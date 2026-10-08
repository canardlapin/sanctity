package sanctity.kernel

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.{Locale, UUID}

sealed trait DatasetTag
sealed trait ArtifactTag
sealed trait DraftTag
sealed trait CandidateTag
sealed trait ReleaseTag
sealed trait PrincipalTag
sealed trait CustodianTag
sealed trait CredentialTag
type CredentialId = Id[CredentialTag]

opaque type Id[A] = UUID
object Id:
  def apply[A](value: UUID): Id[A] = value
  def parse[A](text: String): Either[Problem, Id[A]] =
    scala.util.Try(UUID.fromString(text)).toEither.left.map(_ => Problem.InvalidIdentifier(text))
  extension [A](id: Id[A])
    def render: String = id.toString

type DatasetId = Id[DatasetTag]
type ArtifactId = Id[ArtifactTag]
type DraftId = Id[DraftTag]
type CandidateId = Id[CandidateTag]
type ReleaseId = Id[ReleaseTag]
type PrincipalId = Id[PrincipalTag]
type CustodianId = Id[CustodianTag]

opaque type RelPath = String
object RelPath:
  def from(value: String): Either[Problem, RelPath] =
    val segments = value.split("/", -1)
    if value.isEmpty || value.startsWith("/") || value.contains('\\') || value.contains(':') ||
        value.exists(_.isControl) || segments.exists(s => s.isEmpty || s == "." || s == ".." || s != s.trim) then
      Left(Problem.InvalidPath(value))
    else Right(value)

  extension (path: RelPath)
    def value: String = path
    def fileName: String = path.substring(path.lastIndexOf('/') + 1)
    def parent: Option[RelPath] =
      val split = path.lastIndexOf('/')
      Option.when(split >= 0)(path.substring(0, split))
    def collisionKey: String =
      Normalizer.normalize(path, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)

opaque type Digest = String
object Digest:
  def from(value: String): Either[Problem, Digest] =
    if value.length == 64 && value.forall(c => c >= '0' && c <= '9' || c >= 'a' && c <= 'f') then Right(value)
    else Left(Problem.InvalidIdentifier(value))

  def bytes(value: Array[Byte]): Digest =
    hex(MessageDigest.getInstance("SHA-256").digest(value))

  /** Versioned length framing prevents ambiguous concatenations. */
  def framed(domain: String, fields: Iterable[String]): Digest =
    val md = MessageDigest.getInstance("SHA-256")
    (Iterator.single(domain) ++ fields.iterator).foreach { field =>
      val bytes = field.getBytes(StandardCharsets.UTF_8)
      md.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.length.toLong).array())
      md.update(bytes)
    }
    hex(md.digest())

  private def hex(value: Array[Byte]): Digest =
    value.map(b => f"${b & 0xff}%02x").mkString

  extension (digest: Digest)
    def value: String = digest

final case class ManifestId(value: Digest)
final case class InterpretationId(value: Digest)
final case class AssessmentId(value: Digest)

opaque type IdempotencyKey = String
object IdempotencyKey:
  def from(value: String): Either[Problem, IdempotencyKey] =
    if value.nonEmpty && value.length <= 200 && !value.exists(_.isControl) then Right(value)
    else Left(Problem.InvalidIdentifier(value))
  extension (key: IdempotencyKey)
    def value: String = key

enum Problem:
  case InvalidIdentifier(value: String)
  case InvalidPath(value: String)
  case InvalidManifest(detail: String)
  case NotFound(kind: String, id: String)
  case Conflict(detail: String)
  case Denied
  case Rejected(findings: Vector[Finding])
  case Unsupported(detail: String)
  case LimitExceeded(detail: String)
  case Integrity(detail: String)
  case Operational(detail: String)

  def message: String = this match
    case InvalidIdentifier(value) => s"Invalid identifier: $value"
    case InvalidPath(value) => s"Unsafe or unsupported relative path: $value"
    case InvalidManifest(detail) => s"Invalid manifest: $detail"
    case NotFound(kind, id) => s"$kind not found: $id"
    case Conflict(detail) => s"Conflict: $detail"
    case Denied => "Access denied"
    case Rejected(findings) => findings.map(_.message).mkString("Admission rejected: ", "; ", "")
    case Unsupported(detail) => s"Unsupported: $detail"
    case LimitExceeded(detail) => s"Limit exceeded: $detail"
    case Integrity(detail) => s"Integrity failure: $detail"
    case Operational(detail) => s"Operation failed: $detail"
