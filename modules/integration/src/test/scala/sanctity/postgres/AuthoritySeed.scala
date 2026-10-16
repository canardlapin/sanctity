package sanctity.postgres

import cats.effect.IO
import cats.syntax.all.*
import doobie.{ConnectionIO, Fragment}
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Encoder
import sanctity.app.OrganizationAuthorityFixtures as Corpus
import sanctity.application.*
import sanctity.kernel.*
import sanctity.kernel.OrganizationAuthority.*
import sanctity.objects.NativeDirectorySource
import java.sql.Timestamp
import java.time.{Duration, Instant}

/** Seeds an ADR 002 corpus state into PostgreSQL for authority and disclosure suites. */
object AuthoritySeed:
  import Records.uuid
  import OrganizationCodecs.given
  private def checked[A](v: IO[Either[Problem,A]]): IO[A] = v.flatMap(Checked.lift[IO,A])
  /** Test-only operator fixture: real relational releases reference cloned, FK-valid
    * draft/candidate rows. No test bypass is compiled into production authorization.
    * Shift the frozen corpus clock to the database clock, preserving expiry relations.
    */
  def seed(f: PgFixture, x: Corpus.Scenario): IO[State] = for
    preview <- checked(f.system.repository.importNative(f.system.context,f.system.dataset.id,new NativeDirectorySource[IO](f.root.resolve("source"))))
    now <- OrganizationSql.now.transact(f.database.transactor)
    delta = Duration.between(x.now,now)
    at = (t: Instant) => t.plus(delta)
    s = x.state.copy(
      principals=x.state.principals.view.mapValues(p => p.copy(createdAt=at(p.createdAt))).toMap,
      eligibility=x.state.eligibility.view.mapValues {
        case Eligibility.Local => Eligibility.Local
        case Eligibility.Guest(g) => Eligibility.Guest(g.copy(expiresAt=at(g.expiresAt)))
      }.toMap,
      memberships=x.state.memberships.view.mapValues(m => m.copy(createdAt=at(m.createdAt),expiresAt=m.expiresAt.map(at))).toMap,
      participations=x.state.participations.view.mapValues(r => r.copy(createdAt=at(r.createdAt),expiresAt=r.expiresAt.map(at))).toMap,
      stewardships=x.state.stewardships.view.mapValues(r => r.copy(createdAt=at(r.createdAt))).toMap,
      grants=x.state.grants.view.mapValues(g => g.copy(createdAt=at(g.createdAt),expiresAt=g.expiresAt.map(at))).toMap)
    _ <- (for
      _ <- s.principals.values.toVector.traverse_ { p =>
        val eligibility = JsonCodecs.encode(s.eligibility(p.id))
        sql"""INSERT INTO principal(id,label,active,generation,created_at,authority_eligibility)
          VALUES(${uuid(p.id)},${p.label.value},${p.active},${p.generation.value},${Timestamp.from(p.createdAt)},$eligibility::jsonb)""".update.run
      }
      _ <- s.datasets.values.toVector.traverse_ { d => for
        _ <- sql"INSERT INTO dataset(id,custodian,curator,authority_lifecycle) VALUES(${uuid(d.id)},${uuid(f.system.dataset.custodian)},${uuid(f.system.context.principal)},${d.lifecycle.toString})".update.run
        _ <- d.releases.toVector.traverse_ { r =>
          val draft = Corpus.id[DraftTag]("draft/"+r.render)
          val candidate = Corpus.id[CandidateTag]("candidate/"+r.render)
          for
            _ <- sql"""INSERT INTO draft(id,dataset_id,generation,manifest_id,activity)
              SELECT ${uuid(draft)},${uuid(d.id)},generation,manifest_id,activity FROM draft WHERE id=${uuid(preview.candidate.draft)}""".update.run
            _ <- sql"""INSERT INTO candidate(id,draft_id,dataset_id,generation,manifest_id,interpretation_id,assessment_id,activity)
              SELECT ${uuid(candidate)},${uuid(draft)},${uuid(d.id)},generation,manifest_id,interpretation_id,assessment_id,activity
              FROM candidate WHERE id=${uuid(preview.candidate.id)}""".update.run
            _ <- sql"""INSERT INTO release(id,dataset_id,draft_id,candidate_id,accepted_by,accepted_at,authority_lifecycle)
              VALUES(${uuid(r)},${uuid(d.id)},${uuid(draft)},${uuid(candidate)},${uuid(f.system.context.principal)},${Timestamp.from(now)},
              ${d.releaseLifecycle.getOrElse(r,Lifecycle.Available).toString})""".update.run
          yield ()
        }
        _ <- sql"UPDATE dataset SET head=${d.head.map(uuid)} WHERE id=${uuid(d.id)}".update.run
      yield () }
      _ <- insertRows("lab",s.labs.values.toVector)
      _ <- insertRows("project",s.projects.values.toVector)
      _ <- insertRows("membership",s.memberships.values.toVector)
      _ <- insertRows("participation",s.participations.values.toVector)
      _ <- insertRows("placement",s.placements.values.toVector)
      _ <- insertRows("stewardship",s.stewardships.values.toVector)
      _ <- insertRows("link",s.links.values.toVector)
      _ <- s.grants.values.toVector.traverse_ { g =>
        val basis=JsonCodecs.encode(g.basis)
        val kind=if g.basis == GrantBasis.Independent then "Independent" else "Derived"
        sql"""INSERT INTO access_grant(id,dataset_id,recipient,release_id,actions,issuer,created_at,expires_at,basis,basis_payload,generation,revoked_at)
          VALUES(${uuid(g.id)},${uuid(g.scope.dataset)},${uuid(g.recipient)},${g.scope.release.map(uuid)},${g.actions.toList.map(_.toString)},
          ${g.issuer.map(uuid)},${Timestamp.from(g.createdAt)},${g.expiresAt.map(Timestamp.from)},$kind,$basis::jsonb,${g.generation.value},
          ${Option.when(g.revoked)(Timestamp.from(now))})""".update.run
      }
      _ <- sql"""UPDATE organization_policy SET initialized=true,bootstrap_operator=${uuid(f.system.context.principal)},
        migration_digest=repeat('0',64),migration_report='{}'::jsonb WHERE singleton""".update.run
    yield ()).transact(f.database.transactor)
  yield s
  private def insertRows[A: Encoder](table: String, values: Vector[A]): ConnectionIO[Unit] = values.traverse_ { v =>
    val payload=JsonCodecs.encode(v)
    (Fragment.const(s"INSERT INTO organization_$table(payload) VALUES(") ++ fr"$payload::jsonb" ++ fr")").update.run
  }

