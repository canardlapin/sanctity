# Sanctity service distribution

The internal development distribution runs the API and browser console, catalog
migrations and trusted operator commands from JARs. Runtime requirements are Java
21 or newer, a POSIX shell (including `dirname`), PostgreSQL and writable managed
storage. Java 21 is the currently tested runtime; newer Java versions require
qualification. No Scala, sbt, Python or source checkout is required to run it.
The current qualification uses PostgreSQL 17. Independent policy mode requires its
transaction timeout to enforce server-side admission and finalization deadlines;
older servers refuse independent admission. Startup validates private storage,
database credentials and the explicit origin before opening a listener. Configuration,
proxy, supervision and separate database-role templates are included. The retained historical corpus qualifies the V008 organization cutover; the complete
installation/upgrade rehearsal remains O01.4.

## Build and identity

Build prerequisites: Java 21, sbt 1.10.5, Python 3.9+ and network access or populated
dependency caches. From the repository root:

```sh
python3 scripts/package-service.py --output target/service-release
python3 scripts/check-service-package.py target/service-release/sanctity-0.1.0-SNAPSHOT.zip --output target/service-smoke
```

Choose new output directories for subsequent runs. The build cleans first-party
outputs, uses sbt's resolved service classpath, packages the exact JAR closure, and
checks the vendored bids4s source against its SHA-256 pin. Source overrides are
rejected. `manifest.json` records application, Scala, sbt, upstream and dependency
versions, hashes, reported licenses and embedded notices. `metadata/source.json`
identifies the actual build inputs, including uncommitted files; a Git revision alone
is insufficient. Source paths are repository-relative; no cache paths or environment
secrets are shipped. The source inventory includes processing code because the
existing scientific BuildIdentity uses it, but its executable module is excluded.

The adjacent `.sha256` file identifies the ZIP; obtain that digest through a trusted
release channel before installation. The manifest identifies every packaged payload
file. It is an integrity inventory, not a signature or runtime tamper-prevention
mechanism. Keep the installed distribution read-only to the service account.
ZIP timestamps, ordering and permissions are fixed; packaging identical JAR inputs
produces identical bytes. This does not claim bit-identical compiler output across
different machines/JDKs. Retain the exact source snapshot and build inputs with any
release promoted to staging.

Sanctity currently has no declared repository license; its inventory records
`NOASSERTION`. Dependency licenses and original JAR notices are retained without
alteration; bids4s's Apache-2.0 license is included. This is an internal development
artifact, not a declaration of licensing terms or a public release.

## Install and run

Verify the trusted ZIP digest, unpack into a new versioned directory and preserve
the executable bit on `bin/sanctity` (set `chmod 755 bin/sanctity` if the unzip tool
does not preserve it). The launcher supports paths with spaces and honors JAVA_HOME.
From the unpacked directory:

```sh
bin/sanctity --version
bin/sanctity --help
```

Supply `SANCTITY_JDBC_URL`, `SANCTITY_DB_USER` and exactly one of
`SANCTITY_DB_PASSWORD_FILE` (preferred) or `SANCTITY_DB_PASSWORD`. Secret files must
be regular mode-0600 files owned by the service user in a mode-0700 directory; one
terminal newline is permitted. File and storage paths must be absolute canonical
paths with no symlink alias or `..` component. Resolve the containing directory with
`realpath` before configuring it, including macOS `/var` or `/tmp` aliases. Secrets
must be outside the blob root. No configuration file is evaluated as shell code.

The supported JDBC form is `jdbc:postgresql://HOST:PORT/DATABASE`, optionally with
one `sslmode` parameter (`disable`, `require`, `verify-ca` or `verify-full`). Credentials,
arbitrary driver parameters, URL userinfo and fragments are rejected. Remote databases
need an independently configured trusted TLS connection; `require` alone does not
verify the server identity. The demonstrated local profile uses loopback PostgreSQL.
`SANCTITY_DB_SCHEMA` defaults to `sanctity`; `SANCTITY_DB_POOL_SIZE` defaults to 4
and permits 2–32 connections. Configuration checks do not infer deployment capacity.

Create `SANCTITY_STORAGE` explicitly, owned by the service user and mode 0700.
Startup refuses missing, read-only, public, aliased or unsafe roots; it creates private
`objects`/`staging` directories if absent and performs a removable write/flush probe.
Existing child directories must also be private and owned by the service user.
The root cannot contain the working directory, the user's home or credential file.
Only the service account may change this tree; these checks do not protect against
that account maliciously replacing its own files. Do not point storage at source data,
database files or an existing unrelated directory. The launcher sets umask 077.

`SANCTITY_HTTP_PORT` defaults to 8080 (range 1–65535). The default
`SANCTITY_DEPLOYMENT_MODE=loopback` accepts only `http://127.0.0.1:PORT` as its
public origin. For a TLS proxy, explicitly select `proxy` and set
`SANCTITY_PUBLIC_ORIGIN=https://YOUR-HOST` (optional port; no path/trailing slash,
userinfo, query or fragment). The listener always binds 127.0.0.1. Requests must
have exactly the configured Host and, when present, Origin. Cross-site browser
requests are denied. Forwarding headers are stripped and cannot change authority,
identity or the configured origin. Local processes remain within the host trust
boundary; proxy mode does not authenticate a proxy process.

