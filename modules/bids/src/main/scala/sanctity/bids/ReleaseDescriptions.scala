package sanctity.bids

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.JawnParser
import sanctity.application.{BlobStore, ReleaseDescriber}
import sanctity.kernel.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

/** The catalog record of a release, read from the descriptor its kind defines (SEE2).
  *
  * An archive's record comes from `archive_description.json`, which its interpretation already holds. A BIDS
  * dataset's record comes from `dataset_description.json`, with the root README as its description and the data
  * types found in its coverage. Only those small root files are read. Whatever cannot be read is left out.
  */
final class ReleaseDescriptions[F[_]](store: BlobStore[F])(using F: Async[F]) extends ReleaseDescriber[F]:
  import ReleaseDescriptions.*
  private val parser = new JawnParser(maxValueSize = Some(DescriptorLimit.toInt), allowDuplicateKeys = false)

  def describe(manifest: Manifest, interpretation: Interpretation): F[CatalogRecord] =
    if interpretation.profile.name == ArchiveInterpreter.Profile then F.pure(archive(interpretation))
    else bids(manifest, interpretation).handleError(_ => CatalogRecord.empty(CatalogRecord.Bids))

  private def root(manifest: Manifest, name: String): Option[Entry.File] =
    manifest.files.find(_.path.value == name)
  private def bytes(file: Entry.File, limit: Long): F[Option[Array[Byte]]] =
    store.read(file.blob).take(limit).compile.to(Array).map(Option(_)).handleError(_ => None)

  private def bids(manifest: Manifest, interpretation: Interpretation): F[CatalogRecord] =
    for
      descriptor <- root(manifest, DatasetInterpreter.BidsDescriptor).filter(_.blob.size <= DescriptorLimit).flatTraverse(bytes(_, DescriptorLimit))
      fields = descriptor.flatMap(b => parser.parseByteBuffer(ByteBuffer.wrap(b)).toOption).flatMap(_.asObject)
      readme = Readmes.flatMap(root(manifest, _)).headOption
      // A README is free text of any size; only its beginning describes the dataset in a listing.
      text <- readme.flatTraverse(bytes(_, ReadmeLimit))
      dataTypes = interpretation.levels.toVector.flatMap(_.summary.map(_._1)).distinct.filterNot(NotDataTypes)
    yield CatalogRecord.from(CatalogRecord.Bids,
      title = fields.flatMap(_("Name")).flatMap(_.asString),
      description = text.map(b => new String(b, UTF_8)),
      descriptionFrom = readme.map(_.path),
      creators = strings(fields.flatMap(_("Authors"))),
      keywords = strings(fields.flatMap(_("Keywords"))),
      dataTypes = dataTypes,
      license = fields.flatMap(_("License")).flatMap(_.asString),
      identifiers = fields.flatMap(_("DatasetDOI")).flatMap(_.asString).toVector)

  private def strings(value: Option[Json]): Vector[String] = value.flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString)

  private def archive(interpretation: Interpretation): CatalogRecord =
    val descriptor = interpretation.artifacts.find(_.path.value == ArchiveInterpreter.Descriptor)
    val fields = descriptor.map(_.fields.view.mapValues(_.value).toMap).getOrElse(Map.empty)
    def text(name: String): Option[String] = fields.get(name).collect { case MetadataValue.Text(s) => s }
    def list(name: String): Vector[String] = fields.get(name).collect { case MetadataValue.Array(values) => values.collect { case MetadataValue.Text(s) => s } }
      .getOrElse(Vector.empty)
    CatalogRecord.from(CatalogRecord.Archive, text("Name"), text("Description"), descriptor.map(_.path).filter(_ => text("Description").nonEmpty),
      list("Authors"), list("Keywords"), list("DataTypes"), text("License"), text("DatasetDOI").toVector)

object ReleaseDescriptions:
  val DescriptorLimit: Long = 4L * 1024 * 1024
  val ReadmeLimit: Long = 64L * 1024
  /** In the order BIDS prefers them. */
  val Readmes: Vector[String] = Vector("README", "README.md", "README.rst", "README.txt")
  /** Coverage groups that name where a file sits, not a kind of data. */
  private val NotDataTypes: Set[String] = Set("dataset", "other")
