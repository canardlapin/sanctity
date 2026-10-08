package sanctity.postgres

import cats.effect.Async
import cats.syntax.all.*
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*
import doobie.postgres.implicits.*
import sanctity.application.{Authenticator, IdSource}
import sanctity.kernel.*
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.sql.Timestamp
import java.util.{Base64, UUID}

/** Secret is disclosed explicitly once by the operator CLI; diagnostic rendering is always redacted. */
final class IssuedCredential private[postgres](
    val id: CredentialId, val principal: PrincipalId, val expiresAt: java.time.Instant, val secret: String
):
  override def toString: String = s"IssuedCredential(${id.render},secret=<redacted>)"

/** Issuance/revocation are operator capabilities, deliberately absent from the researcher API.
  * Tokens authenticate a principal; every action still needs its current catalog authority.
  */
final class PostgresCredentials[F[_]] private[postgres](xa: Transactor[F], ids: IdSource[F])(using F: Async[F])
    extends Authenticator[F]:
  import Records.{lift, uuid}

  def issue(principal: PrincipalId, label: String, lifetimeSeconds: Int): F[Either[Problem, IssuedCredential]] =
    if label.trim.isEmpty || label.length > 100 || label.exists(_.isControl) ||
        lifetimeSeconds < 1 || lifetimeSeconds > 30 * 24 * 3600 then
      F.pure(Left(Problem.Unsupported("credential label must be 1–100 characters; lifetime must be 1 second–30 days")))
    else
      for
        id <- ids.next[CredentialTag]
        secret <- F.delay {
          val bytes = new Array[Byte](32)
          new SecureRandom().nextBytes(bytes)
          "sct_" + Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
        }
        digest = Digest.bytes(secret.getBytes(StandardCharsets.US_ASCII)).value
        result <- OperationsSql.run(xa) {
          for
            _ <- OperationsSql.active
            exists <- sql"SELECT EXISTS(SELECT 1 FROM principal WHERE id=${uuid(principal)})".query[Boolean].unique
            _ <- lift(Either.cond(exists, (), Problem.Denied))
            expiry <- sql"""INSERT INTO api_credential(id,principal_id,token_digest,label,expires_at)
              VALUES(${uuid(id)},${uuid(principal)},$digest,$label,clock_timestamp()+$lifetimeSeconds*interval '1 second')
              RETURNING expires_at""".query[Timestamp].unique
            _ <- sql"INSERT INTO credential_audit(credential_id,action) VALUES(${uuid(id)},'Issued')".update.run
          yield new IssuedCredential(id, principal, expiry.toInstant, secret)
        }
      yield result

  def revoke(id: CredentialId): F[Either[Problem, Unit]] = OperationsSql.run(xa) {
    for
      _ <- OperationsSql.active
      changed <- sql"""UPDATE api_credential SET revoked_at=COALESCE(revoked_at,clock_timestamp())
        WHERE id=${uuid(id)}""".update.run
      _ <- lift(Either.cond(changed == 1, (), Problem.Denied))
      _ <- sql"INSERT INTO credential_audit(credential_id,action) VALUES(${uuid(id)},'Revoked')".update.run
    yield ()
  }

  def authenticate(bearer: String): F[Either[Problem, AccessContext]] =
    if !bearer.matches("sct_[A-Za-z0-9_-]{43}") then F.pure(Left(Problem.Denied))
    else
      val digest = Digest.bytes(bearer.getBytes(StandardCharsets.US_ASCII)).value
      OperationsSql.run(xa) {
        for
          _ <- OperationsSql.active
          row <- sql"""SELECT id,principal_id FROM api_credential
            WHERE token_digest=$digest AND revoked_at IS NULL AND expires_at > clock_timestamp()"""
            .query[(UUID, UUID)].option
          ctx <- lift(row.map((id, p) => AccessContext(Id[PrincipalTag](p), Some(Id[CredentialTag](id)))).toRight(Problem.Denied))
        yield ctx
      }

private[postgres] object CredentialSql:
  /** FOR SHARE serializes a transaction with revocation; cached contexts cannot outlive credentials. */
  def check(ctx: AccessContext): ConnectionIO[Unit] =
    ctx.credential.traverse_ { id =>
      sql"""SELECT principal_id FROM api_credential WHERE id=${Records.uuid(id)}
        AND revoked_at IS NULL AND expires_at > clock_timestamp() FOR SHARE""".query[UUID].option
        .flatMap(p => Records.lift(Either.cond(p.contains(Records.uuid(ctx.principal)), (), Problem.Denied)))
    }
