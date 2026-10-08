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

  def recovery: PostgresRecovery[F] = new PostgresRecovery(transactor)

  def journal(ids: IdSource[F]): PostgresImportJournal[F] = new PostgresImportJournal(transactor, catalog(ids), ids)
  def outbox(ids: IdSource[F]): PostgresOutbox[F] = new PostgresOutbox(transactor, catalog(ids), ids)

  def catalog(ids: IdSource[F]): PostgresCatalog[F] = new PostgresCatalog(transactor, ids)

  /** Advisory lock plus transactional DDL; drift or a newer schema fails closed. */
  def migrate: F[Unit] =
    F.blocking {
      Vector("V001__catalog.sql", "V002__authority.sql", "V003__queries.sql", "V004__operations.sql", "V005__credentials.sql").zipWithIndex.map { (name, index) =>
        val stream = Option(getClass.getResourceAsStream(s"/sanctity/postgres/$name"))
          .getOrElse(throw Failure(Problem.Operational("missing catalog migration")))
        val source = try new String(stream.readAllBytes(), StandardCharsets.UTF_8) finally stream.close()
        (index + 1, Digest.bytes(source.getBytes(StandardCharsets.UTF_8)).value, source)
      }
    }.flatMap { migrations =>
      val migration: ConnectionIO[Unit] = for
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
  def open[F[_]: Async](config: DatabaseConfig): Resource[F, PostgresDatabase[F]] =
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
    yield new PostgresDatabase[F](xa, config.schema)
