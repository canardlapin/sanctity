package sanctity.postgres

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import com.zaxxer.hikari.HikariConfig
import doobie.{ConnectionIO, Fragment, Transactor}
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import sanctity.application.{Failure, IdSource}
import sanctity.kernel.{Digest, Problem}

import java.nio.charset.StandardCharsets

final class DatabaseConfig private (
    val jdbcUrl: String, val user: String, private[postgres] val password: String,
    val schema: String, val poolSize: Int
):
  override def toString: String = s"DatabaseConfig(schema=$schema,poolSize=$poolSize,credentials=<redacted>)"

object DatabaseConfig:
  def from(jdbcUrl: String, user: String, password: String, schema: String = "sanctity", poolSize: Int = 4): Either[Problem, DatabaseConfig] =
    Either.cond(jdbcUrl.startsWith("jdbc:postgresql:") && user.nonEmpty &&
      schema.matches("[a-z][a-z0-9_]{0,62}") && !schema.startsWith("pg_") && schema != "public" &&
      poolSize >= 2 && poolSize <= 32,
      new DatabaseConfig(jdbcUrl, user, password, schema, poolSize),
      Problem.Unsupported("expected PostgreSQL JDBC URL, user, private lowercase schema, and pool size 2–32"))

  def environment(values: Map[String, String]): Either[Problem, DatabaseConfig] =
    for
      url <- values.get("SANCTITY_JDBC_URL").toRight(Problem.Unsupported("SANCTITY_JDBC_URL is required"))
      user <- values.get("SANCTITY_DB_USER").toRight(Problem.Unsupported("SANCTITY_DB_USER is required"))
      password <- values.get("SANCTITY_DB_PASSWORD").toRight(Problem.Unsupported("SANCTITY_DB_PASSWORD is required"))
      config <- from(url, user, password, values.getOrElse("SANCTITY_DB_SCHEMA", "sanctity"))
    yield config

