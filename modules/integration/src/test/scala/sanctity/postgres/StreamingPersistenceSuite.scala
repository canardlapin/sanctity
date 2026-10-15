package sanctity.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import sanctity.app.{CatalogStreamingViewSuite, RepositorySystem}
import java.nio.file.Path

final class StreamingPersistenceSuite extends CatalogStreamingViewSuite:
  protected def fixture(run: (RepositorySystem, Path) => IO[Unit]): Unit =
    PostgresFixture.resource.use(f => run(f.system, f.root)).unsafeRunSync()
