package sanctity.postgres

import cats.effect.Async
import cats.syntax.all.*
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*
import doobie.postgres.implicits.*
import sanctity.application.{Authenticator, IdSource}
import sanctity.kernel.*
import io.circe.Json
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.sql.Timestamp
import java.util.{Base64, UUID}

/** Secret is disclosed explicitly once by the operator CLI; diagnostic rendering is always redacted. */
final class IssuedCredential private[postgres](
    val id: CredentialId, val principal: PrincipalId, val expiresAt: java.time.Instant, val secret: String,
    val durability: PolicyDurability
):
  override def toString: String = s"IssuedCredential(${id.render},secret=<redacted>)"

/** Issuance/revocation are operator capabilities, deliberately absent from the researcher API.
  * Tokens authenticate a principal; every action still needs its current catalog authority.
  */
final class PostgresCredentials[F[_]] private[postgres](xa: Transactor[F], ids: IdSource[F])(using F: Async[F])
    extends Authenticator[F]:
  import Records.{lift, uuid}
  import JsonCodecs.given

  def issue(principal: PrincipalId, label: String, lifetimeSeconds: Int): F[Either[Problem, IssuedCredential]] =
    issueInternal(None,principal,label,lifetimeSeconds)

  /** Caller retains this ID across timeouts. A committed ID is never issued twice;
    * the bearer is deliberately unrecoverable and requires explicit revoke/reissue.
    */
  def issueOnce(id: CredentialId, principal: PrincipalId, label: String, lifetimeSeconds: Int): F[Either[Problem, IssuedCredential]] =
    issueInternal(Some(id),principal,label,lifetimeSeconds)

  private def issueInternal(operation: Option[CredentialId], principal: PrincipalId, label: String,
      lifetimeSeconds: Int): F[Either[Problem, IssuedCredential]] =
    if label.trim.isEmpty || label.length > 100 || label.exists(_.isControl) ||
        lifetimeSeconds < 1 || lifetimeSeconds > 30 * 24 * 3600 then
      F.pure(Left(Problem.Unsupported("credential label must be 1–100 characters; lifetime must be 1 second–30 days")))
    else
      for
        id <- operation.fold(ids.next[CredentialTag])(_.pure[F])
        secret <- F.delay {
          val bytes = new Array[Byte](32)
          new SecureRandom().nextBytes(bytes)
          "sct_" + Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
        }
        digest = Digest.bytes(secret.getBytes(StandardCharsets.US_ASCII)).value
        result <- OperationsSql.run(xa) {
          for
            _ <- OperationsSql.active
            enabled <- OrganizationSql.guard(true)
            _ <- PolicySql.requireOperator
            mode <- sql"SELECT durability_mode FROM organization_policy WHERE singleton".query[String].unique
            _ <- lift(Either.cond(mode != "independent" || operation.isDefined, (),
              Problem.Unsupported("independent credential issuance requires a retained operation ID; use issueOnce")))
            previous <- sql"SELECT EXISTS(SELECT 1 FROM api_credential WHERE id=${uuid(id)})".query[Boolean].unique
            _ <- lift(Either.cond(!previous, (), Problem.Conflict("credential operation already issued; secret cannot be replayed; revoke this ID before issuing another")))
            exists <- sql"SELECT EXISTS(SELECT 1 FROM principal WHERE id=${uuid(principal)} AND active)".query[Boolean].unique
            _ <- lift(Either.cond(exists, (), Problem.Denied))
            _ <- CredentialSql.check(AccessContext(principal))
            at <- OrganizationSql.now
            expiry = at.plusSeconds(lifetimeSeconds.toLong)
            actor <- if enabled then PolicySql.operator else principal.pure[ConnectionIO]
            _ <- PolicySql.commit(actor,"credential:"+id.render,id.render,"CredentialIssued",Json.obj(
              "before" -> Json.Null,"after" -> Json.obj("id" -> id.asJson,"principal" -> principal.asJson,
                "label" -> Json.fromString(label),"issuedAt" -> at.asJson,"expiresAt" -> expiry.asJson,"revokedAt" -> Json.Null))) {
              sql"""INSERT INTO api_credential(id,principal_id,token_digest,label,issued_at,expires_at)
                VALUES(${uuid(id)},${uuid(principal)},$digest,$label,${Timestamp.from(at)},${Timestamp.from(expiry)})""".update.run *>
              sql"INSERT INTO credential_audit(credential_id,action) VALUES(${uuid(id)},'Issued')".update.run.void
            }
          yield new IssuedCredential(id, principal, expiry, secret,
            if mode == "independent" then PolicyDurability.Durable else PolicyDurability.LocalOnly)
        }
      yield result

  def revoke(id: CredentialId): F[Either[Problem, Unit]] = revokeAcknowledged(id).map(_.void)

  def revokeAcknowledged(id: CredentialId): F[Either[Problem, PolicyDurability]] = OperationsSql.run(xa) {
    for
      _ <- OperationsSql.active
      enabled <- OrganizationSql.guard(true)
      _ <- PolicySql.requireOperator
      row <- sql"SELECT principal_id,revoked_at FROM api_credential WHERE id=${uuid(id)}".query[(UUID,Option[Timestamp])].option
      found <- lift(row.toRight(Problem.Denied))
      (principal,revoked) = found
      actor <- if enabled then PolicySql.operator else Id[PrincipalTag](principal).pure[ConnectionIO]
      at <- OrganizationSql.now
      _ <- if revoked.nonEmpty then ().pure[ConnectionIO] else
        PolicySql.commit(actor,"credential:"+id.render,"revoke:"+id.render,"CredentialRevoked",Json.obj(
          "id" -> id.asJson,"principal" -> Json.fromString(principal.toString),"before" -> Json.Null,"revokedAt" -> at.asJson)) {
          sql"UPDATE api_credential SET revoked_at=${Timestamp.from(at)} WHERE id=${uuid(id)}".update.run *>
          sql"INSERT INTO credential_audit(credential_id,action) VALUES(${uuid(id)},'Revoked')".update.run.void
        }
      mode <- sql"SELECT durability_mode FROM organization_policy WHERE singleton".query[String].unique
    yield if mode == "independent" then PolicyDurability.Durable else PolicyDurability.LocalOnly
  }

  def authenticate(bearer: String): F[Either[Problem, AccessContext]] =
    if !bearer.matches("sct_[A-Za-z0-9_-]{43}") then F.pure(Left(Problem.Denied))
    else
      val digest = Digest.bytes(bearer.getBytes(StandardCharsets.US_ASCII)).value
      OperationsSql.run(xa) {
        for
          _ <- OperationsSql.active
          _ <- OrganizationSql.guard(false)
          row <- sql"""SELECT id,principal_id FROM api_credential
            WHERE token_digest=$digest AND revoked_at IS NULL AND expires_at > clock_timestamp()
              AND EXISTS(SELECT 1 FROM principal p WHERE p.id=principal_id AND p.active)"""
            .query[(UUID, UUID)].option
          ctx <- lift(row.map((id, p) => AccessContext(Id[PrincipalTag](p), Some(Id[CredentialTag](id)))).toRight(Problem.Denied))
          _ <- CredentialSql.check(ctx)
        yield ctx
      }

