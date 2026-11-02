package sanctity.application

import cats.effect.{Async, Clock}
import cats.syntax.all.*
import fs2.Stream
import sanctity.kernel.*

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.Try

/** A proposed authored-document replacement, including its exact original bytes. */
final case class ReferenceRepair(input: Entry.File, output: RelPath, text: String, rule: String):
  def bytes: Array[Byte] = text.getBytes(StandardCharsets.UTF_8)
  lazy val blob: BlobRef = BlobRef.from(Digest.bytes(bytes), bytes.length.toLong).toOption.get
  lazy val id: Digest = Digest.framed("sanctity.reference-repair.v1", Vector(
    input.path.value, input.artifact.render, input.blob.digest.value, input.blob.size.toString,
    output.value, blob.digest.value, blob.size.toString, rule))

/** Pure scientific adapters propose repairs; the application owns publication. */
trait ScientificChangeCompiler:
  def identity: String
  def documents(source: Release, edits: Vector[FileEdit]): Either[Problem, Vector[RelPath]]
  def repairs(source: Release, edits: Vector[FileEdit], documents: Map[RelPath, String]): Either[Problem, Vector[ReferenceRepair]]

final class ScientificChangePlan private (
    val source: ReleaseRef, val edits: Vector[FileEdit], val repairs: Vector[ReferenceRepair],
    val manifest: Manifest, val id: Digest, val receipt: String
)
object ScientificChangePlan:
  def from(source: Release, edits: Vector[FileEdit], repairs: Vector[ReferenceRepair], compiler: String): Either[Problem, ScientificChangePlan] =
    val ordered = edits.sortBy(_.source.value)
    val proposed = repairs.sortBy(_.output.value)
    for
      structural <- FileEdits.applyTo(source.manifest, ordered)
      _ <- Either.cond(compiler.nonEmpty && compiler.length <= 300 && !compiler.exists(_.isControl), (), Problem.Integrity("invalid change compiler identity"))
      _ <- Either.cond(proposed.size <= FileEdits.MaxEntries && proposed.map(_.output).distinct.size == proposed.size && proposed.forall { r =>
        source.manifest.byPath.get(r.input.path).contains(r.input) &&
          structural.byPath.get(r.output).contains(r.input.copy(path = r.output)) &&
          r.rule.matches("[a-z][a-z0-9.-]{0,99}")
      }, (), Problem.Integrity("repair does not match the source and structural destination"))
      _ <- Either.cond(proposed.forall(_.bytes.length <= 4 * 1024 * 1024) && proposed.map(_.bytes.length.toLong).sum <= 32L * 1024 * 1024,
        (), Problem.LimitExceeded("repairs allow 4 MiB per document and 32 MiB total"))
      manifest <- structural.change(proposed.map(r => Change.Put(r.output, r.input.artifact, r.blob)))
      rows = Vector("sanctity.scientific-change.v1", "compiler\t" + compiler,
        s"source\t${source.ref.dataset.render}\t${source.ref.release.render}",
        "content\t" + source.manifest.id.value.value, "interpretation\t" + source.interpretation.id.value.value,
        "output\t" + manifest.id.value.value) ++ ordered.map {
          case FileEdit.Move(a, b) => s"move\t${a.value}\t${b.value}"
          case FileEdit.Remove(p) => s"remove\t${p.value}"
        } ++ proposed.map(r => s"repair\t${r.id.value}\t${r.rule}\t${r.input.path.value}\t${r.input.blob.digest.value}\t${r.input.blob.size}\t${r.output.value}\t${r.blob.digest.value}\t${r.blob.size}")
      id = Digest.framed("sanctity.scientific-change-plan.v1", rows)
      receipt = (rows :+ ("plan\t" + id.value)).mkString("", "\n", "\n")
      _ <- Either.cond(receipt.getBytes(StandardCharsets.UTF_8).length <= 4 * 1024 * 1024, (), Problem.LimitExceeded("change receipt exceeds 4 MiB"))
    yield new ScientificChangePlan(source.ref, ordered, proposed, manifest, id, receipt)

/** Plan is read-only. Preparing requires the exact inspected plan ID, then uses
  * the same draft/assessment/acceptance path as import and metadata correction.
  */
