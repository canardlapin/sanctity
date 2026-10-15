# Catalog enrollment and policy acknowledgement

O03.6.2 connects the current catalog to the [policy witness](policy-witness.md).
It remains a local engineering deployment. It does not enable organization restore,
qualify a separate physical failure domain, or supply institutional identity and
lifecycle administration. O03.6.3 supplies the passing
[integrated crash and fencing qualification](policy-process-qualification.md), including
actual packaged HTTP-service fencing. All six unchanged O03.6 criteria pass.
O03.7 and O03.8 still own replay and restore rehearsals; H03.1 backup automation is next.

## Trust and installation

Keep the writer trust bundle outside the catalog, object storage and their backup.
Retain the installation UUID, TLS trust anchors, endpoint and signing public key
independently. The writer has its own TLS client key. It never receives the witness
signing private key or the recovery client's key. The recovery client has a different
TLS certificate and a separate protected configuration. Both clients pin the same
installation and witness signing public key.

A client configuration is exactly this JSON object; paths are absolute, canonical,
owned by the executing account, mode 0600, in mode 0700 directories:

```json
{
  "installation": "00000000-0000-0000-0000-000000000001",
  "endpoint": "https://localhost:9443/v1/policy",
  "identityStore": "/protected/writer.p12",
  "identityPasswordFile": "/protected/writer-password",
  "trustStore": "/protected/anchors.p12",
  "trustPasswordFile": "/protected/anchors-password",
  "signingPublic": "/protected/witness-signing-public.der"
}
```

The PKCS12 identity store must contain exactly one private-key entry. Supply
`SANCTITY_POLICY_CONFIG=/protected/writer.json` to the packaged service and ordinary
operator commands. Missing trust on an enrolled catalog refuses admission. Supplying
trust to a local-engineering catalog also refuses ordinary admission; that prevents
a restored pre-enrollment backup from silently selecting local mode. Enrollment
itself uses explicit writer and recovery configuration arguments.

The trust file's presence does not prove that its physical storage survives loss of
the computer. Separate custody, off-host persistence, clock reliability and operating
system account separation remain deployment qualification work.

## Enroll an existing local catalog

1. Stop the service and workers, retain their current configuration, and keep browser
   access closed. Run the explicit schema migration and organization bootstrap if needed.
2. Before setting `SANCTITY_POLICY_CONFIG`, run `sanctity policy readiness` to obtain
   the initialized catalog's installation UUID. Initialize and start a fresh witness
   for that UUID using the standalone witness guide. Never initialize over existing witness history.
3. As the database operator, run:

   ```sh
   sanctity policy enroll /protected/writer.json /protected/recovery.json
   ```

4. Enrollment obtains the repository maintenance lock and installation exclusive lock.
   It revokes existing ordinary runtime roles' privileges in this catalog schema and
   records those retired identities. Their credentials cannot be granted the runtime
   profile again. Inherited or PUBLIC access that survives revocation refuses enrollment.
   Provision a **fresh ordinary database login** for the new service, then explicitly
   apply `sanctity migrate --runtime-role NEW_ROLE` and configure its writer trust file.
5. Run `sanctity policy readiness` with writer trust configured, then start the updated
   service. Keep the recovery configuration separate from the service configuration.

The maintenance transaction stores the exact enrollment operation and checkpoint,
sets `recovery_required`, and commits before contacting the witness. Failed or lost
acknowledgement leaves the catalog closed. Re-running the enrollment command resumes
that exact retained checkpoint; a changed catalog or trust binding refuses it. Opening
requires both a verified ENROLL record and a fresh writer CURRENT response matching
the installation, capability set, epoch and applied head. Completed enrollment cannot
be repeated or used to reopen a restored independent catalog.

Privileged database operators remain trusted. Retirement fences ordinary service
identities, not a malicious schema owner or superuser. A new service must pass the
existing runtime privilege checks; operator credentials are not a runtime deployment.

## Checkpoint coverage

The snapshot includes every row, including ended appointments, archived organizations,
inactive principals, revoked grants and credentials. It contains principal eligibility;
dataset and release identity/lifecycle; all seven organization tables; full grants and
policy audit; credential status/audit; import and delivery lease status/expiry;
processing-run state; installation/bootstrap and schema identity; historical policy event identity/payload/hash; and retired runtime
identities. Bearer secrets, token digests and import/delivery lease capability tokens
are excluded from the current credential/lease inventory. Existing historical event
payloads retain their original bytes and identity.