final class PostgresDatabase[F[_]] private[postgres] (
    private[postgres] val transactor: Transactor[F], val schema: String
)(using F: Async[F]):
  def credentials(ids: IdSource[F]): PostgresCredentials[F] = new PostgresCredentials(transactor, ids)

  def policy: PostgresPolicy[F] = new PostgresPolicy(this)

  def organization: PostgresOrganizationCatalog[F] = new PostgresOrganizationCatalog(this)

  def checkOrganizationReady: F[Unit] = (OrganizationSql.guard(false).flatMap(enabled =>
    Records.require(enabled, "explicit organization bootstrap required"))).transact(transactor)

  def recovery: PostgresRecovery[F] = new PostgresRecovery(transactor)

  def journal(ids: IdSource[F]): PostgresImportJournal[F] = new PostgresImportJournal(transactor, catalog(ids), ids)
  def outbox(ids: IdSource[F]): PostgresOutbox[F] = new PostgresOutbox(transactor, catalog(ids), ids)

  def catalog(ids: IdSource[F]): PostgresCatalog[F] = new PostgresCatalog(transactor, ids)

  private def migrations: F[Vector[(Int, String, String)]] =
    F.blocking {
      Vector("V001__catalog.sql", "V002__authority.sql", "V003__queries.sql", "V004__operations.sql", "V005__credentials.sql", "V006__processing.sql", "V007__publication.sql", "V008__organization.sql", "V009__current_authority.sql", "V010__policy_witness.sql", "V011__governed_organization.sql").zipWithIndex.map { (name, index) =>
        val stream = Option(getClass.getResourceAsStream(s"/sanctity/postgres/$name"))
          .getOrElse(throw Failure(Problem.Operational("missing catalog migration")))
        val source = try new String(stream.readAllBytes(), StandardCharsets.UTF_8) finally stream.close()
        (index + 1, Digest.bytes(source.getBytes(StandardCharsets.UTF_8)).value, source)
      }
    }

  /** Explicit operator capability, never granted by a browser principal or runtime login. */
  def requireOperator: F[Unit] =
    (for
      allowed <- sql"""SELECT r.rolsuper OR EXISTS(
        SELECT 1 FROM pg_namespace n WHERE n.nspname=$schema AND n.nspowner=r.oid)
        FROM pg_roles r WHERE r.rolname=current_user""".query[Boolean].unique
      _ <- Records.lift(Either.cond(allowed, (), Problem.Denied))
    yield ()).transact(transactor)

  /** No DDL, even for an empty/older database. Recovery may check compatibility while closed. */
  def checkCompatibility(requireOpen: Boolean = true): F[Unit] = migrations.flatMap { expected =>
    (for
      existing <- sql"SELECT version, checksum FROM schema_migration ORDER BY version".query[(Int, String)].to[Vector]
      _ <- Records.require(existing == expected.map((v, c, _) => v -> c), "catalog schema is not exactly compatible; explicit migration required")
      closed <- sql"SELECT recovery_required FROM repository_state WHERE singleton=TRUE".query[Boolean].unique
      _ <- Records.require(!requireOpen || !closed, "repository recovery is required")
    yield ()).transact(transactor)
  }

  /** Reject an accidentally privileged runtime configuration, including inherited/SET ROLE authority. */
  def checkRuntimeRole: F[Unit] =
    (for
      safe <- sql"""SELECT NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls)
        AND NOT has_database_privilege(current_database(), 'CREATE,TEMPORARY')
        AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=r.oid)
        AND NOT EXISTS(SELECT 1 FROM pg_namespace n WHERE n.nspname NOT LIKE 'pg_%'
          AND n.nspname <> 'information_schema' AND (n.nspowner=r.oid OR has_schema_privilege(n.oid,'CREATE')))
        AND NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
          WHERE c.relowner=r.oid AND n.nspname NOT LIKE 'pg_%')
        AND NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
          WHERE n.nspname <> $schema AND n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
            AND c.relkind IN ('r','p','v','m','f') AND
            (has_table_privilege(c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
              OR has_any_column_privilege(c.oid,'INSERT,UPDATE,REFERENCES')))
        AND NOT has_table_privilege('schema_migration','INSERT,UPDATE,DELETE,TRUNCATE')
        AND NOT has_any_column_privilege('schema_migration','INSERT,UPDATE')
        AND NOT has_column_privilege('repository_state','recovery_required','UPDATE')
        AND NOT has_any_column_privilege('repository_state','INSERT')
        AND NOT has_table_privilege('repository_state','INSERT,DELETE,TRUNCATE')
        AND NOT has_any_column_privilege('api_credential','INSERT')
        AND NOT has_table_privilege('api_credential','INSERT,DELETE,TRUNCATE')
        AND NOT has_column_privilege('api_credential','token_digest','UPDATE')
        AND NOT has_column_privilege('api_credential','principal_id','UPDATE')
        AND NOT has_column_privilege('api_credential','expires_at','UPDATE')
        AND NOT has_column_privilege('api_credential','revoked_at','UPDATE')
        AND NOT has_table_privilege('organization_policy','INSERT,DELETE,TRUNCATE')
        AND NOT has_any_column_privilege('organization_policy','INSERT')
        AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=$schema
          AND table_name='organization_policy' AND column_name NOT IN ('singleton','revision','applied_hash')
          AND has_column_privilege('organization_policy',column_name,'UPDATE'))
        AND NOT has_table_privilege('policy_acknowledgement','UPDATE,DELETE,TRUNCATE')
        AND NOT has_any_column_privilege('policy_acknowledgement','UPDATE')
        AND NOT has_table_privilege('policy_retired_runtime','INSERT,UPDATE,DELETE,TRUNCATE')
        AND NOT has_any_column_privilege('policy_retired_runtime','INSERT,UPDATE')
        AND NOT has_table_privilege('policy_enrollment','INSERT,UPDATE,DELETE,TRUNCATE')
        AND NOT has_any_column_privilege('policy_enrollment','INSERT,UPDATE')
        AND NOT has_table_privilege('organization_event','UPDATE,DELETE,TRUNCATE')
        AND NOT has_any_column_privilege('organization_event','UPDATE')
        AND EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
          WHERE n.nspname=$schema AND c.relname='organization_event' AND c.relrowsecurity)
        AND NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
          WHERE n.nspname=$schema AND c.relname IN ('organization_lab','organization_project',
            'organization_membership','organization_participation','organization_placement','organization_stewardship','organization_link')
          AND (has_table_privilege(c.oid,'UPDATE,DELETE,TRUNCATE') OR (c.relname='organization_lab' AND has_any_column_privilege(c.oid,'INSERT'))))
        AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=$schema
          AND table_name IN ('organization_lab','organization_project','organization_membership',
            'organization_participation','organization_placement','organization_stewardship','organization_link')
          AND column_name <> 'payload' AND has_column_privilege(table_name,column_name,'UPDATE'))
        AND NOT has_any_column_privilege('principal','UPDATE')
        AND NOT has_column_privilege('dataset','authority_lifecycle','UPDATE')
        AND NOT has_column_privilege('release','authority_lifecycle','UPDATE')
        AND NOT has_column_privilege('dataset','curator','UPDATE')
        AND NOT has_column_privilege('dataset','custodian','UPDATE')
        FROM pg_roles r WHERE r.rolname=current_user""".query[Boolean].unique
      _ <- Records.require(safe, "runtime database role is not isolated from operator authority")
    yield ()).transact(transactor)

  private val governedOrganization = Vector("project", "membership", "participation", "placement", "stewardship", "link")

  /** Apply a reviewed, narrow profile to a pre-provisioned ordinary login. No automatic role creation. */
  def grantRuntime(role: String): F[Unit] =
    val valid = role.matches("[a-z][a-z0-9_]{0,62}") && !role.startsWith("pg_")
    if !valid then F.raiseError(Failure(Problem.Unsupported("invalid runtime role name")))
    else requireOperator *> checkCompatibility(requireOpen = false) *> (for
      ordinary <- sql"""SELECT rolcanlogin AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
        AND rolname <> current_user AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=pg_roles.oid)
        FROM pg_roles WHERE rolname=$role""".query[Boolean].option
      _ <- Records.require(ordinary.contains(true), "runtime role must be an existing ordinary login without role memberships")
      retired <- sql"SELECT EXISTS(SELECT 1 FROM policy_retired_runtime WHERE role_name=$role)".query[Boolean].unique
      _ <- Records.require(!retired,"enrollment retired this runtime identity; provision a fresh database login")
      _ <- Fragment.const(s"""REVOKE ALL ON ALL TABLES IN SCHEMA "$schema" FROM "$role"""").update.run
      _ <- Fragment.const(s"""REVOKE ALL ON ALL SEQUENCES IN SCHEMA "$schema" FROM "$role"""").update.run
      _ <- Fragment.const(s"""GRANT USAGE ON SCHEMA "$schema" TO "$role"""").update.run
      _ <- Fragment.const(s"""GRANT SELECT ON ALL TABLES IN SCHEMA "$schema" TO "$role"""").update.run
      inserts = Vector("principal", "dataset", "blob", "manifest", "manifest_entry", "interpretation", "metadata_artifact",
        "metadata_field", "assessment", "draft", "candidate", "release", "acceptance_receipt", "audit", "outbox",
        "access_grant", "policy_audit", "scientific_artifact", "scientific_entity", "scientific_value", "participant_value",
        "frozen_selection", "import_operation", "acquired_file", "outbox_delivery", "scientific_observation", "processing_run",
        "derivative_submission", "processing_publication", "organization_event", "policy_acknowledgement")
      _ <- inserts.traverse_(table => Fragment.const(s"""GRANT INSERT ON "$schema"."$table" TO "$role"""").update.run)
      updates = Map("dataset" -> "head", "access_grant" -> "revoked_at,generation", "draft" -> "generation,manifest_id,activity,accepted_release",
        "processing_run" -> "payload", "import_operation" -> "candidate_id,receipt,lease_token,lease_until",
        "outbox_delivery" -> "lease_token,lease_until,delivered_at,attempts",
        // PostgreSQL requires UPDATE on at least one column for SELECT ... FOR SHARE.
        // These grants permit locking without changing the recovery flag or credential powers.
        "repository_state" -> "singleton", "organization_policy" -> "singleton,revision,applied_hash", "api_credential" -> "id")
      _ <- updates.toVector.traverse_ { (table, columns) =>
        Fragment.const(s"""GRANT UPDATE ($columns) ON "$schema"."$table" TO "$role"""").update.run
      }
      // Governed organization commands (F02.3): the application authorizes each one under the policy guard and
      // row security admits only their event kinds. Labs are never inserted and principals never updated here.
      _ <- governedOrganization.traverse_ { table =>
        Fragment.const(s"""GRANT INSERT ON "$schema"."organization_$table" TO "$role"""").update.run
      }
      _ <- ("lab" +: governedOrganization).traverse_ { table =>
        Fragment.const(s"""GRANT UPDATE (payload) ON "$schema"."organization_$table" TO "$role"""").update.run
      }
      _ <- Vector("audit_sequence_seq", "outbox_sequence_seq", "policy_audit_sequence_seq")
        .traverse_(sequence => Fragment.const(s"""GRANT USAGE ON SEQUENCE "$schema"."$sequence" TO "$role"""").update.run)
    yield ()).transact(transactor)

  /** Advisory lock plus transactional DDL; drift or a newer schema fails closed. */
  def migrate: F[Unit] = migrations.flatMap { migrations =>
      val migration: ConnectionIO[Unit] = for
        permitted <- sql"""SELECT r.rolsuper OR EXISTS(SELECT 1 FROM pg_namespace n WHERE n.nspname=$schema AND n.nspowner=r.oid)
          OR (NOT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname=$schema) AND has_database_privilege(current_database(),'CREATE'))
          FROM pg_roles r WHERE rolname=current_user""".query[Boolean].unique
        _ <- Records.lift(Either.cond(permitted, (), Problem.Denied))
        _ <- sql"SELECT pg_advisory_xact_lock(hashtextextended($schema, 0))::text".query[String].unique
        _ <- Fragment.const(s"""CREATE SCHEMA IF NOT EXISTS "$schema" """).update.run
        _ <- sql"""CREATE TABLE IF NOT EXISTS schema_migration (
          version integer PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now()
        )""".update.run
        existing <- sql"SELECT version, checksum FROM schema_migration ORDER BY version".query[(Int, String)].to[Vector]
        _ <- Records.require(existing == migrations.take(existing.size).map((v, c, _) => v -> c),
          "catalog schema version or migration checksum mismatch")
        _ <- migrations.drop(existing.size).traverse_ { (version, checksum, source) =>
          source.split("(?m)^-- statement\\s*$").toVector.filter(_.trim.nonEmpty)
            .traverse_(statement => Fragment.const(statement).update.run) *>
            sql"INSERT INTO schema_migration (version, checksum) VALUES ($version, $checksum)".update.run.void
        }
      yield ()
      migration.transact(transactor)
    }

object PostgresDatabase:
  def open[F[_]: Async](config: DatabaseConfig, policy: Option[PolicyConnection] = None): Resource[F, PostgresDatabase[F]] =
    for
      hikari <- Resource.eval(Async[F].delay {
        val pool = new HikariConfig()
        pool.setDriverClassName("org.postgresql.Driver")
        pool.setJdbcUrl(config.jdbcUrl)
        pool.setUsername(config.user)
        pool.setPassword(config.password)
        pool.setMaximumPoolSize(config.poolSize)
        pool.setMinimumIdle(0)
        pool.setConnectionTimeout(10000L)
        pool.setValidationTimeout(5000L)
        pool.setTransactionIsolation("TRANSACTION_READ_COMMITTED")
        pool.setConnectionInitSql(s"""SET search_path TO "${config.schema}", pg_catalog;
          SET statement_timeout = '30s'; SET lock_timeout = '10s';
          SET idle_in_transaction_session_timeout = '30s'; SET synchronous_commit = on""")
        pool
      })
      xa <- HikariTransactor.fromHikariConfig[F](hikari)
    yield new PostgresDatabase[F](xa.copy(strategy0 = PolicyBridge.strategy(policy,xa.kernel.evictConnection)), config.schema)