The distribution's `deployment/` directory contains environment, nginx TLS, systemd
and macOS launchd examples (repository sources: [deployment](deployment/)). Replace
their example paths and identities, protect configuration/logs, validate the installed
supervisor/proxy configuration, and independently check certificates and network
exposure before use. These are templates, not a claim that Linux supervision or an
institutional TLS endpoint has been qualified. No supervisor is installed automatically.
Do not put credentials in command arguments, shell history or checked-in files.

Provision a dedicated database and two ordinary logins using the DBA-reviewed
[database roles template](deployment/database-roles.sql.example). The migration login
owns Sanctity's schema; the runtime login has no ownership, role memberships, database
CREATE/TEMPORARY, schema CREATE or privileges to administer unrelated tables. Revoke
the template's PUBLIC privileges only on the dedicated database. Restrict the runtime
login to that database in `pg_hba.conf`; do not reuse it for another application.

With the migration login and its separate protected secret file:

```sh
bin/sanctity migrate --runtime-role sanctity_runtime
```

The operator must also complete the explicit organization cutover before serving.
For an existing catalog, stop all old servers/workers before previewing; for a synthetic
new demonstration, legacy operator creation/import can seed data before this cutover.
OPERATOR is a stable principal UUID chosen by the installation owner; the bootstrap
adds its directory entry without claiming a verified institutional identity.

```sh
bin/sanctity migrate --organization-preview OPERATOR /absolute/private/organization-preview.json
# Review the private JSON mapping and use the printed digest:
bin/sanctity migrate --organization-bootstrap OPERATOR REVIEWED_DIGEST
```

Bootstrap groups legacy datasets into private projects, installs explicit stewardship,
and records a local genesis event atomically. Input changes invalidate the preview;
an exact retry returns the original result. A new empty installation gets a migration
lab and operator admin; project/dataset administration then uses the trusted adapter
until F02.3 supplies the supported public commands. Do not infer a complete demo flow
from this engineering cutover. The full contract is in the source repository at `docs/organization-reference.md`.

Switch to the runtime login and its protected secret file, then:

```sh
bin/sanctity serve
```

`serve` performs read-only exact version/checksum and recovery-gate checks and refuses
an incompatible, unbootstrapped, recovery-closed or privileged runtime configuration. It never migrates.
Operator commands also never migrate implicitly. Migration grants SELECT, the explicit
normal-operation INSERT list, selected mutable columns and sequence usage; it does not
grant DDL, DELETE, TRUNCATE, migration-history changes, recovery reopening or credential
administration. The singleton/id column grants permit PostgreSQL's `FOR SHARE` locking
without granting updates to the recovery flag or credential powers. The runtime database
credential remains trusted server authority: this profile is not row-level isolation
from malicious direct SQL by someone who steals that credential.

The browser console is at `/` and API at `/v1`. External access requires the deployment's
TLS reverse proxy. An institutional host must keep migration/operator secret files
unreadable to the runtime OS account. The local same-user qualification verifies database
privileges, not this separate OS-account boundary. Environment examples distinguish the
two identities; no browser endpoint can issue credentials or bootstrap operator access.

SIGTERM stops admission and gives existing HTTP responses up to
`SANCTITY_SHUTDOWN_SECONDS` (default 10, range 1–120) to finish, then cancels stalled
responses and releases their resources before closing the database pool. Give the
supervisor at least ten additional seconds (the templates use 20 for the default).
Forced termination cannot drain work: transactional publication and durable operation
leases still govern retry; a client losing the response must check the operation's
recorded outcome rather than assume failure or blindly create another request.
The server does not currently run a background import worker. Existing broad resource
limits and future background-worker shutdown remain O02.3/W01 responsibilities.

Startup failures and operator entry-point failures report fixed categories without
JDBC URLs, filesystem paths, secret values or nested exception causes. HTTP failures
similarly omit raw exceptions and forwarded values. This minimal error boundary does
not supply O02.1's planned request IDs, health endpoints or operational diagnostics.

## Trusted operator commands

The distribution also provides `policy-witness init|serve|inspect CONFIG` as an
explicit standalone process. It takes protected JSON configuration, needs its own
TLS/signing material and local journal, and never connects to the primary catalog.
This O03.6.1 component alone does not change catalog `LocalOnly` receipts.
The explicit O03.6.2 enrollment command below connects it to policy transactions;
organization recovery remains closed. Configuration and qualification are
documented in the source tree's `docs/policy-witness.md`.

The following repository/recovery commands require the schema owner or database superuser and a compatible catalog.
The runtime login is refused before command execution. They are not public authentication
shortcuts. PRINCIPAL, CUSTODIAN and DATASET are stable identifiers.

