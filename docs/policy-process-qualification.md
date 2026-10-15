# Integrated policy failure qualification

O03.6.3 tests the current catalog and witness together. The
[catalog operator guide](policy-catalog.md) describes normal enrollment, outcomes and
retries. This qualification does not implement organization restore or authorize an
institutional installation.

The [qualification receipt](evidence/institutional-pilot/O03.6.3/receipt.json) records
33 distinct process cases, 250 authority regressions, 28 packaged checks and the
preserved historical upgrade. All six original criteria passed the
[O03.6 parent review](evidence/institutional-pilot/O03.6/receipt.json). This slice also fixes the server transaction deadline exposed by the finalization check;
scientific sources, migrations and historical fixtures remain unchanged.

## Fixture and isolation

The fixture uses the installable service's exact runtime JARs, a pinned PostgreSQL
image, and a pinned Java 21 image. Primary, witness and recovery operator run as
different OS users with separate mode-0700 writable volumes. Trust files and witness
records are mode 0600. A permission probe mounts each service's store under the other
user and verifies that both reading and writing are denied. The primary has its own
TLS identity and the witness's signing public key; it cannot read the recovery client
key or signing private key. The witness has no primary database credentials.

The fixture uses a private Docker network and the witness's loopback network namespace.
Root filesystems are read-only, capabilities are dropped, and privilege escalation is
disabled. The Docker operator remains trusted. All keys, principals, credentials and
data are synthetic. Separate containers and volumes on one computer demonstrate
component isolation; they do not demonstrate survival of that computer or its disk.

Before enrollment, the catalog has an accepted release, revoked release-scoped share,
revoked credential, disabled principal and credential, and active import/delivery
lease records. Enrollment must preserve those restrictions and the exact existing
event bytes, capture lease status without live capability tokens, retire the old SQL
runtime identity, and admit a fresh least-privilege runtime identity. Every scenario
also compares immutable scientific rows and the pre-enrollment event prefix with the
fixture baseline.

## Interruption matrix

The test-only JDBC wrapper pauses before PREPARE, before and after local commit,
before local receipt insertion, and before and after receipt commit. Two further
positions simulate lost local-commit and receipt-commit responses after PostgreSQL
has actually committed. The controller terminates the primary JVM at a confirmed
pause point. These hooks never appear in the service distribution.

A separate witness JVM pauses after a journal record or its head marker has been
forced, before returning the response. The controller kills that JVM for each of
ENROLL, PREPARE, COMMIT, ABORT and RECOVERY_BARRIER. Restart must verify the store,
retain a valid durable suffix, and return exact retained outcomes or keep uncertain
absence closed. The wrapper uses the production witness store and TLS server; it
cannot change their signatures or transition rules.

Additional scenarios exercise exact terminal retries, changed payloads, reordered
events, unknown capabilities, foreign installations, gaps, altered signatures,
altered signing pins, an empty witness, and stale catalogs. A locally committed
revocation must deny access while pending; witness outage must prevent a new write
from overtaking it. Actual deletion of the primary database checks independently
acknowledged state and unresolved-PREPARE behavior without using that database.
Restoring the older fixture must not authorize ABORT or reopen access.

A running database client and held SQL transaction are observed across a fresh signed
barrier. The local epoch remains unchanged. The old client's next admissions must
fail; the held backend transaction must disappear and its uncommitted writes roll
back within the five-second permit, allowing the receipt's stated polling tolerance.
The caller's sleeping thread is observed separately from the database transaction.

A separate finalization procedure stalls the acknowledgement INSERT for twenty
seconds using a PostgreSQL trigger and the unwrapped production connection. Server
logs must show transaction-timeout termination within the five-second phase budget;
the caller receives `Pending`, the committed restriction remains effective, and exact
resolution writes one acknowledgement without repeating the policy change. An active
query regression also spends part of the admission budget before starting a long
query, then verifies the backend transaction has actually disappeared.

This exposed and repaired a gap in client-only cancellation: closing the JDBC socket
could leave a server statement running until its own timeout. Each phase now sets the
remaining budget as PostgreSQL 17's
[transaction timeout](https://www.postgresql.org/docs/17/runtime-config-client.html#GUC-TRANSACTION-TIMEOUT).
The original candidate and diagnostic attempts are retained; they do not qualify the
repaired artifact.

The actual packaged HTTP server runs in an additional container with only artifact
JARs on its classpath. A valid bearer succeeds before the barrier, receives 503 from
the same running server afterward, and cannot cause a restarted old writer to start.
This separates the public-service check from the test-only pause controls.

## Reproduction and evidence

Compile `integration/Test/compile`, then archive only the `PolicyProcessQualification`
and `PolicyProcessWitness` classes from the integration test output into a separate
qualification JAR. Build a fresh service artifact with `scripts/package-service.py`.
The retained harness inputs identify the exact two test sources and JAR used.

```sh
python3 scripts/check-policy-processes.py \
  --artifact PATH_TO_SERVICE_ZIP \
  --harness PATH_TO_QUALIFICATION_JAR \
  --output NEW_EVIDENCE_DIRECTORY
```

`--smoke-only` qualifies setup/enrollment/isolation. `--packaged-only` runs that setup
and the actual HTTP-server barrier check. Output directories must be new. The script
removes its disposable containers, databases, volumes and network on exit. The locally
built fixture image may remain cached. Never point the harness at an existing catalog
or witness store.

The qualification receipt binds artifact and harness digests, source snapshots,
configuration identities, observed scenario outcomes, regression reports and package/
historical-upgrade receipts. Failed fixture attempts are retained separately. Existing
historical oracles are verified unchanged, not regenerated to accommodate a candidate.

O03.7/O03.8 retain replay, barrier release, restored-catalog opening and source-isolation
rehearsals. H03.1 retains backup automation. Full identity/session/lifecycle producers,
continuous byte-stream/worker checks, scale qualification, physical failure domains
and institutional approval remain separate work.