final class ScientificChanges[F[_]: Async](
    catalog: Catalog[F], store: BlobStore[F], repository: NativeRepository[F], compiler: ScientificChangeCompiler
):
  private val maxDocument = 4L * 1024 * 1024

  def plan(ctx: AccessContext, base: ReleaseRef, edits: Vector[FileEdit]): F[Either[Problem, ScientificChangePlan]] = Checked.attempt {
    for
      _ <- catalog.check(ctx, base.dataset, Action.Propose).flatMap(Checked.lift[F, Unit])
      _ <- catalog.check(ctx, base.dataset, Action.ReadContent, Some(base.release)).flatMap(Checked.lift[F, Unit])
      release <- catalog.release(ctx, base).flatMap(Checked.lift[F, Release])
      paths <- Checked.lift[F, Vector[RelPath]](compiler.documents(release, edits))
      files <- Checked.lift[F, Vector[Entry.File]](paths.distinct.traverse(p => release.manifest.byPath.get(p)
        .collect { case f: Entry.File => f }.toRight(Problem.Integrity("change source document is missing"))))
      _ <- Checked.lift[F, Unit](Either.cond(files.size <= FileEdits.MaxEntries && files.forall(_.blob.size <= maxDocument) &&
        files.foldLeft(BigInt(0))((n, f) => n + f.blob.size) <= 32L * 1024 * 1024,
        (), Problem.LimitExceeded("changes allow 4 MiB per source document and 32 MiB total")))
      documents <- files.traverse { file =>
        store.read(file.blob).chunkN(64 * 1024).evalTap(_ => catalog.check(ctx, base.dataset, Action.ReadContent, Some(base.release))
          .flatMap(Checked.lift[F, Unit])).unchunks.take(maxDocument + 1).compile.to(Array).flatMap { bytes =>
          Checked.lift[F, (RelPath, String)](for
            _ <- Either.cond(bytes.length.toLong == file.blob.size && Digest.bytes(bytes) == file.blob.digest, (),
              Problem.Integrity("change document bytes differ from the source manifest"))
            text <- Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString)
              .toEither.leftMap(_ => Problem.Unsupported(s"change document is not UTF-8: ${file.path.value}"))
          yield file.path -> text)
        }
      }.map(_.toMap)
      repairs <- Checked.lift[F, Vector[ReferenceRepair]](compiler.repairs(release, edits, documents))
      result <- Checked.lift[F, ScientificChangePlan](ScientificChangePlan.from(release, edits, repairs, compiler.identity))
    yield result
  }

  def prepare(ctx: AccessContext, base: ReleaseRef, edits: Vector[FileEdit], expectedPlan: Digest,
      reason: String): F[Either[Problem, Preview]] = Checked.attempt {
    for
      _ <- Checked.lift[F, Unit](Either.cond(reason.trim.nonEmpty && reason.length <= 4096, (), Problem.Unsupported("a change reason of at most 4096 characters is required")))
      plan <- plan(ctx, base, edits).flatMap(Checked.lift[F, ScientificChangePlan])
      _ <- Checked.lift[F, Unit](Either.cond(plan.id == expectedPlan, (), Problem.Conflict("the inspected change plan differs; inspect a fresh plan")))
      dataset <- catalog.dataset(ctx, base.dataset).flatMap(Checked.lift[F, Dataset])
      _ <- Checked.lift[F, Unit](Either.cond(dataset.head.contains(base.release), (), Problem.Conflict("the dataset head changed; plan against the new release")))
      authorize = catalog.check(ctx, base.dataset, Action.ReadContent, Some(base.release)).flatMap(Checked.lift[F, Unit]) *>
        catalog.check(ctx, base.dataset, Action.Propose).flatMap(Checked.lift[F, Unit])
      _ <- plan.repairs.traverse_ { repair =>
        store.put(CurrentAuthority.bytes(Stream.emits(repair.bytes).covary[F], authorize)).flatMap(blob => Checked.lift[F, Unit](
          Either.cond(blob == repair.blob, (), Problem.Integrity("stored repair differs from the inspected plan"))))
      }
      evidence <- store.put(CurrentAuthority.bytes(Stream.emits(plan.receipt.getBytes(StandardCharsets.UTF_8)).covary[F], authorize))
      now <- Clock[F].realTimeInstant
      draft <- catalog.draft(ctx, base.dataset, Some(base.release), plan.manifest,
        Activity(ActivityKind.ScientificChange, ctx.principal, reason, Some(compiler.identity), now, evidence = Some(evidence),
          change = Some(ChangeEvidence(plan.id, base, plan.manifest.id, evidence))))
        .flatMap(Checked.lift[F, Draft])
      preview <- repository.prepare(ctx, draft.id).flatMap(Checked.lift[F, Preview])
    yield preview
  }
