package sanctity.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie.implicits.*
import io.circe.jawn.JawnParser
import munit.FunSuite
import org.http4s.*
import org.typelevel.ci.CIString
import sanctity.application.*
import sanctity.bids.{DatasetInterpreter, ReleaseDescriptions}
import sanctity.http.RepositoryHttp
import sanctity.kernel.*
import sanctity.objects.NativeDirectorySource
import scala.concurrent.duration.*

/** SEE2 on PostgreSQL: the record is stored once with the acceptance, the operator backfill stores the same record
  * for older releases without replacing any, and the HTTP read follows metadata access to the exact release.
  */
final class CatalogRecordSuite extends FunSuite:
  override val munitTimeout = 5.minutes
  private def checked[A](value: IO[Either[Problem, A]]): IO[A] = value.flatMap(Checked.lift[IO, A])
  private def key(s: String) = IdempotencyKey.from(s).toOption.get
  private def credentials(f: PgFixture) = f.database.credentials(IdSource.random[IO])
  private def describing(f: PgFixture): NativeRepository[IO] =
    new NativeRepository[IO](f.system.catalog, f.system.store, new DatasetInterpreter[IO](f.system.store), IdSource.random[IO],
      Some(new ReleaseDescriptions[IO](f.system.store)))
  private def api(f: PgFixture) = new RepositoryHttp[IO](f.system.catalog, describing(f), credentials(f)).app
  private def get(f: PgFixture, target: String, token: String): IO[(Status, String)] =
    api(f).run(Request[IO](Method.GET, Uri.unsafeFromString(target)).putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $token")))
      .flatMap(r => r.as[String].map(r.status -> _))
  private def source(f: PgFixture) = new NativeDirectorySource[IO](f.root.resolve("source"))
  private def rows(f: PgFixture): IO[Int] = sql"SELECT count(*) FROM release_catalog".query[Int].unique.transact(f.database.transactor)
  private def url(r: ReleaseRef) = s"/v1/datasets/${r.dataset.render}/releases/${r.release.render}/catalog"

  test("acceptance stores one record in its own transaction; a replay stores nothing more; the HTTP read needs metadata access") {
    PostgresFixture.resource.use { f =>
      for
        preview <- checked(describing(f).importNative(f.system.context, f.system.dataset.id, source(f)))
        before <- rows(f)
        receipt <- checked(describing(f).accept(f.system.context, preview.candidate.id, key("first")))
        replay <- checked(describing(f).accept(f.system.context, preview.candidate.id, key("first")))
        _ = assertEquals(replay, receipt)
        after <- rows(f)
        _ = assertEquals((before, after), (0, 1))
        stored <- checked(f.system.catalog.catalogRecord(f.system.context, receipt.release))
        release <- checked(f.system.catalog.release(f.system.context, receipt.release))
        expected <- new ReleaseDescriptions[IO](f.system.store).describe(release.manifest, release.interpretation)
        _ = assertEquals(stored, Some(expected))
        _ = assert(expected.title.nonEmpty && expected.dataTypes.nonEmpty, expected.toString)
        owner <- checked(credentials(f).issue(f.system.context.principal, "owner", 3600))
        read <- get(f, url(receipt.release), owner.secret)
        _ = assertEquals(read._1, Status.Ok, read._2)
        body = new JawnParser().parse(read._2).toOption.get.hcursor
        _ = assertEquals(body.get[String]("kind").toOption, Some("bids"))
        _ = assertEquals(body.get[Option[String]]("title").toOption.flatten, expected.title)
        _ = assertEquals(body.get[Vector[String]]("dataTypes").toOption, Some(expected.dataTypes))
        _ = assertEquals(body.keys.map(_.toSet), Some(Set("kind", "title", "description", "descriptionFrom", "creators", "keywords", "dataTypes", "license", "identifiers")))
        // Someone with no access to the release learns nothing; metadata access to exactly that release is enough.
        guest <- IdSource.random[IO].next[PrincipalTag]
        _ <- checked(f.system.catalog.grant(f.system.context, f.system.dataset.id, guest, Some(receipt.release.release), Set(Action.ReadMetadata)))
        token <- checked(credentials(f).issue(guest, "guest", 3600))
        shared <- get(f, url(receipt.release), token.secret)
        _ = assertEquals(shared, read)
        elsewhere <- get(f, s"/v1/datasets/${receipt.release.dataset.render}/releases/${java.util.UUID.randomUUID()}/catalog", token.secret)
        _ = assertEquals(elsewhere, Status.NotFound -> """{"error":"not_found"}""")
      yield ()
    }.unsafeRunSync()
  }

  test("the operator backfill stores, for a release accepted before records were kept, the record acceptance would have stored, and never replaces one") {
    PostgresFixture.resource.use { f =>
      val catalog = f.database.catalog(IdSource.random[IO])
      for
        // The fixture's own repository keeps no records, as every release before this change.
        preview <- checked(f.system.repository.importNative(f.system.context, f.system.dataset.id, source(f)))
        receipt <- checked(f.system.repository.accept(f.system.context, preview.candidate.id, key("old")))
        absent <- checked(catalog.catalogRecord(f.system.context, receipt.release))
        missing <- checked(catalog.releasesWithoutCatalogRecord)
        _ = assertEquals((absent, missing), (None, Vector(receipt.release)))
        // What a read computes on demand, without storing it.
        onDemand <- checked(describing(f).catalogRecord(f.system.context, receipt.release))
        none <- rows(f)
        _ = assertEquals(none, 0)
        release <- checked(catalog.acceptedRelease(receipt.release))
        record <- new ReleaseDescriptions[IO](f.system.store).describe(release.manifest, release.interpretation)
        _ = assertEquals(onDemand, Some(record))
        first <- checked(catalog.backfillCatalogRecord(receipt.release, record))
        second <- checked(catalog.backfillCatalogRecord(receipt.release, CatalogRecord.empty(CatalogRecord.Archive)))
        _ = assertEquals((first, second), (true, false))
        stored <- checked(catalog.catalogRecord(f.system.context, receipt.release))
        left <- checked(catalog.releasesWithoutCatalogRecord)
        _ = assertEquals((stored, left), (Some(record), Vector.empty))
        // The release itself is what it was.
        again <- checked(catalog.acceptedRelease(receipt.release))
        _ = assertEquals((again.manifest.id, again.interpretation.id, again.assessment.id), (release.manifest.id, release.interpretation.id, release.assessment.id))
      yield ()
    }.unsafeRunSync()
  }
