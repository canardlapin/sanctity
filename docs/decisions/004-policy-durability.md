# ADR 004: Independently durable policy and closed recovery

14 October 2026. O03.1 engineering contract, version 1. Extends
[ADR 003](003-institutional-lifecycle.md); F02.2 owns the schema, F03.2 owns transaction
enforcement, and O03.2 implements the independent service. This document and its
executable model do not claim deployed replication or institutional disaster recovery.

## Failure model and authority

An acknowledged restrictive change must survive loss of the entire primary service,
catalog and blob namespace. Scientific backups may lose data within their declared
RPO; acknowledged authority/lifecycle changes have **zero-event policy RPO**. An older
scientific release must never bring back a disabled principal, revoked share, ended
sponsorship, withdrawn dataset or former steward's authority.

Use an independent **policy witness**: an authenticated service holding an append-only,
ordered durable journal, checkpoints and the current installation/writer epoch. It is
not an ordinary outbox consumer. Its successful write response means its own declared
durability boundary has been crossed. Its read-current operation is linearizable.
The production witness must survive the declared primary failure domain and must not
share the primary's writable credentials, database/volume, administrator recovery key
or rollback procedure. O03.2 uses a separately permissioned local service for engineering;
O03.3/SITE01 select and qualify the actual medium and trust holders. Separate local
containers prove component isolation, not survival of the computer or disk.

The trust bundle is installation ID, witness endpoint/CA, pinned signing-key IDs and
the separately retained recovery authority. It is obtained independently of a database
backup. The witness itself must never silently roll back or recreate its authoritative
head from an old backup. If its durable head is lost or currency is indeterminate,
recovery remains closed until independently trusted continuity is established. A
fresh signature on a rolled-back witness is outside the trust assumption, not proof
of safety. Simultaneous loss/compromise of all trusted continuity copies has no automatic
recovery path. No claimed failure domain depends on contacting the lost primary.

Recovery authority has a separate attributable identity and protected credential. It
can fence a writer, inspect the complete journal and authorize a recovery session; it
does not acquire ordinary browsing rights. Institutional approvers remain unassigned.
Engineering rehearsals use named synthetic operators and record that limitation.

## Records, ordering and exact identity

The journal orders PREPARE, COMMIT, ABORT and RECOVERY_BARRIER records using a contiguous
64-bit sequence and previous-record digest. Each policy event separately names its
installation, writer epoch, operation/event UUID, proposed policy revision, previous
policy revision/hash, actor, recorded time, reason, command kind, target IDs, expected
generations, approval references, before/after policy changes and idempotency payload
digest. The distinction matters: an aborted preparation advances journal order without
advancing applied policy revision. IDs/integers/time values have explicit canonical
string encodings; unknown versions, overflow and duplicate keys are rejected.

Policy changes cover organization/principal state, membership lineage, stewardship,
custody/placement, grant actions/scope/expiry, guest sponsorship, identity-binding status,
credential/session invalidation and lifecycle/retention/hold decisions. Credential events
carry stable IDs and digests where required, never bearer secrets or provider tokens.
Scientific manifests, interpretation payloads and accepted actor attribution are not
rewritten by replay. Expiry is evaluated at the recovery clock; it is not postponed
until a worker emits an event. A trustworthy clock is a readiness prerequisite.

