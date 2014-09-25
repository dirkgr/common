package org.allenai.pipeline

import org.allenai.common.testkit.UnitSpec
import org.allenai.pipeline.IoHelpers._

import org.apache.commons.io.FileUtils
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import spray.json.DefaultJsonProtocol._

import scala.util.Random

import java.io.{InputStream, File}

/**
 * Created by rodneykinney on 9/25/14.
 */
class SampleExperiment extends UnitSpec with BeforeAndAfterEach with BeforeAndAfterAll {

  case class TrainedModel(info: String)

  object TrainedModel {
    val jsonFormat = jsonFormat1(apply)
  }

  class JoinAndSplitData(features: PipelineStep[Iterable[Array[Double]]],
                         labels: PipelineStep[Iterable[Boolean]],
                         testSizeRatio: Double)
    extends PipelineStep[(Iterable[(Boolean, Array[Double])], Iterable[(Boolean,
      Array[Double])])] with Ai2CodeInfo {
    def create = {
      val rand = new Random
      val data = labels.get.zip(features.get)
      val testSize = math.round(testSizeRatio * data.size).toInt
      (data.drop(testSize), data.take(testSize))
    }

    override def signature = Signature.fromFields(this, "features", "labels", "testSizeRatio")
  }

  case class TrainModel(trainingData: PipelineStep[Iterable[(Boolean, Array[Double])]])
    extends PipelineStep[TrainedModel] with Ai2CodeInfo {
    def create: TrainedModel = {
      val dataRows = trainingData.get
      train(dataRows) // Run training algorithm on training data
    }

    def train(data: Iterable[(Boolean, Array[Double])]): TrainedModel =
      TrainedModel(s"Trained model with ${data.size} rows")

    override def signature = Signature.fromObject(this)
  }

  type PRMeasurement = Iterable[(Double, Double, Double)]

  // Threshold, precision, recall
  case class MeasureModel(val model: PipelineStep[TrainedModel],
                          val testData: PipelineStep[Iterable[(Boolean, Array[Double])]])
    extends PipelineStep[PRMeasurement] with Ai2CodeInfo {
    def create = {
      model.get
      // Just generate some dummy data
      val rand = new Random
      import math.exp
      var a = 0.0
      var b = 0.0
      for (i <- (0 until testData.get.size)) yield {
        val r = (exp(-a), 1 - exp(-b), exp(-b))
        a += rand.nextDouble * .03
        b += rand.nextDouble * .03
        r
      }
    }

    override def signature = Signature.fromObject(this)
  }

  case class ParsedDocument(info: String)

  case class FeaturizeDocuments(documents: PipelineStep[Iterator[ParsedDocument]])
    extends PipelineStep[Iterable[Array[Double]]] with Ai2CodeInfo {
    def create = {
      val features = for (doc <- documents.get) yield {
        val rand = new Random
        Array.fill(8)(rand.nextDouble)
      }
      features.toList
    }

    def signature = Signature.fromObject(this)
  }

  object ParseDocumentsFromXML extends ArtifactIo[Iterator[ParsedDocument], StructuredArtifact]
  with Ai2CodeInfo {
    def read(a: StructuredArtifact): Iterator[ParsedDocument] = {
      for ((id, is) <- a.reader.readAll) yield parse(id, is)
    }

    def parse(id: String, is: InputStream): ParsedDocument = ParsedDocument(id)

    // Writing back to XML not supported
    def write(data: Iterator[ParsedDocument], artifact: StructuredArtifact) = ???

    override def toString = this.getClass.getSimpleName
  }

  class TrainModelPython(val data: Producer[FlatArtifact], val io: ArtifactIo[TrainedModel,
    FileArtifact])
    extends PipelineStep[TrainedModel] with Ai2CodeInfo {
    def create: TrainedModel = {
      val inputFile = File.createTempFile("trainData", ".tsv")
      val outputFile = File.createTempFile("model", ".json")
      data.get.copyTo(new FileArtifact(inputFile))
      import sys.process._
      import scala.language.postfixOps
      // In real world, omit "echo"
      val stdout: String = s"echo train.py -input $inputFile -output $outputFile" !!
      // val model = io.read(new FileArtifact(outputFile)) // In real world, return this
      val model = TrainedModel(stdout)
      model
    }
    override def signature = Signature.fromFields(this, "data", "io")
  }


  val outputDir = new File("pipeline/test-output")
  val inputDir = new File("pipeline/src/test/resources/pipeline")
  val featureFile = "features.txt"
  val labelFile = "labels.txt"

  // Enable JSON serialization for our trained model object

  import org.allenai.pipeline.IoHelpers._

  implicit val modelFormat = TrainedModel.jsonFormat

  implicit val prMeasurementFormat: StringSerializable[(Double, Double, Double)] = tuple3ColumnFormat[Double, Double, Double](',')

  // Define our persistence implementation

  val input = new RelativeFileSystem(inputDir)

  "Sample Experiment" should "complete" in {
    // TSV format for label+features is <label><tab><comma-separated feature values>
    implicit val featureFormat = columnArrayFormat[Double](',')
    implicit val labelFeatureFormat = tuple2ColumnFormat[Boolean, Array[Double]]('\t')

    implicit val runner = PipelineRunner.writeToDirectory(outputDir)
//    implicit val runner = PipelineRunner.writeToS3(S3Config("ai2-s2"), "pipeline/unit-test")

    val docDir = new DirectoryArtifact(new File(inputDir, "xml"))
    val docs = Read.fromArtifact(ParseDocumentsFromXML, docDir).
    asInstanceOf[PipelineStep[Iterator[ParsedDocument]]]
    val docFeatures = new FeaturizeDocuments(docs) // use in place of featureData above

    val labelData: PipelineStep[Iterable[Boolean]] =
      Read.collection.fromText[Boolean](input.flatArtifact(labelFile))
        .asInstanceOf[PipelineStep[Iterable[Boolean]]]
    // Define pipeline
    val Producer2(trainData, testData) = new JoinAndSplitData(docFeatures, labelData, 0.2)
    val trainingDataFile = Persist.Collection.asText(trainData).asArtifact
    val model = Persist.Singleton.asJson(new TrainModelPython(trainingDataFile,
      SingletonIo.json[TrainedModel]))
    val measure: PipelineStep[PRMeasurement] = Persist.Collection.asText(
      new MeasureModel(model, testData))
    runner.run(measure)

    assert(findFile(outputDir, "JoinAndSplitData_1", ".txt"), "Training data file created")
    assert(findFile(outputDir, "TrainModelPython", ".json"), "Json file created")
    assert(findFile(outputDir, "MeasureModel", ".txt"), "P/R file created")
    assert(findFile(outputDir, "experiment",".html"), "Experiment summary created")
  }

  override def beforeEach: Unit = {
    require((outputDir.exists && outputDir.isDirectory) ||
      outputDir.mkdirs, s"Unable to create test output directory $outputDir")
  }

  override def afterEach: Unit = {
    FileUtils.cleanDirectory(outputDir)
  }

  override def afterAll: Unit = {
    FileUtils.deleteDirectory(outputDir)
  }

  def findFile(dir: File, prefix: String, suffix: String): Boolean =
    dir.listFiles.map(_.getName).exists(s => s.startsWith(prefix) && s.endsWith(suffix))

}
