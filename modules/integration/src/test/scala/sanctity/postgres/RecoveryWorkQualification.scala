package sanctity.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import sanctity.app.PolicyClientConfiguration
import sanctity.application.{Checked,IdSource}
import sanctity.kernel.*
import sanctity.policy.PolicyWire.*
import java.nio.file.{Files,Path}
import java.time.Instant

/** Source setup and worker probes; usable against the qualified predecessor jars. */
object RecoveryWorkQualification:
  private val ids = IdSource.random[IO]
  private def id[A](value: String): Id[A] = Id.parse[A](value).toOption.get
  private def checked[A](value: IO[Either[Problem,A]]): IO[A] = value.flatMap(Checked.lift[IO,A])
  def main(args: Array[String]): Unit =
    val program = for
      config <- IO.fromEither(DatabaseConfig.environment(sys.env).left.map(p => new IllegalArgumentException(p.toString)))
      result <- PolicyClientConfiguration.load(Path.of(args(1))).use { trust =>
        PostgresDatabase.open[IO](config,Some(trust)).use { db =>
          val ctx = AccessContext(id[PrincipalTag](args(2)))
          val data = id[DatasetTag](args(3))
          val file = Path.of(args(4))
          if args(0) == "setup" then for
            snapshot <- db.organization.snapshot
            project = snapshot.placements(data).project
            original <- checked(db.catalog(ids).dataset(ctx,data))
            custodian = original.custodian
            created <- checked(db.organization.createDataset(ctx,custodian,project,IdempotencyKey.from("pending-recovery-work").toOption.get,
              snapshot.projects(project).generation))
            operation <- checked(db.journal(ids).begin(ctx,created._1.id,IdempotencyKey.from("pending-recovery-import").toOption.get,
              "synthetic-empty-source",Vector.empty))
            lease <- checked(db.journal(ids).claim(ctx,operation.id,3600))
            delivery <- checked(db.outbox(ids).claim(ctx,data,"recovery-qualification",3600)).map(_.get)
            saved = obj("pendingDataset" -> str(created._1.id.render),"operation" -> str(operation.id.render),
              "importToken" -> str(lease.token.render),"importUntil" -> str(lease.expiresAt.toString),
              "sequence" -> str(delivery.sequence.toString),"target" -> str(delivery.target),
              "deliveryToken" -> str(delivery.token.render),"release" -> str(delivery.release.release.render),
              "deliveryUntil" -> str(delivery.expiresAt.toString))
            _ <- IO.blocking {
              Files.writeString(file,saved.noSpaces)
              Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            }
          yield obj("setup" -> Json.True)
          else
            val saved = parse(Files.readAllBytes(file))
            val lease = WorkLease(id[OperationTag](text(saved,"operation")),id[LeaseTag](text(saved,"importToken")),Instant.parse(text(saved,"importUntil")))
            val delivery = EventLease(natural(text(saved,"sequence")),text(saved,"target"),id[LeaseTag](text(saved,"deliveryToken")),
              ReleaseRef(data,id[ReleaseTag](text(saved,"release"))),Instant.parse(text(saved,"deliveryUntil")))
            if args(0) == "closed" then for
              read <- db.catalog(ids).dataset(ctx,data)
              claim <- db.journal(ids).claim(ctx,lease.operation,5)
              outbox <- db.outbox(ids).claim(ctx,data,delivery.target,5)
              ready <- db.checkCompatibility().attempt
              _ <- IO(check(read.isLeft && claim.isLeft && outbox.isLeft && ready.isLeft,"closed-work-probe"))
            yield obj("readDenied" -> Json.True,"importClaimDenied" -> Json.True,"deliveryClaimDenied" -> Json.True,"startupDenied" -> Json.True)
            else for
              oldImport <- db.journal(ids).renew(ctx,lease,5)
              oldDelivery <- db.outbox(ids).acknowledge(ctx,delivery)
              _ <- IO(check(oldImport.isLeft && oldDelivery.isLeft,"restored-lease-reused"))
              renewed <- checked(db.journal(ids).claim(ctx,lease.operation,5))
              fresh <- checked(db.outbox(ids).claim(ctx,data,delivery.target,5)).map(_.get)
              _ <- IO(check(renewed.token != lease.token && fresh.token != delivery.token,"recovery-lease-token-reused"))
              _ <- checked(db.outbox(ids).acknowledge(ctx,fresh))
            yield obj("oldImportDenied" -> Json.True,"oldDeliveryDenied" -> Json.True,"pendingImportReacquired" -> Json.True,
              "freshDeliveryAcknowledged" -> Json.True)
        }
      }
      _ <- IO.println(result.noSpaces)
    yield ()
    try program.unsafeRunSync()
    catch case e: Throwable =>
      e.printStackTrace()
      System.exit(1)
