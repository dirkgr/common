import sbtrelease.ReleaseStateTransformations._

ivyLoggingLevel in ThisBuild := UpdateLogging.Quiet

lazy val buildSettings = Seq(
  organization := "org.allenai.common",
  resolvers ++= Seq(
    "AllenAI Bintray" at "http://dl.bintray.com/allenai/maven",
    Resolver.jcenterRepo
  ),
  releaseProcess := Seq(
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishArtifacts,
    setNextVersion,
    commitNextVersion,
    pushChanges
  ),
  bintrayPackage := s"${organization.value}:${name.value}_${scalaBinaryVersion.value}",
  licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html")),
  homepage := Some(url("https://github.com/allenai/common")),
  scmInfo := Some(ScmInfo(
    url("https://github.com/allenai/common"),
    "https://github.com/allenai/common.git")),
  bintrayRepository := "maven",
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  pomExtra := (
    <developers>
      <developer>
        <id>allenai-dev-role</id>
        <name>Allen Institute for Artificial Intelligence</name>
        <email>dev-role@allenai.org</email>
      </developer>
    </developers>),
  crossScalaVersions := Seq(Dependencies.defaultScalaVersion),
  scalaVersion <<= crossScalaVersions { (vs: Seq[String]) => vs.head },
  apiURL := Some(url("https://allenai.github.io/common/"))
)

lazy val cache = Project(id = "cache", base = file("cache"))
    .settings(buildSettings)
    .enablePlugins(LibraryPlugin)
    .dependsOn(core, testkit % "test->compile")

lazy val core = Project(id = "core", base = file("core"))
    .settings(buildSettings)
    .enablePlugins(LibraryPlugin)
    .dependsOn(testkit % "test->compile")

lazy val guice = Project(id = "guice", base = file("guice"))
    .settings(buildSettings)
    .enablePlugins(LibraryPlugin)
    .dependsOn(core, testkit % "test->compile")

lazy val indexing = Project(id = "indexing", base = file("indexing"))
    .settings(buildSettings)
    .enablePlugins(LibraryPlugin)
    .dependsOn(core, testkit % "test->compile")

lazy val testkit = Project(id = "testkit", base = file("testkit"))
    .settings(buildSettings)
    .enablePlugins(LibraryPlugin)

lazy val webapp = Project(id = "webapp", base = file("webapp"))
    .settings(buildSettings)
    .enablePlugins(LibraryPlugin)
    .dependsOn(core, testkit % "test->compile")

lazy val common = Project(id = "common", base = file(".")).settings(
  // Don't publish a jar for the root project.
  publishArtifact := false,
  publishTo := Some("dummy" at "nowhere"),
  publish := { },
  publishLocal := { },
  scaladocGenGitRemoteRepo := "git@github.com:allenai/common.git"
).aggregate(
  cache,
  core,
  guice,
  indexing,
  testkit,
  webapp
).enablePlugins(LibraryPlugin, ScaladocGenPlugin)
