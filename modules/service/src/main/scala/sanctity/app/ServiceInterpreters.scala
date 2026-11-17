package sanctity.app

import cats.effect.IO
import sanctity.application.BlobStore
import sanctity.bids.{DatasetInterpreter, DerivativeProfile}
import sanctity.processing.{TemporalDerivativeInterpreter, TemporalStatistics}

private[sanctity] object ServiceInterpreters:
  def apply(store: BlobStore[IO]): DatasetInterpreter[IO] =
    new DatasetInterpreter(store, additional = Vector(DerivativeProfile("SanctityTemporalStatistics",
      Set(TemporalStatistics.Profile, TemporalStatistics.MultiEchoProfile, TemporalStatistics.CalibratedProfile),
      TemporalStatistics.Producer, new TemporalDerivativeInterpreter(store))))
