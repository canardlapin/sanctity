package sanctity.postgres

import cats.effect.{IO,Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import sanctity.app.PolicyClientConfiguration
import sanctity.application.{Checked,IdSource,NativeRepository}
import sanctity.bids.RepositoryBidsInterpreter
import sanctity.kernel.*
import sanctity.objects.{DirectoryExport,FileBlobStore}
import sanctity.policy.PolicyWire.*
import java.nio.file.{Files,Path}

/** Real export and chunk-boundary probes, excluded from production distribution. */
object RecoveryDeliveryQualification:
  private val ids = IdSource.random[IO]
  private def id[A](value: String): Id[A] = Id.parse[A](value).toOption.get
  private def checked[A](value: IO[Either[Problem,A]]): IO[A] = value.flatMap(Checked.lift[IO,A])
  def main(args: Array[String]): Unit =
    val program = for
      config <- IO.fromEither(DatabaseConfig.environment(sys.env).left.map(p => new IllegalArgumentException(p.toString)))
      result <- PolicyClientConfiguration.load(Path.of(args(1))).use { trust =>
        PostgresDatabase.open[IO](config,Some(trust)).use { db =>
          val ctx=AccessContext(id[PrincipalTag](args(2)))
          val ref=ReleaseRef(id[DatasetTag](args(3)),id[ReleaseTag](args(4)))
          for
            store <- FileBlobStore.open[IO](Path.of(sys.env("SANCTITY_STORAGE")))
            repository = new NativeRepository[IO](db.catalog(ids),store,new RepositoryBidsInterpreter[IO](store),ids)
            result <- if args(0)=="export" then
              checked(repository.exportFull(ctx,ref,new DirectoryExport[IO](Path.of(args(5)))))
                .as(obj("exported" -> Json.True))
            else
              val details=parse(Files.readAllBytes(Path.of(args(5))))
              for
                file <- checked(repository.readFile(ctx,ref,RelPath.from("README").toOption.get))
                emitted <- Ref.of[IO,Long](0L)
                outcome <- file.bytes.chunkN(65536).zipWithIndex.evalTap { (chunk,index) =>
                  emitted.update(_+chunk.size).flatMap { _ =>
                    if index==0 then checked(db.catalog(ids).revoke(AccessContext(id[PrincipalTag](text(details,"issuer"))),
                      ref.dataset,id[GrantTag](text(details,"grant")))) else IO.unit
                  }
                }.compile.drain.attempt
                count <- emitted.get
                _ <- IO(check(outcome.isLeft && count==65536,"new-delivery-chunk-after-revocation"))
              yield obj("bytesBeforeRevocation" -> Json.fromLong(count),"nextChunkDenied" -> Json.True)
          yield result
        }
      }
      _ <- IO.println(result.noSpaces)
    yield ()
    try program.unsafeRunSync()
    catch case e: Throwable =>
      e.printStackTrace()
      System.exit(1)
