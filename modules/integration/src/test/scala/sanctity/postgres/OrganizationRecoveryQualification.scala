package sanctity.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import sanctity.app.PolicyClientConfiguration
import sanctity.policy.PolicyWitnessClient
import sanctity.policy.PolicyWire.*
import io.circe.Json
import java.nio.file.{Files,Path}
import java.util.UUID

/** Synthetic diagnostics and process stops live only in the separate test JAR. */
object OrganizationRecoveryQualification:
  def main(args: Array[String]): Unit =
    val program = for
      config <- IO.fromEither(DatabaseConfig.environment(sys.env).left.map(p => new IllegalArgumentException(p.toString)))
      result <- PolicyClientConfiguration.load(Path.of(args(1))).use { recovery =>
        PolicyClientConfiguration.load(Path.of(args(2))).use { writer =>
          PostgresDatabase.open[IO](config).use { db =>
            val engine = new OrganizationRecovery[IO](db,config,recovery,writer)
            args(0) match
              case "prepare" => IO.blocking {
                val before = RecoveryJournal.current(writer)
                val history = (1L to natural(text(before,"sequence"))).map { sequence =>
                  val response = recovery.client.call(PolicyWitnessClient.request("RECORD",obj("sequence" -> str(sequence.toString)),0,zero))
                  recovery.client.verifyRecord(field(response,"record"))
                }
                val original = field(history.reverse.find(r => text(r,"kind") == "COMMIT").get,"command")
                val payload = io.circe.jawn.parse(utf8(unbase64(text(original,"payload")))).toOption.get.mapObject(o =>
                  o.add("event",str(UUID.randomUUID().toString)).add("epoch",field(before,"epoch"))
                    .add("previousRevision",field(before,"revision")).add("revision",str(increment(natural(text(before,"revision"))).toString))
                    .add("previousHash",field(before,"appliedHash")))
                val encoded = payload.printWith(io.circe.Printer.noSpaces.copy(sortKeys=true))
                val applied = sanctity.kernel.Digest.framed("sanctity.policy-local.v2",Vector(text(payload,"previousHash"),text(payload,"event"),
                  text(payload,"revision"),digest(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).value
                val command = PolicyBridge.envelope(encoded,applied,text(before,"capabilities"))
                writer.client.call(PolicyWitnessClient.request("PREPARE",command,natural(text(before,"sequence")),text(before,"journalHash")))
                Files.writeString(Path.of(args(3)),command.noSpaces)
                obj("pending" -> Json.True)
              }
              case "abort" => IO.blocking {
                val before = RecoveryJournal.current(writer)
                val command = parse(Files.readAllBytes(Path.of(args(3))))
                writer.client.call(PolicyWitnessClient.request("ABORT",command,natural(text(before,"sequence")),text(before,"journalHash")))
                obj("abortedSyntheticUncommittedIntent" -> Json.True)
              }
              case "preview" => engine.preview(Path.of(args(3)),Path.of(args(4)),args(5),args(6))
              case "verify" => engine.restore(Path.of(args(3)),Path.of(args(4)),args(5),args(6),
                sys.env("SANCTITY_PG_RESTORE"),sys.env("SANCTITY_PSQL")).flatMap(_ => engine.verify(args(5),Path.of(args(4))))
              case "reconcile" => engine.reconcileObserved(args(3),Path.of(args(4)),stage =>
                if args.length > 5 && stage == args(5) then IO.blocking {
                  Files.writeString(Path.of(args(6)),stage)
                  new java.util.concurrent.CountDownLatch(1).await()
                } else IO.unit)
          }
        }
      }
      _ <- IO.println(result.spaces2)
    yield ()
    try program.unsafeRunSync()
    catch case e: Throwable =>
      e.printStackTrace()
      System.exit(1)

/** Authenticate readiness without any database configuration or connection. */
object RecoveryWitnessReadiness:
  def main(args: Array[String]): Unit =
    PolicyClientConfiguration.load(Path.of(args(0))).use(trust => IO.blocking(RecoveryJournal.current(trust)))
      .flatMap(value => IO.println(value.noSpaces)).unsafeRunSync()
