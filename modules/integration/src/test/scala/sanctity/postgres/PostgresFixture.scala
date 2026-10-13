package sanctity.postgres

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import doobie.Fragment
import doobie.implicits.*
import sanctity.app.{CatalogLifecycleSuite, RepositorySystem, SyntheticFixture, TestFiles}
import sanctity.application.{Checked, IdSource, NativeRepository}
import sanctity.bids.BidsInterpreter
import sanctity.kernel.*
import sanctity.objects.FileBlobStore

import java.nio.file.{Files, Path}
import java.util.UUID

private[postgres] final case class PgFixture(config: DatabaseConfig, database: PostgresDatabase[IO], system: RepositorySystem, root: Path)

private[postgres] object PostgresFixture:
  def resource: Resource[IO, PgFixture] =
    for
      _ <- Resource.eval(IO.raiseUnless(sys.env.get("SANCTITY_TEST_DISPOSABLE").contains("1"))(
        new IllegalStateException("Run scripts/check-postgres.sh: integration tests require the disposable test database")))
      config <- Resource.eval(Checked.lift[IO, DatabaseConfig](DatabaseConfig.environment(
        sys.env.updated("SANCTITY_DB_SCHEMA", "sanctity_it_" + UUID.randomUUID().toString.replace("-", "")))))
      root <- Resource.make(IO.blocking(Files.createTempDirectory("sanctity-pg-test-")))(TestFiles.remove)
      database <- PostgresDatabase.open[IO](config)
      _ <- Resource.make(database.migrate)(_ => Fragment.const(s"""DROP SCHEMA "${config.schema}" CASCADE""").update.run.transact(database.transactor).void)
      system <- Resource.eval {
        val ids = IdSource.random[IO]
        for
          _ <- SyntheticFixture.create(root.resolve("source"))
          principal <- ids.next[PrincipalTag]
          custodian <- ids.next[CustodianTag]
          store <- FileBlobStore.open[IO](root.resolve("storage"))
          catalog = database.catalog(ids)
          repository = new NativeRepository[IO](catalog, store, new BidsInterpreter[IO](store), ids)
          context = AccessContext(principal)
          dataset <- repository.create(context, custodian)
        yield RepositorySystem(catalog, store, repository, context, dataset)
      }
    yield PgFixture(config, database, system, root)

final class PostgresLifecycleSuite extends CatalogLifecycleSuite:
  // Generated oracle cases exercise many accepted releases and policy transitions.
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(5, "minutes")
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit =
    PostgresFixture.resource.use(fixture => run(fixture.system, fixture.root)).unsafeRunSync()
