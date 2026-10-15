import sbt._

/** Resolve the exact service classpath through sbt; packaging never guesses target filenames. */
object ServiceDistribution {
  val inputs = taskKey[File]("Write verified service JAR paths, coordinates and reported licenses for packaging")

  def write(root: File, target: File, classpath: Seq[File], internal: Seq[(File, File)],
      report: UpdateReport, upstream: File, version: String, scalaVersion: String, sbtVersion: String): File = {
    val replacements = internal.map { case (classes, jar) => classes.getCanonicalFile -> jar.getCanonicalFile }.toMap
    val localJars = replacements.values.toSet
    val reports = report.configurations.flatMap(_.modules).filterNot(_.evicted)
      .flatMap(m => m.artifacts.map { case (_, file) => file.getCanonicalFile -> m }).groupBy(_._1)
    val jars = classpath.map(f => replacements.getOrElse(f.getCanonicalFile, f.getCanonicalFile)).distinct
    require(jars.forall(f => f.isFile && f.getName.endsWith(".jar")), "service runtime must resolve entirely to packaged JARs")
    require(jars.map(_.getName).distinct.size == jars.size, "service JAR basenames collide")
    val properties = new java.util.Properties
    val pin = new java.io.FileInputStream(root / "project/bids4s-source.properties")
    try properties.load(pin) finally pin.close()
    val document = <service version={version} scala={scalaVersion} sbt={sbtVersion} javaMinimum="21"
        upstreamSha256={properties.getProperty("sha256")}>
      {jars.map { jar =>
        val external = reports.getOrElse(jar, Nil).map(_._2)
        val module = external.headOption.map(m => m.module.organization + ":" + m.module.name + ":" + m.module.revision)
          .getOrElse(if (jar == upstream.getCanonicalFile) "bids4s-source:" + properties.getProperty("sha256")
            else "io.github.canardlapin:" + jar.getName)
        require(localJars(jar) || external.nonEmpty, "unidentified runtime artifact: " + jar.getName)
        val licenses = external.flatMap(_.licenses).distinct.sortBy(_._1)
        <jar path={jar.getAbsolutePath} module={module} internal={localJars(jar).toString}
            upstream={(jar == upstream.getCanonicalFile).toString}>
          {licenses.map { case (name, url) => <license name={name} url={url.getOrElse("")}/> }}
        </jar>
      }}
    </service>
    val output = target / "distribution-inputs.xml"
    IO.write(output, new scala.xml.PrettyPrinter(120, 2).format(document) + "\n")
    output
  }
}
