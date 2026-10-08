ThisBuild / organization := "io.github.canardlapin"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / publish / skip := true

lazy val bids4sDirectory = file(sys.props.getOrElse("sanctity.bids4s.path", "../bids4s")).getCanonicalFile
lazy val bids4sCore = ProjectRef(bids4sDirectory.toURI, "coreJVM")

lazy val common = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Werror", "-Wunused:all"),
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit" % "1.3.0" % Test,
    "org.scalameta" %% "munit-scalacheck" % "1.3.0" % Test
  ),
  Test / fork := true
)

lazy val kernel = project.in(file("modules/kernel")).settings(common)
lazy val application = project.in(file("modules/application")).dependsOn(kernel).settings(common).settings(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect" % "3.5.7",
    "co.fs2" %% "fs2-core" % "3.11.0"
  )
)
lazy val bids = project.in(file("modules/bids")).dependsOn(application, bids4sCore).settings(common).settings(
  libraryDependencies += "io.circe" %% "circe-jawn" % "0.14.10",
  Compile / sourceGenerators += Def.task {
    val root = (LocalRootProject / baseDirectory).value
    def fingerprint(base: File, inputs: Seq[File]): String = {
      val md = java.security.MessageDigest.getInstance("SHA-256")
      inputs.distinct.sortBy(_.getAbsolutePath).foreach { f =>
        val name = IO.relativize(base, f).getOrElse(f.getName).getBytes("UTF-8")
        val bytes = IO.readBytes(f)
        md.update(java.nio.ByteBuffer.allocate(4).putInt(name.length).array())
        md.update(name)
        md.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.length.toLong).array())
        md.update(bytes)
      }
      md.digest().map(b => f"${b & 0xff}%02x").mkString
    }
    val upstream = ((bids4sDirectory / "modules/core/shared/src/main") ** "*.scala").get ++
      ((bids4sDirectory / "modules/core/jvm/src/main") ** "*.scala").get ++ Seq(bids4sDirectory / "build.sbt")
    val own = Seq("kernel", "application", "bids").flatMap(m => ((root / "modules" / m / "src/main") ** "*.scala").get) ++ Seq(root / "build.sbt")
    val output = (Compile / sourceManaged).value / "sanctity/bids/BuildIdentity.scala"
    IO.write(output, "package sanctity.bids\nobject BuildIdentity:\n" +
      "  val bids4s: String = \"sha256:" + fingerprint(bids4sDirectory, upstream) + "\"\n" +
      "  val sanctity: String = \"sha256:" + fingerprint(root, own) + "\"\n")
    Seq(output)
  }.taskValue
)
lazy val objects = project.in(file("modules/objects")).dependsOn(application).settings(common).settings(
  libraryDependencies += "co.fs2" %% "fs2-io" % "3.11.0"
)
lazy val postgres = project.in(file("modules/postgres")).dependsOn(application).settings(common).settings(
  libraryDependencies ++= Seq(
    "org.tpolecat" %% "doobie-core" % "1.0.0-RC6",
    "org.tpolecat" %% "doobie-hikari" % "1.0.0-RC6",
    "org.tpolecat" %% "doobie-postgres" % "1.0.0-RC6",
    "org.postgresql" % "postgresql" % "42.7.14",
    "io.circe" %% "circe-jawn" % "0.14.10",
    "io.circe" %% "circe-generic" % "0.14.10",
    "org.slf4j" % "slf4j-nop" % "2.0.16"
  )
)
lazy val importDicom = project.in(file("modules/import-dicom")).dependsOn(bids, objects).settings(common)
lazy val importXnat = project.in(file("modules/import-xnat")).dependsOn(application, objects).settings(common).settings(
  libraryDependencies ++= Seq("org.http4s" %% "http4s-ember-client" % "0.23.30", "io.circe" %% "circe-jawn" % "0.14.10")
)
lazy val http = project.in(file("modules/http")).dependsOn(application).settings(common).settings(
  libraryDependencies ++= Seq(
    "org.http4s" %% "http4s-dsl" % "0.23.30",
    "org.http4s" %% "http4s-ember-server" % "0.23.30",
    "io.circe" %% "circe-jawn" % "0.14.10"
  )
)
lazy val integration = project.in(file("modules/integration")).dependsOn(app % "compile->compile;test->test,compile").settings(common).settings(
  Test / parallelExecution := false
)
lazy val app = project.in(file("modules/app")).dependsOn(bids, objects, postgres, importDicom, importXnat, http).settings(common).settings(
  Compile / run / fork := true,
  Compile / mainClass := Some("sanctity.app.Demo")
)
lazy val root = project.in(file(".")).aggregate(kernel, application, bids, objects, postgres, importDicom, importXnat, http, app).settings(
  name := "sanctity",
  publish / skip := true
)

addCommandAlias("check", ";compile;test")
