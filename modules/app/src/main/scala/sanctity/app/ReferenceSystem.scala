package sanctity.app

import cats.effect.IO
import sanctity.application.*
import sanctity.bids.BidsInterpreter
import sanctity.kernel.*
import sanctity.objects.FileBlobStore

import java.nio.file.Path

final case class RepositorySystem(
    catalog: Catalog[IO],
    store: FileBlobStore[IO],
    repository: NativeRepository[IO],
    context: AccessContext,
    dataset: Dataset
)

object ReferenceSystem:
  def create(storage: Path): IO[RepositorySystem] =
    val ids = IdSource.random[IO]
    for
      principal <- ids.next[PrincipalTag]
      custodian <- ids.next[CustodianTag]
      catalog <- ReferenceCatalog.create[IO](ids)
      store <- FileBlobStore.open[IO](storage)
      repository = new NativeRepository[IO](catalog, store, new BidsInterpreter[IO](store), ids)
      ctx = AccessContext(principal)
      dataset <- repository.create(ctx, custodian)
    yield RepositorySystem(catalog, store, repository, ctx, dataset)
