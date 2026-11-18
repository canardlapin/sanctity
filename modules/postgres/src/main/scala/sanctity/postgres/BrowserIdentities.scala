package sanctity.postgres

import cats.effect.Async
import cats.syntax.all.*
import doobie.Transactor
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.{Base64, UUID}
import sanctity.kernel.*

/** Only an operator may bind an issuer's opaque subject. Display names are search labels. */
final class BrowserIdentities[F[_]] private[postgres](xa: Transactor[F])(using F: Async[F]):
  import Records.{lift, uuid}
  private val random = new SecureRandom
  private def digest(token: String) = Digest.bytes(token.getBytes(StandardCharsets.US_ASCII)).value
  private def valid(s: String, limit: Int) = s.nonEmpty && s.length <= limit && !s.exists(_.isControl)

  def bind(principal: PrincipalId, issuer: String, subject: String, name: String): F[Either[Problem, Unit]] =
    if !valid(issuer, 2048) || !valid(subject, 255) || !valid(name.trim, 120) then
      F.pure(Left(Problem.Unsupported("invalid identity or display name")))
    else OperationsSql.run(xa) {
      for
        _ <- OperationsSql.active *> OrganizationSql.guard(true) *> PolicySql.requireOperator
        active <- sql"SELECT EXISTS(SELECT 1 FROM principal WHERE id=${uuid(principal)} AND active)".query[Boolean].unique
        _ <- lift(Either.cond(active, (), Problem.Denied))
        _ <- sql"""INSERT INTO identity_binding(id,issuer,subject,principal_id,display_name)
          VALUES(${UUID.randomUUID()},$issuer,$subject,${uuid(principal)},${name.trim}) ON CONFLICT DO NOTHING""".update.run
        matches <- sql"""SELECT EXISTS(SELECT 1 FROM identity_binding WHERE issuer=$issuer AND subject=$subject
          AND principal_id=${uuid(principal)} AND display_name=${name.trim})""".query[Boolean].unique
        _ <- lift(Either.cond(matches, (), Problem.Conflict("different identity binding already exists")))
      yield ()
    }

  def unbind(principal: PrincipalId, issuer: String, subject: String): F[Either[Problem, Unit]] = OperationsSql.run(xa) {
    OperationsSql.active *> OrganizationSql.guard(true) *> PolicySql.requireOperator *>
      sql"DELETE FROM identity_binding WHERE issuer=$issuer AND subject=$subject AND principal_id=${uuid(principal)}".update.run.void
  }

  def issue(issuer: String, subject: String): F[Either[Problem, String]] = F.delay {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    "scs_" + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }.flatMap { token =>
    OperationsSql.run(xa) {
      for
        _ <- OperationsSql.active *> OrganizationSql.guard(false)
        binding <- sql"""SELECT b.id,b.principal_id FROM identity_binding b JOIN principal p ON p.id=b.principal_id
          WHERE b.issuer=$issuer AND b.subject=$subject AND p.active""".query[(UUID,UUID)].option
        pair <- lift(binding.toRight(Problem.Denied))
        _ <- CredentialSql.check(AccessContext(Id[PrincipalTag](pair._2)))
        _ <- sql"DELETE FROM browser_session WHERE expires_at <= clock_timestamp()".update.run
        _ <- sql"""INSERT INTO browser_session(id,binding_id,token_digest,expires_at)
          VALUES(${UUID.randomUUID()},${pair._1},${digest(token)},clock_timestamp() + interval '8 hours')""".update.run
      yield token
    }
  }

  def authenticate(issuer: String, token: String): F[Either[Problem, AccessContext]] =
    if !token.matches("scs_[A-Za-z0-9_-]{43}") then F.pure(Left(Problem.Denied))
    else OperationsSql.run(xa) {
      for
        _ <- OperationsSql.active *> OrganizationSql.guard(false)
        row <- sql"""SELECT s.id,b.principal_id FROM browser_session s JOIN identity_binding b ON b.id=s.binding_id
          JOIN principal p ON p.id=b.principal_id WHERE s.token_digest=${digest(token)} AND b.issuer=$issuer AND p.active
          AND s.expires_at > clock_timestamp()""".query[(UUID,UUID)].option
        ctx <- lift(row.map((id,p) => AccessContext(Id[PrincipalTag](p),Some(Id[CredentialTag](id)),true)).toRight(Problem.Denied))
        _ <- CredentialSql.check(ctx)
      yield ctx
    }

  def logout(token: String): F[Either[Problem, Unit]] = OperationsSql.run(xa) {
    sql"DELETE FROM browser_session WHERE token_digest=${digest(token)}".update.run.void
  }
