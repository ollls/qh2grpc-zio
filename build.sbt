import Dependencies._
import scalapb.compiler.Version.scalapbVersion
import sbtprotoc.ProtocPlugin.autoImport.PB

ThisBuild / scalaVersion := "3.3.3"
ThisBuild / version := "0.7"
ThisBuild / versionScheme := Some("early-semver")

ThisBuild / developers := List(
  Developer(
    id = "ostrygun",
    name = "Oleg Strygun",
    email = "ostrygun@gmail.com",
    url = url("https://github.com/ollls/")
  )
)


//The grpc = true parameter tells ScalaPB to generate standard gRPC service stubs. 
//When using zio-grpc, you need grpc = false because the ZIO code generaton.
//scalapb.gen(grpc = false)

Compile / PB.targets := Seq(
  scalapb.gen(grpc = true) -> (Compile / sourceManaged).value / "scalapb",
  scalapb.zio_grpc.ZioCodeGenerator -> (Compile / sourceManaged).value / "scalapb"
)

ThisBuild / licenses := List("Apache 2" -> java.net.URI.create("http://www.apache.org/licenses/LICENSE-2.0.txt").toURL)
ThisBuild / homepage := Some(url("https://github.com/ollls/zio-quartz-h2"))
ThisBuild / credentials += Credentials(Path.userHome / ".sbt" / ".credentials")
ThisBuild / credentials += Credentials(
  "GnuPG Key ID",
  "gpg",
  "F85809244447DB9FA35A3C9B1EB44A5FC60F4104", // key identifier
  "ignored" // this field is ignored; passwords are supplied by pinentry
)

ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  val nexus = "https://s01.oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
ThisBuild / publishMavenStyle := true

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/ollls/zio-quartz-h2"),
    "scm:git@github.com:ollls/zio-quartz-h2"
  )
)

Runtime / unmanagedClasspath += baseDirectory.value / "src" / "main" / "resources"

// Main project
lazy val root = (project in file("."))
  .enablePlugins(ProtocPlugin)
  .settings(
    name := "zio-qh2-examples",
    organization := "io.github.ollls",
    publish / skip := true,
    Compile / scalaSource := baseDirectory.value / "src" / "main" / "scala",
    Compile / mainClass := Some("com.example.Run"),
    libraryDependencies += scalaTest % Test,
    libraryDependencies += "io.github.ollls" %% "zio-quartz-h2" % "0.7.1",
    libraryDependencies += "dev.zio" %% "zio-streams" % "2.1.16",
    libraryDependencies += "dev.zio" %% "zio" % "2.1.16",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-no-indent",
      "-Wunused:imports"
    ),
    libraryDependencies += "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapbVersion,
    libraryDependencies += "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-core" % "0.6.3"
  )