Transport uses mutually authenticated HTTPS; application records use versioned canonical
JSON ([RFC 8785](https://www.rfc-editor.org/rfc/rfc8785.html)), SHA-256 digest chaining and
Ed25519 signatures ([RFC 8032](https://www.rfc-editor.org/rfc/rfc8032.html)). Checkpoints
include policy state/hash, applied revision, journal head sequence/digest, unresolved
preparations, installation and epoch. Key rotation is ordered and signed by the old
trusted key; loss/replacement needs the independent recovery authority, never a key
copied from the restored catalog. Algorithm implementation, key custody, crypto vectors,
wire-size bounds and TLS qualification belong to O03.2.

## Commit and acknowledgment protocol

Initially serialize all policy mutations through one installation policy row. A policy
mutation obtains its exclusive row lock; scientific writes, receipt access, delivery
chunk admission and lease renewal use the same current-policy guard. PostgreSQL row
locks coordinate these transactions; an earlier unlocked read under READ COMMITTED is
insufficient ([PostgreSQL 17 locking](https://www.postgresql.org/docs/17/explicit-locking.html)).
The first implementation may hold the row lock across one bounded witness PREPARE
round trip (2-second deadline, no automatic network retry inside the transaction).
That deliberate availability/latency cost must be measured in H04.2.

1. Authenticate and authorize against current principal/policy, check expected generations,
   approvals and payload-bound idempotency. Compute the exact event/after-images. Recheck
   identical retries before returning any existing receipt; changed intent conflicts.
2. PREPARE at the witness using compare-and-set on installation, epoch and both heads.
   It durably stores the complete intent and returns a signed prepare certificate. Only
   one unresolved preparation is allowed per installation. An unavailable witness,
   unknown write outcome or head mismatch prevents local policy commit. Unknown remote
   outcome is resolved by event ID, never assumed absent because of a timeout.
3. In the **same catalog transaction**, change policy/generations, revoke applicable
   credentials/leases, append the local event and a pending durability receipt containing
   the prepare certificate. Commit. Restrictive effects now govern the live primary.
   The event, receipt and mutation cannot be separately committed.
4. Finalize COMMIT at the witness with the identical prepared event and local commit
   attestation. It atomically appends the terminal record, advances applied policy and
   clears the pending slot. Persist its signed durable receipt locally idempotently.
   No new policy mutation may overtake an unresolved event. Data reads use the current
   local policy; a pending restrictive change does not defer local denial.
5. Return completed/durably acknowledged only after verifying the witness COMMIT receipt.
   A lost response is resolved by the same operation ID and payload. A 202/pending status
   says explicitly that policy is locally effective but independent durability is not
   yet acknowledged; it must not look like completed success. If commit outcome itself
   is unknown, show outcome-unknown and resolve it before retrying effects.

An explicit ABORT requires the live authoritative catalog, the same exclusive policy
lock, absence of the local committed event and proof that no transaction can still
commit it. Never abort from a missing row in an old restored backup. Once COMMIT is
recorded, ABORT is refused. A prepared intent whose primary was lost stays unresolved
and keeps recovery closed. Version 1 has no automatic heuristic to decide it. A future
governed conservative quarantine/resolution protocol needs a separate reviewed extension;
this contract accepts unavailable recovery rather than resurrecting access.

## Failure-point table

| Failure point | Live effect / response | Independent record / recovery |
| --- | --- | --- |
| Before PREPARE is durable | No local mutation; no success | No event; prior complete policy can recover |
| PREPARE durable, response lost | No local commit without verified certificate; outcome unknown | Resolve by event ID; unresolved intent blocks restore |
| After PREPARE, before local commit | Local transaction rolls back; no success | ABORT only with fenced live-catalog proof; otherwise closed |
| Local commit fails or its response is lost | Read durable event under lock to distinguish committed from rollback | Never infer abort from network failure; pending intent remains visible |
| Local commit succeeds, witness unavailable | Local restrictions apply; pending, no durable success | Primary loss leaves unresolved intent; restore closed |
| Witness COMMIT durable, response lost | Local retry queries witness; do not duplicate effects | Replay committed event even if client never saw success |
| COMMIT receipt verified, local receipt update fails | Reconcile acknowledgment by event ID | Remote committed state is sufficient; cannot disappear on primary loss |
| Client success followed by total primary loss | No primary needed for policy | Trusted current journal includes every acknowledged event |
| Duplicate/reordered/conflicting event | Same ID+bytes idempotent; changed bytes/head/epoch denied | No branch or missing middle accepted |
| Witness restart/outage or ambiguous witness restore | No new policy success; readiness can close | No authoritative current proof means restore stays closed |
| Restored primary or stale writer | Local authority not admitted until heads/epoch verified | Fence old writer; never permit mixed epochs or mixed-version writers |
| New restrictive event after a backup | Old science may be restored | Replay current restrictions/tombstones before exposing any bytes |

## Currentness, fencing and opening a restore

Stop/fence the old service before recovery. The recovery operator requests a fresh,
single-use random challenge response from the trusted live witness and a durable
RECOVERY_BARRIER that advances the writer epoch. This pauses new preparations and
names the exact terminal head plus unresolved intents. Merely reading a signed file,
comparing wall-clock timestamps or finding a digest beside a backup is insufficient.
The implementation must fence the old service's database/identity admission or prove
it stopped; changing a number in the restored database cannot fence another host.

Every service startup validates epoch and local applied head against the witness before
admitting work. Runtime epoch permits expire within five seconds and a stale/unavailable
witness stops new admission on expiry. After advancing an epoch, recovery waits out
old permits and the bounded transaction/drain interval and independently fences any
old writer before it can reopen. Already admitted bytes cannot be recalled. Each next
delivery chunk (at most 64 KiB) and external-work heartbeat (at most five seconds) still
rechecks current **local** policy; the witness permit never caches dataset authority.

While `recovery_required` remains true:

1. Verify backup identity and scientific bytes independently. Obtain the trusted live
   barrier response bound to this challenge, installation, recovery session and epoch.
2. Verify checkpoint authenticity and its prefix through the exact barrier head:
   contiguous sequences, previous digests, unique event IDs, compatible schemas and
   exact before/after generations. Missing suffix, branch, foreign installation,
   unexpected key or unresolved PREPARE means closed. An old valid signature is not
   currentness. Do not skip an unknown event kind.
3. Replay current policy, retaining tombstones/denials for IDs absent from the older
   scientific backup. Missing referenced scientific data cannot manufacture authority
   or cause a restrictive event to be dropped. Reconstruct organization/principals from
   policy; preserve scientific IDs/attribution. Invalidate restored sessions, credentials
   and worker/delivery leases; reissue credentials only through governed fresh admission.
4. Record a recovery audit containing backup identity, independently verified head,
   epoch, operator/approval and verification results. Atomically set the new local
   policy head and open the gate only after a final witness compare-and-set releases
   this barrier to the new writer. A crash between witness release and local opening
   remains locally closed and retries by the same recovery session; other writers
   cannot acquire that epoch. Do not acknowledge a new policy write during the barrier.

Uncertainty always closes access. A local `capture-policy` snapshot remains useful for
development/reconciliation but cannot establish independent currency or satisfy this
contract. Existing `PostgresRecovery` does not yet implement the protocol. Its legacy
snapshot must not be used to reopen an organization-enabled catalog; F02.2 must fence
that incompatible recovery path until O03.2/O03.4 implement the new one.

## Schema handoff and staged rollout

F02.2 adds one organization policy singleton/revision and transactional organization
events/receipts without a second principal registry. Its schema reserves the installation,
epoch, previous/applied policy head, event ID, payload digest, prepare certificate and
durability state required here. F03.2 joins the authoritative transaction/guard;
O03.2 supplies witness I/O and persisted signatures. No asynchronous outbox-only adapter
may claim this protocol. Until the witness exists, any exercised organization writes
must be explicitly labelled local engineering durability; institutional/independent-
recovery mode is unavailable. Local receipt status cannot be `durable` by default.

Migration uses a closed, single-writer maintenance window and an explicitly named
bootstrap operator. It inventories legacy policy and records its deterministic genesis
and private placement mapping. Enabling independent mode later requires exporting that
complete genesis/current state to the witness under a fenced enrollment procedure;
an empty journal cannot make preexisting restrictions disappear. F02.2 must leave a
testable boundary, not invent an acknowledgment service.

## Executable adversarial review

Implementation refinement, O03.6.1: the standalone journal uses one explicit `ENROLL`
record before these four operational record kinds. It binds the complete checkpoint's
exact bytes/digest, existing applied head/epoch, capability version and writer certificate;
it is not permission to recreate a lost witness or infer currency from an empty one.
The signed envelope uses RFC8785's string/literal subset; numeric tokens are forbidden
and existing catalog payloads are opaque exact bytes. See [the component contract](../policy-witness.md)
for bounds, trust, durability and the remaining catalog integration obligations.
The original acceptance contract is unchanged; O03.6.2/O03.6.3 must prove complete
enrollment and live catalog fencing before O03.6 can close.

`python3 scripts/check-policy-protocol.py --output NEW_DIRECTORY` checks all interruption
positions over short grant/revoke/disable/steward-transfer sequences, replay of durable
changes from old scientific backups, unresolved preparations, exact retry identity,
foreign/stale/tampered proofs and incomplete chains. It includes a counterexample to
the rejected local-commit-then-asynchronous-replication protocol. The model uses HMAC
only as an executable authenticity stand-in; it does not test production Ed25519/TLS,
PostgreSQL isolation, physical fsync or distributed consensus. The source-bound O03.1
receipt records the review and those limits before F02.2 starts. O03.2 must repeat the
failure matrix with real independent connections/stores; H00 tests the integrated build.

Qualification result, O03.6.3: complete restrictive enrollment and live catalog fencing
now pass the [integrated process matrix](../policy-process-qualification.md), including
actual packaged HTTP-service denial and restart refusal after an independent barrier.
All six original O03.6 criteria pass the [parent review](../evidence/institutional-pilot/O03.6/receipt.json).
The qualification also exposed a server-drain gap in JDBC-only cancellation; the
implementation now enforces the remaining budget with PostgreSQL 17 transaction
timeouts in admission, finalization and live rollback resolution. This closes
current-authority durability only. H03.1 backup automation, O03.7/O03.8
restore and source-isolation qualification, and institutional failure domains remain open.