private[postgres] object CredentialSql:
  /** FOR SHARE serializes a transaction with revocation; cached contexts cannot outlive credentials. */
  def check(ctx: AccessContext): ConnectionIO[Unit] =
    OrganizationSql.guard(false).flatMap { enabled =>
      if !enabled then ().pure[ConnectionIO] else
        import OrganizationCodecs.given
        import OrganizationAuthority.*
        for
          row <- sql"SELECT active,created_at,authority_eligibility::text FROM principal WHERE id=${Records.uuid(ctx.principal)}"
            .query[(Boolean,Timestamp,String)].option
          current <- row.traverse { (active,created,payload) => for
            e <- Records.lift(JsonCodecs.decode[Eligibility](payload))
            now <- OrganizationSql.now
          yield active && !now.isBefore(created.toInstant) && (e match
            case Eligibility.Local => true
            case Eligibility.Guest(g) => g.verifiedBinding && g.approved && g.active && now.isBefore(g.expiresAt)) }
          _ <- Records.lift(Either.cond(current.contains(true), (), Problem.Denied))
        yield ()
    } *> ctx.credential.traverse_ { id =>
      sql"""SELECT principal_id FROM api_credential WHERE id=${Records.uuid(id)}
        AND revoked_at IS NULL AND expires_at > clock_timestamp() FOR SHARE""".query[UUID].option
        .flatMap(p => Records.lift(Either.cond(p.contains(Records.uuid(ctx.principal)), (), Problem.Denied)))
    }
