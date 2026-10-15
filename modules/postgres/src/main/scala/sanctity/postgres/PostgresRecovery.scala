package sanctity.postgres

import cats.effect.Async
import cats.syntax.all.*
import doobie.Transactor
import doobie.implicits.*
import doobie.postgres.implicits.*
import sanctity.application.{BlobStore, Checked}
import sanctity.kernel.*

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

final case class CustodyBinding(dataset: DatasetId, custodian: CustodianId, curator: PrincipalId)
final case class PolicySnapshot(observedAt: Instant, custody: Vector[CustodyBinding], grants: Vector[Grant]):
  def digest: Digest = Digest.framed("sanctity.policy-snapshot.v1", Vector(observedAt.toString, custody.size.toString) ++
    custody.sortBy(_.dataset.render).flatMap(c => Vector(c.dataset.render, c.custodian.render, c.curator.render)) ++
    Vector(grants.size.toString) ++ grants.sortBy(_.id.render).flatMap(g => Vector(g.id.render, g.dataset.render, g.recipient.render,
      g.release.fold("")(_.render), g.actions.size.toString) ++ g.actions.toVector.map(_.toString).sorted))

/** Operator-only recovery capability; never exposed as a researcher endpoint.
  * A trusted current policy snapshot is supplied separately from the older backup.
  */