```text
bin/sanctity repository create PRINCIPAL CUSTODIAN
bin/sanctity repository import PRINCIPAL DATASET SOURCE STORAGE KEY
bin/sanctity repository issue-credential PRINCIPAL SECONDS LABEL NEW_FILE
bin/sanctity repository revoke-credential CREDENTIAL_ID
bin/sanctity recovery capture-policy NEW_FILE
bin/sanctity recovery reconcile POLICY TRUSTED_DIGEST STORAGE
```

`repository create` is a pre-bootstrap legacy staging command and refuses an initialized
organization catalog. `recovery capture-policy` and `reconcile` are legacy-only and
refuse organization-enabled catalogs. They cannot establish current organization
policy or reopen a restored V008 installation. Independent recovery under ADR004 remains
O03.2; keep organization-enabled restores closed until that protocol is implemented.

Credential issuance writes a new mode-0600 file and does not print its secret. Native
intake is a trusted local-directory operation. Recovery capture contains sensitive
policy: use a protected directory. Reconciliation needs independently trusted current
policy; possession of a digest beside an old backup does not establish freshness.
Independent durable policy recovery remains O03.

The distribution includes the existing API, console assets, SQL migrations, native
BIDS intake and recovery mechanisms. It excludes development demos, test libraries,
DICOM/XNAT adapters and optional numerical processing recipes. Those can be added
through explicitly qualified distributions later. The source-development `app/runMain`
commands remain available through the app's dependency on the service module.

## Upgrade and recovery procedure

Keep the exact installed ZIP, source inventory, configuration version and a verified
catalog/blob backup before an upgrade. Quiesce writers and stop the old server; capture
current authority independently of a stale backup. On a restored staging copy, run the
new artifact's explicit migration as the schema owner, refresh the runtime profile with
`--runtime-role`, explicitly preview/bootstrap organization state, and validate historical
releases, exact bytes and current denials before
restarting the runtime login. Do not edit an applied SQL file or its checksum to force
acceptance. Runtime startup refuses missing, older, newer or changed migration records.

Migrations use an advisory lock and one database transaction. Termination rolls back
uncommitted DDL; retry explicitly after checking the operator log/category. This does
not make future nontransactional external operations safe; such a migration needs its
own recovery design. V008 is additive and leaves V001–V007 unchanged. Organization bootstrap is a separate
transaction with its own reviewed digest and idempotent retry.

There are no supported in-place downgrade migrations. Roll back only to an exact known
artifact with its compatible backup and a controlled outage, accounting for writes since
the backup. Restore into a closed repository and reconcile independently current authority
before serving. The schema-owner legacy recovery command refuses an organization-enabled catalog;
the runtime cannot reopen it. Independent durable authority recovery is O03;
retained historical-corpus upgrades and the full operator rehearsal are H06.2/O01.4.

## Verification boundaries

The smoke harness needs Python and Docker on the **test host**, starts a disposable
pinned PostgreSQL container, unpacks to an isolated directory, and launches Java with
a restricted PATH and no application sources. It checks migration twice with an ordinary schema owner, serves with a separate runtime
login, refuses operator commands under that runtime login, rejects privileged serving, and checks private
credential issuance/revocation, API authentication, console resources, native intake,
byte retrieval, explicit reviewed organization bootstrap and refusal of legacy policy
capture after cutover. It retains redacted evidence and always stops its
service/container. It also rejects invalid settings and occupied ports, checks
Host/Origin enforcement and raw-log canaries, and verifies exact accepted state and
revocation after real SIGTERM/SIGKILL restarts. Service tests separately exercise
active-response draining and cancellation at the configured shutdown deadline.
Its PostgreSQL password is synthetic and restricted to this disposable
test. This is artifact qualification on the current host, not operator-led clean-host
installation, independent disaster recovery, institutional load or pilot acceptance.

## Optional independent policy enrollment

The `policy` command integrates an explicitly initialized witness with the current
catalog. Follow the repository's `docs/policy-catalog.md` maintenance procedure.
`deployment/policy-client.json.example` describes the protected client configuration;
set `SANCTITY_POLICY_CONFIG` on the service and operator commands after enrollment.
The primary receives the witness signing public key only. Recovery uses a separate
client identity; keep its key out of the service configuration and primary backup.

Enrollment closes admission, freezes complete current authority and retires existing
runtime SQL identities. Provision a fresh ordinary login for the updated service;
retired identities cannot receive the runtime profile again. `policy readiness` fails
without fresh witness currentness. `policy resolve` reconciles a proven committed
pending event, and cannot abort an intent based on absence in a restored database.

In independent mode, use `repository issue-credential-once OPERATION_ID PRINCIPAL
SECONDS LABEL NEW_FILE` and retain the UUID across failures. An already-issued ID
cannot produce another credential or replay its secret. Pending and unknown policy
outcomes are nonzero command results; they must not be treated as completed success.
Local engineering mode and old receipts remain LocalOnly. Organization restore
remains blocked until the separate replay and rehearsal work is qualified.
