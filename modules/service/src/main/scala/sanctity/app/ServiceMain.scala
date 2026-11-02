package sanctity.app

import java.util.Properties

/** Deliberately excludes demo and analysis dispatch; all operational commands remain explicit. */
object ServiceMain:
  private val usage = "sanctity serve | migrate [--runtime-role ROLE | --organization-preview OPERATOR NEW_FILE | --organization-bootstrap OPERATOR DIGEST] | repository <arguments> | backup capture STORAGE NEW_BUNDLE ARTIFACT | backup verify BUNDLE | recovery <arguments> | organization recovery-needs|create-lab|disable-principal|recover-steward <arguments> | policy enroll WRITER_CONFIG RECOVERY_CONFIG|readiness|resolve | policy-witness init|serve|inspect CONFIG | --version | --help"

  def main(args: Array[String]): Unit =
    if Runtime.version().feature() < 21 then
      System.err.println("Sanctity requires Java 21 or newer.")
      System.exit(2)
    else args.toList match
      case "--help" :: Nil => println(usage)
      case "--version" :: Nil =>
        val properties = new Properties()
        val stream = Option(getClass.getResourceAsStream("/sanctity/service-build.properties"))
          .getOrElse(throw new IllegalStateException("service build metadata is missing"))
        try properties.load(stream)
        finally stream.close()
        println(s"Sanctity ${properties.getProperty("version")} (Scala ${properties.getProperty("scala")}; Java 21+)")
      case "serve" :: Nil => ApiServer.main(Array.empty)
      case "migrate" :: rest => MigrationCli.main(rest.toArray)
      case "repository" :: rest => RepositoryCli.main(rest.toArray)
      case "recovery" :: rest => RecoveryCli.main(rest.toArray)
      case "backup" :: rest => BackupCli.main(rest.toArray)
      case "organization" :: rest => OrganizationCli.main(rest.toArray)
      case "policy" :: rest => PolicyCatalogCli.main(rest.toArray)
      case "policy-witness" :: rest => PolicyWitnessCli.main(rest.toArray)
      case _ =>
        System.err.println(usage)
        System.exit(2)
