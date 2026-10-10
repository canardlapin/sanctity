package sanctity.bids

import bids4s.{BValues, BVectors, DwiConsistency, DwiIssue, DwiIssueCode, GradientText, NiftiHeaderFacts}
import cats.effect.Async
import cats.syntax.all.*
import sanctity.application.{BlobStore, Checked}
import sanctity.kernel.*

import java.io.{ByteArrayInputStream, IOException}
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.util.zip.GZIPInputStream
import scala.util.Try

/** Prefix inspection is bounded independently of declared dimensions and compressed image size.
  * Full blob identity is verified by ordinary acceptance. This reader does not validate gzip
  * payload CRC, affine geometry or voxel contents beyond the inspected prefix.
  */
final class DwiContentChecks[F[_]](store: BlobStore[F])(using F: Async[F]):
  private val textLimit = GradientText.MaxCharacters.toLong
  private val totalTextLimit = 32L * 1024 * 1024
  private val compressedPrefixLimit = 64 * 1024
  private val rule = DwiConsistency.Version + "+" + NiftiHeaderFacts.Version + "+sanctity-prefix-v1"

  def assess(manifest: Manifest, interpretation: Interpretation): F[Vector[ContentCheck]] =
    val groups = interpretation.relationships.toVector.flatMap(_.relationships).filter(_.kind == "bids.dwi-gradients")
    val gradientPaths = groups.flatMap(_.to).distinct
    val files = manifest.files.map(f => f.path -> f).toMap
    val gradients = gradientPaths.flatMap(files.get)
    for
      _ <- Checked.lift[F, Unit](Either.cond(gradients.foldLeft(BigInt(0))((n, f) => n + f.blob.size) <= totalTextLimit,
        (), Problem.LimitExceeded("DWI profile allows 32 MiB of selected gradient documents")))
      texts <- gradients.traverse(f => readText(f).map(f.path -> _)).map(_.toMap)
      checks <- groups.traverse { group =>
        val image = files(group.from)
        val bval = group.to.find(_.fileName.endsWith(".bval")).flatMap(files.get)
        val bvec = group.to.find(_.fileName.endsWith(".bvec")).flatMap(files.get)
        for
          pair <- Checked.lift[F, (Entry.File, Entry.File)]((bval, bvec).mapN((_, _))
            .toRight(Problem.Integrity("DWI relationship does not contain both gradient roles")))
          (valuesFile, vectorsFile) = pair
          header <- readHeader(image)
          values = texts(valuesFile.path).flatMap(text => BValues.parse(text).leftMap(_.map(issueFinding(valuesFile.path, _))))
          vectors = texts(vectorsFile.path).flatMap(text => BVectors.parse(text).leftMap(_.map(issueFinding(vectorsFile.path, _))))
          errors = header.left.toOption.toVector ++ values.left.toOption.toVector.flatten ++ vectors.left.toOption.toVector.flatten
          consistency = (header.toOption, values.toOption, vectors.toOption).mapN { (h, b, v) =>
            DwiConsistency.check(h, b, v).map { issue =>
              issueFinding(if issue.input == "bval" then valuesFile.path else if issue.input == "bvec" then vectorsFile.path else image.path, issue)
            }
          }.getOrElse(Vector.empty)
          facts = observations(header.toOption, values.toOption, vectors.toOption, image.path.fileName.endsWith(".gz"))
          check <- Checked.lift[F, ContentCheck](ContentCheck.from(rule, image.path,
            Vector(CheckInput("image", image.path, image.blob), CheckInput("bval", valuesFile.path, valuesFile.blob),
              CheckInput("bvec", vectorsFile.path, vectorsFile.blob)), facts, errors ++ consistency))
        yield check
      }
    yield checks

  private def readText(file: Entry.File): F[Either[Vector[Finding], String]] =
    if file.blob.size > textLimit then F.pure(Left(Vector(Finding("DwiLimitExceeded", Severity.Error, Some(file.path), "gradient document exceeds 4 MiB"))))
    else store.read(file.blob).compile.to(Array).map { bytes =>
      Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString)
        .toEither.leftMap(_ => Vector(Finding("DwiEncoding", Severity.Error, Some(file.path), "gradient document is not valid UTF-8")))
    }

  private def readHeader(file: Entry.File): F[Either[Finding, NiftiHeaderFacts]] =
    val gzip = file.path.fileName.endsWith(".nii.gz")
    val limit = if gzip then compressedPrefixLimit else NiftiHeaderFacts.MaxHeaderBytes
    store.read(file.blob).take(limit.toLong).compile.to(Array).flatMap { prefix =>
      F.blocking {
        val decoded = if !gzip then Right(prefix) else gzipHeader(prefix, file.blob.size > prefix.length)
        decoded.flatMap(NiftiHeaderFacts.parse).flatMap { header =>
          if !gzip && file.blob.size < header.minimumFileBytes then
            Left(DwiIssue(DwiIssueCode.Header, "image", "uncompressed file is shorter than the header's declared array"))
          else Right(header)
        }.leftMap(issueFinding(file.path, _))
      }
    }

  private final class PrefixLimit extends IOException
  private def gzipHeader(prefix: Array[Byte], limited: Boolean): Either[DwiIssue, Array[Byte]] =
    val input = new ByteArrayInputStream(prefix):
      override def read(): Int =
        if available() == 0 && limited then throw new PrefixLimit else super.read()
      override def read(b: Array[Byte], offset: Int, length: Int): Int =
        if length > 0 && available() == 0 && limited then throw new PrefixLimit else super.read(b, offset, length)
    try
      val stream = new GZIPInputStream(input, 512)
      try
        val initial = stream.readNBytes(4)
        NiftiHeaderFacts.headerSize(initial).map(size => initial ++ stream.readNBytes(size - initial.length))
      finally stream.close()
    catch
      case _: PrefixLimit => Left(DwiIssue(DwiIssueCode.LimitExceeded, "image", "gzip header requires more than 64 KiB compressed input"))
      case _: IOException => Left(DwiIssue(DwiIssueCode.Header, "image", "invalid or truncated gzip header prefix"))

  private def issueFinding(path: RelPath, issue: DwiIssue): Finding =
    val location = issue.row.fold("")(r => s" row $r") + issue.column.fold("")(c => s" column $c")
    Finding("Dwi" + issue.code.toString, Severity.Error, Some(path), issue.message + location)

  private def observations(header: Option[NiftiHeaderFacts], bvals: Option[BValues], bvecs: Option[BVectors], gzip: Boolean): Map[String, MetadataValue] =
    def n(value: Double): MetadataValue = MetadataValue.Number(BigDecimal(value.toString))
    val config = Map(
      "lowBMaximum" -> n(DwiConsistency.LowBMaximum),
      "unitNormTolerance" -> n(DwiConsistency.UnitNormTolerance),
      "zeroNormTolerance" -> n(DwiConsistency.ZeroNormTolerance),
      "gradientConvention" -> MetadataValue.Text("FSL image-axis components; no reorientation"),
      "compressedPrefixLimit" -> n(compressedPrefixLimit.toDouble),
      "headerOnly" -> MetadataValue.Flag(true),
      "gzip" -> MetadataValue.Flag(gzip))
    config ++ header.toVector.flatMap { h => Vector(
      "niftiVersion" -> n(h.version.toDouble), "byteOrder" -> MetadataValue.Text(h.byteOrder.toString),
      "dimensions" -> MetadataValue.Array(h.dimensions.map(d => MetadataValue.Number(BigDecimal(d)))),
      "datatype" -> n(h.datatype.toDouble), "bitsPerVoxel" -> n(h.bitsPerVoxel.toDouble),
      "dataOffset" -> MetadataValue.Number(BigDecimal(h.dataOffset)),
      "minimumFileBytes" -> MetadataValue.Number(BigDecimal(h.minimumFileBytes))) ++
      Option.when(Set(3, 4)(h.dimensions.size))("volumeCount" -> MetadataValue.Number(BigDecimal(h.dimensions.lift(3).getOrElse(1L)))).toVector
    } ++ bvals.toVector.map(v => "bvalCount" -> n(v.values.size.toDouble)) ++
      bvecs.toVector.map(v => "bvecCount" -> n(v.values.size.toDouble))