final class PostgresRecovery[F[_]: Async] private[postgres](xa: Transactor[F]):
  import Records.{lift, uuid}
  import JsonCodecs.given

  private def legacyOnly: doobie.ConnectionIO[Unit] = OrganizationSql.guard(false).flatMap(enabled =>
    lift(Either.cond(!enabled, (), Problem.Conflict("legacy policy recovery cannot cover organization authority; ADR004 recovery required"))))

  def capturePolicy: F[PolicySnapshot] =
    (for
      _ <- sql"SET TRANSACTION ISOLATION LEVEL REPEATABLE READ".update.run
      _ <- OperationsSql.active
      _ <- legacyOnly
      timestamp <- sql"SELECT clock_timestamp()".query[Timestamp].unique
      rows <- sql"SELECT id,custodian,curator FROM dataset ORDER BY id".query[(UUID, UUID, UUID)].to[Vector]
      grants <- sql"SELECT id,dataset_id,recipient,release_id,actions FROM access_grant WHERE revoked_at IS NULL"
        .query[(UUID, UUID, UUID, Option[UUID], List[String])].to[Vector]
      decoded <- grants.traverse { (id, dataset, recipient, release, actions) =>
        actions.traverse(a => lift(Action.values.find(_.toString == a).toRight(Problem.Integrity("unknown grant action"))))
          .map(as => Grant(Id[GrantTag](id), Id[DatasetTag](dataset), Id[PrincipalTag](recipient), release.map(Id[ReleaseTag].apply), as.toSet))
      }
    yield PolicySnapshot(timestamp.toInstant, rows.map((d, c, p) => CustodyBinding(Id[DatasetTag](d), Id[CustodianTag](c), Id[PrincipalTag](p))), decoded)
    ).transact(xa)

  def encodePolicy(snapshot: PolicySnapshot): String = JsonCodecs.encode(snapshot)
  def decodePolicy(text: String): Either[Problem, PolicySnapshot] = JsonCodecs.decode[PolicySnapshot](text)

  /** The supported restore tool sets this before any application is started. */
  def requireReconciliation: F[Unit] =
    sql"UPDATE repository_state SET recovery_required=TRUE".update.run.transact(xa).void

  def reconcile(snapshot: PolicySnapshot, trustedDigest: Digest, store: BlobStore[F]): F[Either[Problem, Unit]] =
    Checked.attempt {
      for
        _ <- Checked.lift[F, Unit](Either.cond(snapshot.digest == trustedDigest && snapshot.custody.map(_.dataset).distinct.size == snapshot.custody.size &&
          snapshot.grants.map(_.id).distinct.size == snapshot.grants.size, (), Problem.Integrity("invalid trusted policy snapshot")))
        required <- sql"SELECT recovery_required FROM repository_state WHERE singleton=TRUE".query[Boolean].unique.transact(xa)
        _ <- Checked.lift[F, Unit](Either.cond(required, (), Problem.Conflict("reconciliation requires a closed recovery gate")))
        rows <- sql"SELECT digest,size FROM blob".query[(String, Long)].to[Vector].transact(xa)
        blobs <- rows.traverse((digest, size) => Checked.lift[F, BlobRef](Digest.from(digest).flatMap(BlobRef.from(_, size))))
        checkpoints <- sql"SELECT payload::text FROM acquired_file".query[String].to[Vector].transact(xa)
        acquired <- checkpoints.traverse(s => Checked.lift[F, Entry](JsonCodecs.decode[Entry](s))).map(_.collect { case f: Entry.File => f.blob })
        _ <- (blobs ++ acquired).distinct.traverse_(store.verify)
        _ <- (for
          gate <- sql"SELECT recovery_required FROM repository_state WHERE singleton=TRUE FOR UPDATE".query[Boolean].unique
          _ <- Records.require(gate, "recovery gate changed during verification")
          _ <- legacyOnly
          restored <- sql"SELECT id FROM dataset ORDER BY id FOR UPDATE".query[UUID].to[Vector]
          _ <- Records.require(restored.forall(id => snapshot.custody.exists(c => uuid(c.dataset) == id)),
            "current policy snapshot does not cover all restored datasets")
          principals = (snapshot.custody.map(_.curator) ++ snapshot.grants.map(_.recipient)).distinct
          _ <- principals.traverse_(p => sql"INSERT INTO principal(id) VALUES(${uuid(p)}) ON CONFLICT DO NOTHING".update.run)
          _ <- sql"UPDATE access_grant SET revoked_at=clock_timestamp() WHERE revoked_at IS NULL".update.run
          _ <- snapshot.custody.filter(c => restored.contains(uuid(c.dataset))).traverse_ { c =>
            sql"UPDATE dataset SET curator=${uuid(c.curator)},custodian=${uuid(c.custodian)} WHERE id=${uuid(c.dataset)}".update.run
          }
          _ <- snapshot.grants.filter(g => restored.contains(uuid(g.dataset))).traverse_ { g =>
            for
              _ <- lift(Authority.validate(g.release, g.actions))
              exists <- g.release.fold(true.pure[doobie.ConnectionIO])(r =>
                sql"SELECT EXISTS(SELECT 1 FROM release WHERE id=${uuid(r)} AND dataset_id=${uuid(g.dataset)})".query[Boolean].unique)
              _ <- if !exists then ().pure[doobie.ConnectionIO] else
                sql"""INSERT INTO access_grant(id,dataset_id,recipient,release_id,actions,revoked_at)
                  VALUES(${uuid(g.id)},${uuid(g.dataset)},${uuid(g.recipient)},${g.release.map(uuid)},${g.actions.toList.map(_.toString).sorted},NULL)
                  ON CONFLICT(id) DO UPDATE SET dataset_id=EXCLUDED.dataset_id,recipient=EXCLUDED.recipient,
                    release_id=EXCLUDED.release_id,actions=EXCLUDED.actions,revoked_at=NULL""".update.run.void
            yield ()
          }
          // A restored credential may have been revoked after the backup. Reissue out of band.
          _ <- sql"""INSERT INTO credential_audit(credential_id,action)
            SELECT id,'RecoveryInvalidated' FROM api_credential WHERE revoked_at IS NULL""".update.run
          _ <- sql"UPDATE api_credential SET revoked_at=clock_timestamp() WHERE revoked_at IS NULL".update.run
          // Old workers must reacquire authority and a fresh lease after restoration.
          _ <- sql"UPDATE import_operation SET lease_token=NULL,lease_until=NULL".update.run
          _ <- sql"UPDATE outbox_delivery SET lease_token=NULL,lease_until=NULL".update.run
          _ <- sql"INSERT INTO recovery_audit(policy_digest) VALUES(${snapshot.digest.value})".update.run
          _ <- sql"UPDATE repository_state SET recovery_required=FALSE".update.run
        yield ()).transact(xa)
      yield ()
    }
