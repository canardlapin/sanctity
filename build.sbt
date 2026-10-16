ThisBuild / organization := "io.github.canardlapin"
ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / publish / skip := true

lazy val bids4sDirectory = sys.props.get("sanctity.bids4s.path").map(file(_).getCanonicalFile).getOrElse {
  val archive = file("vendor/bids4s-source.zip").getCanonicalFile
  val properties = new java.util.Properties
  val input = new java.io.FileInputStream(file("project/bids4s-source.properties"))
  try properties.load(input) finally input.close()
  val expected = properties.getProperty("sha256")
  val actual = java.security.MessageDigest.getInstance("SHA-256").digest(IO.readBytes(archive))
    .map(b => f"${b & 0xff}%02x").mkString
  require(actual == expected, "bids4s source snapshot digest mismatch")
  val directory = file("target/source-dependencies/bids4s-" + expected).getCanonicalFile
  IO.unzip(archive, directory)
  directory
}
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
    val own = Seq("kernel", "application", "bids", "processing-mri").flatMap(m => ((root / "modules" / m / "src/main") ** "*.scala").get) ++ ((root / "modules/processing-mri/src/main/resources") ** "*").get.filter(_.isFile) ++ Seq(root / "build.sbt")
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
  ),
  // The derived OrganizationCommand codec (23 cases) exceeds the default inline budget; derivation keeps the stored format.
  scalacOptions += "-Xmax-inlines:64"
)
lazy val importDicom = project.in(file("modules/import-dicom")).dependsOn(bids, objects).settings(common)
lazy val processingMri = project.in(file("modules/processing-mri")).dependsOn(bids, objects).settings(common)
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
lazy val integration = project.in(file("modules/integration")).dependsOn(app % "compile->compile;test->test,compile", postgres % "test->test").settings(common).settings(
  Test / parallelExecution := false
)
lazy val service = project.in(file("modules/service")).dependsOn(bids, objects, postgres, http).settings(common).settings(
  Compile / mainClass := Some("sanctity.app.ServiceMain"),
  Compile / resourceGenerators += Def.task {
    val output = (Compile / resourceManaged).value / "sanctity/service-build.properties"
    IO.write(output, "version=" + version.value + "\nscala=" + scalaVersion.value + "\njavaMinimum=21\n")
    Seq(output)
  }.taskValue,
  ServiceDistribution.inputs := {
    require(!sys.props.contains("sanctity.bids4s.path"), "service distributions require the verified vendored bids4s source")
    val internal = Seq(
      (kernel / Compile / classDirectory).value -> (kernel / Compile / packageBin).value,
      (application / Compile / classDirectory).value -> (application / Compile / packageBin).value,
      (bids / Compile / classDirectory).value -> (bids / Compile / packageBin).value,
      (objects / Compile / classDirectory).value -> (objects / Compile / packageBin).value,
      (postgres / Compile / classDirectory).value -> (postgres / Compile / packageBin).value,
      (http / Compile / classDirectory).value -> (http / Compile / packageBin).value,
      (Compile / classDirectory).value -> (Compile / packageBin).value,
      (bids4sCore / Compile / classDirectory).value -> (bids4sCore / Compile / packageBin).value
    )
    ServiceDistribution.write((LocalRootProject / baseDirectory).value, target.value,
      (Runtime / fullClasspath).value.map(_.data), internal, (update).value,
      (bids4sCore / Compile / packageBin).value, version.value, scalaVersion.value, sbtVersion.value)
  }
)
lazy val app = project.in(file("modules/app")).dependsOn(service, importDicom, importXnat, processingMri).settings(common).settings(
  Compile / run / fork := true,
  Compile / mainClass := Some("sanctity.app.Demo")
)
lazy val root = project.in(file(".")).aggregate(kernel, application, bids, objects, postgres, importDicom, importXnat, http, processingMri, service, app).settings(
  name := "sanctity",
  publish / skip := true
)

addCommandAlias("check", ";compile;test")
