package sanctity.processing

import bids4s.{NiftiByteOrder, NiftiHeaderFacts, NiftiScaling}
import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.{BlobStore, Checked, Failure}
import sanctity.kernel.*
import java.io.{BufferedInputStream, DataInputStream, EOFException, IOException, OutputStream}
import java.nio.{ByteBuffer, ByteOrder}
import java.util.zip.GZIPInputStream

final case class PayloadObservation(header: NiftiHeaderFacts, scaling: NiftiScaling,
    voxels: Long, minimum: Double, maximum: Double, mean: Double):
  def facts: Map[String, MetadataValue] = NiftiPayload.geometry(header) ++ Map(
    "dimensions" -> MetadataValue.Array(header.dimensions.map(n => MetadataValue.Number(BigDecimal(n)))),
    "datatype" -> MetadataValue.Number(header.datatype), "logicalBytes" -> MetadataValue.Number(BigDecimal(header.minimumFileBytes)),
    "voxels" -> MetadataValue.Number(BigDecimal(voxels)), "slope" -> NiftiPayload.number(scaling.slope),
    "intercept" -> NiftiPayload.number(scaling.intercept), "minimum" -> NiftiPayload.number(minimum),
    "maximum" -> NiftiPayload.number(maximum), "mean" -> NiftiPayload.number(mean), "complete" -> MetadataValue.Flag(true))

/** Full scalar-array assessment with constant-sized buffers. Limits are part of this versioned profile. */
object NiftiPayload:
  val Rule = "sanctity.nifti-payload.v1"
  val MaxBytes = 8L * 1024 * 1024 * 1024
  val MaxSpatialVoxels = 16L * 1024 * 1024
  val SupportedTypes: Set[Int] = Set(2, 4, 8, 16, 64, 256, 512, 768)
  def number(n: Double): MetadataValue =
    if n.isFinite then MetadataValue.Number(BigDecimal(n.toString)) else MetadataValue.Text(n.toString)
  private def numbers(v: Vector[Double]): MetadataValue = MetadataValue.Array(v.map(number))
  def geometry(h: NiftiHeaderFacts): Map[String, MetadataValue] = Map(
    "spatialDimensions" -> MetadataValue.Array(h.dimensions.take(3).map(n => MetadataValue.Number(BigDecimal(n)))),
    "spatialSpacing" -> numbers(h.pixelDimensions.slice(1, 4)), "spatialUnits" -> MetadataValue.Number(h.unitsCode & 7),
    "qformCode" -> MetadataValue.Number(h.qformCode), "sformCode" -> MetadataValue.Number(h.sformCode)) ++
    (if h.qformCode > 0 then Map("qfac" -> number(h.pixelDimensions.head), "quaternion" -> numbers(h.quaternion), "qoffset" -> numbers(h.qoffset)) else Map.empty) ++
    (if h.sformCode > 0 then Map("sformRows" -> MetadataValue.Array(h.sformRows.map(numbers))) else Map.empty)

final class NiftiPayload[F[_]: Async](store: BlobStore[F]):
  private val F = Async[F]
  def inspect(file: Entry.File): F[Either[Problem, PayloadObservation]] = Checked.attempt {
    Checked.lift[F, Unit](Either.cond(file.blob.size <= NiftiPayload.MaxBytes, (), Problem.LimitExceeded("image exceeds 8 GiB encoded budget"))) *>
      fs2.io.toInputStreamResource(store.read(file.blob)).use { raw =>
        F.interruptibleMany {
          try
            val decoded = if file.path.value.endsWith(".gz") then new GZIPInputStream(raw, 65536) else raw
            val input = new DataInputStream(new BufferedInputStream(decoded, 65536))
            def reject(message: String): Nothing = throw Failure(Problem.InvalidManifest(message))
            val prefix = input.readNBytes(4)
            val size = NiftiHeaderFacts.headerSize(prefix).fold(e => reject(e.message), identity)
            val bytes = prefix ++ input.readNBytes(size - 4)
            val h = NiftiHeaderFacts.parse(bytes).fold(e => reject(e.message), identity)
            val scaling = NiftiScaling.parse(bytes).fold(e => reject(e.message), identity)
            if !NiftiPayload.SupportedTypes(h.datatype) then reject("payload profile excludes 64-bit integers and non-scalar datatypes")
            if h.dimensions.size < 3 || h.dimensions.size > 4 || h.minimumFileBytes > NiftiPayload.MaxBytes ||
                h.dimensions.take(3).product > NiftiPayload.MaxSpatialVoxels || h.dataOffset > 1024 * 1024 then
              throw Failure(Problem.LimitExceeded("payload profile requires rank 3/4, at most 16M spatial voxels, 8 GiB logical bytes and 1 MiB offset"))
            input.skipNBytes(h.dataOffset - size)
            val count = h.dimensions.product
            val width = h.bitsPerVoxel / 8
            val chunk = new Array[Byte](65536)
            val buffer = ByteBuffer.wrap(chunk).order(if h.byteOrder == NiftiByteOrder.LittleEndian then ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            var consumed = 0L
            var minimum = Double.PositiveInfinity
            var maximum = Double.NegativeInfinity
            var mean = 0.0
            while consumed < count do
              val n = math.min(count - consumed, chunk.length / width).toInt
              input.readFully(chunk, 0, n * width)
              buffer.clear()
              var i = 0
              while i < n do
                val rawValue = h.datatype match
                  case 2 => (buffer.get() & 255).toDouble
                  case 256 => buffer.get().toDouble
                  case 4 => buffer.getShort().toDouble
                  case 512 => (buffer.getShort() & 65535).toDouble
                  case 8 => buffer.getInt().toDouble
                  case 768 => (buffer.getInt().toLong & 0xffffffffL).toDouble
                  case 16 => buffer.getFloat().toDouble
                  case 64 => buffer.getDouble()
                  case _ => reject("unsupported datatype")
                val value = rawValue * scaling.slope + scaling.intercept
                if !value.isFinite || math.abs(value) > 1e90 then reject("scaled voxels must be finite with magnitude at most 1e90")
                consumed += 1
                minimum = math.min(minimum, value)
                maximum = math.max(maximum, value)
                mean += (value - mean) / consumed.toDouble
                i += 1
            // Read through the compressed trailer and EOF, including concatenated-member checks.
            if input.read() != -1 then reject("unexpected bytes after the declared voxel array")
            val _ = raw.transferTo(OutputStream.nullOutputStream()) // complete the managed blob digest check
            PayloadObservation(h, scaling, count, minimum, maximum, mean)
          catch
            case _: EOFException => throw Failure(Problem.InvalidManifest("truncated NIfTI payload or gzip stream"))
            case _: IOException => throw Failure(Problem.InvalidManifest("invalid NIfTI payload or gzip integrity"))
        }
      }
  }
