package sanctity.processing

import cats.effect.Async
import sanctity.application.{BlobStore, Catalog}

/** Compatibility entry point for the original header-QC recipe. */
final class LocalQcRuns[F[_]: Async](catalog: Catalog[F], store: BlobStore[F], producer: MriQcProducer[F])
    extends LocalProcessingRuns[F](catalog, store, producer)