Enrollment verifies the complete revision/hash chain, including historical
`organization-local-v1` and current `policy-local-v2`. It never relabels old LocalOnly
receipts as independently acknowledged. Current state is required because historical
organization events alone did not describe every grant or credential change.

The supported live producers are the nineteen kinds in
[the transaction inventory](policy-transactions.md). Nondefault guest eligibility or
non-Available dataset/release lifecycle refuses enrollment and live admission. New
schema/producer versions also require an explicit capability extension. Identity,
sponsorship, browser sessions, retention and hold administration remain unimplemented.
Checkpoints over 4 MiB and events over 1 MiB are refused in full, never truncated.
This full-state implementation requires later scale qualification.

## Transaction outcomes and retries

The installation exclusive lock spans authorization, exact event construction and
one bounded signed PREPARE call. Policy effects, credential/lease invalidations,
immutable event, prepare certificate and Pending receipt commit together. The same
transaction connection then reacquires the live exclusive guard, proves the exact
committed event, obtains a signed COMMIT and appends its acknowledgement. Only then
can a completed return value leave the transaction adapter.

| Result | Meaning | Next action |
| --- | --- | --- |
| `LocalOnly` | Historical or unenrolled local commit | No independent durability claim |
| `Pending` / `PolicyPending(operation)` | Local commit succeeded; independent acknowledgement is not established to the caller | Restrictions already apply; use `sanctity policy resolve` |
| `PolicyOutcomeUnknown(operation)` | A PREPARE or local commit response is ambiguous | Retain the operation identity; resolve before retrying effects |
| `Durable` | Verified independent COMMIT | Receipt acknowledges the witness's declared durability boundary |

Successful HTTP grant/revoke responses include `X-Sanctity-Policy-Durability` with
`LocalOnly` or `Durable`; credential output and operator messages also state durability.
HTTP policy failures use 503 with distinct `policy_pending:UUID` and
`policy_outcome_unknown:UUID` codes. Operator commands return a nonzero status with a
redacted operation identifier. They never print a bearer or checkpoint to diagnostics.
`policy readiness` shows only installation, epoch, revision/head, mode, capability
fingerprint and pending state. It fails when currentness cannot be established.

A pending locally committed event permits reads only after fresh witness currentness
confirms that exact local event. Current local authorization still denies revoked
access. Witness outage closes admission. A subsequent policy write must reconcile
its predecessor before preparing another event. Lost COMMIT responses replay the
exact signed terminal record; they do not repeat the effects.

ABORT has one producer: the still-live connection that prepared, rolled back without
ever attempting COMMIT, reacquired the exclusive guard, and proved the original head
and absence of its event. Connection loss, restart, restored absence, or ambiguous
local COMMIT does not supply that proof. Such unresolved absence stays closed.

For credential issuance after enrollment use a retained UUID:

```sh
sanctity repository issue-credential-once OPERATION_ID PRINCIPAL SECONDS LABEL NEW_FILE
```

The UUID is also the credential ID. Reusing it never creates another credential.
A bearer is disclosed once and is not recoverable from the catalog or witness. If
the acknowledgement or output file is lost, resolve the policy operation, explicitly
revoke that credential ID, and issue with a new UUID. The old issuance command is
refused in independent mode because it generates an unretained random identity.

## Admission and fencing

Every guarded SQL transaction gets a fresh signed CURRENT response; there is no
cross-transaction authority cache. It checks installation, writer certificate, epoch,
capabilities and local/remote applied heads, and verifies stored acknowledgement
signatures. A permit expires five seconds from the start of that request, including
network time. Repeated guards and transaction commit check the absolute deadline.
A deadline aborts the borrowed JDBC connection. PostgreSQL 17's transaction timeout
also bounds each entire server transaction by the remaining phase budget, including
active statements; statement and idle timeouts remain additional backstops. Older
PostgreSQL versions cannot admit independent policy transactions. After local commit, a separate five-second
phase may only finalize the exact event; it admits no new authority effects. Allow ten seconds for admitted transaction
drain before recovery work, in addition to independently fencing the old writer.

A durable barrier removes the writer identity at the witness. New admissions from
the running service fail even when its own database epoch and head have not changed.
Previously admitted work remains bounded by its permit. Continuous byte delivery and
external-worker heartbeat coverage remains F03.4; this slice supplies their SQL
admission boundary, not a claim that every external side effect is cancellable.

The additive V010 migration adds enrollment, retired runtime identity and append-only
acknowledgement storage. V001–V009, historical event payloads, scientific identity and
attribution remain unchanged. The legacy recovery path still rejects initialized
organization catalogs.
