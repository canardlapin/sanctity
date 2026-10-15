# Standalone policy witness

O03.6.1 supplies the independently running journal component for
[ADR 004](decisions/004-policy-durability.md). O03.6.2 adds
[catalog enrollment, synchronous acknowledgements and runtime admission](policy-catalog.md).
Unenrolled and historical receipts remain `LocalOnly`; organization-aware restore
remains blocked. O03.6.3 supplies the passing
[integrated process qualification](policy-process-qualification.md). All six original
O03.6 acceptance criteria passed the [unchanged parent review](evidence/institutional-pilot/O03.6/receipt.json).
H03.1 backup automation is next; O03.7/O03.8 restore work remains open.

## Contract and wire format

`PolicyCapabilities.inventory` is the versioned, hashed capability agreement. It names
the nineteen current mutation kinds in [the transaction inventory](policy-transactions.md).
Identity, sponsorship, sessions, lifecycle, retention and holds are unsupported producers.
An unknown capability hash, event kind or wire version is refused. The existing
eligibility/lifecycle columns are inventoried by O03.6.2; unsupported nondefault
values close enrollment. This component does not invent defaults for them.

The outer wire format uses the string/literal subset of
[RFC 8785](https://www.rfc-editor.org/rfc/rfc8785.html): recursively sorted UTF-16 property
names, prescribed escaping, UTF-8 and no Unicode normalization. Numeric JSON tokens
are forbidden. Integers use canonical decimal strings within signed 64-bit bounds;
UUIDs, SHA-256 values, timestamps and unpadded base64url have one accepted spelling.
Duplicate keys, malformed UTF-8, lone surrogates, unknown fields, nesting beyond 48
and messages beyond 8 MiB fail closed. This is a deliberately restricted profile,
not a general JSON floating-point canonicalizer.
Event payloads are limited to 1 MiB and enrollment checkpoint bytes to 4 MiB,
leaving room for complete signed acknowledgments (including the pending event and
its journal record). Larger enrollment needs a separately qualified chunked/checkpoint
extension; it must never silently omit authority to fit the limit.

Exact `policy-local-v2` event bytes are carried as base64url, with their original
digest and applied hash. The witness checks the embedded event's installation,
epoch, operation, revisions, previous head and kind against the signed envelope;
it verifies the existing digest framing. It never reserializes those bytes into a
new historical identity. A checkpoint similarly carries opaque exact bytes and
their digest. **Completeness and semantics of the enrollment checkpoint are the
catalog adapter's responsibility**, implemented by O03.6.2. A synthetic checkpoint
in a component test is not qualified catalog enrollment.

Records use SHA-256 chaining and JDK Ed25519, checked against
[RFC 8032](https://www.rfc-editor.org/rfc/rfc8032.html) vectors. A signature binds the
protocol version, pinned signing-key ID and complete body. Transport is TLS 1.3 with
mutual certificate authentication; the witness additionally pins distinct writer
and recovery certificate fingerprints. A fresh UUID challenge and request digest
bind every signed response to its caller. The client makes one connection and one
POST, with a two-second deadline covering connect, handshake, upload and response.
It has no redirects, reconnects or automatic retries. Failure after sending a mutation
means outcome unknown; resolve the original operation, never infer rollback.

## Journal and failure behavior

The first record is `ENROLL`, an explicit initialization checkpoint containing the
preexisting applied revision/hash, writer epoch and permitted writer certificate.
This concrete wire representation extends ADR 004's four operational record kinds
with a one-time enrollment record. It does not advance or replace prior catalog policy.
An empty store is unenrolled and not ready. Re-enrollment, key replacement and
implicit initialization are refused.

Subsequent records are `PREPARE`, `COMMIT`, `ABORT` and `RECOVERY_BARRIER`:

- PREPARE compares both journal and policy heads, retains the entire exact event,
  and occupies the single pending slot. No different event can overtake it.
- COMMIT advances applied policy; ABORT advances only journal order. The writer
  certificate is trusted to attest live catalog commit/absence. O03.6.2 makes
  that assertion only under the live exclusive catalog lock; the recovery identity
  cannot invoke either terminal operation.
- Exact operation/payload retries return the existing record, including a terminal
  COMMIT reached after a lost response. Changed payload or conflicting terminal
  outcome is rejected.
- A recovery barrier advances the epoch, clears the permitted writer and preserves
  any unresolved preparation. It fences subsequent witness requests from that writer.
  This component has no release/reopen endpoint. O03.6.2 bounds admitted SQL work
  and enforces fresh witness admission; O03.6.3 qualifies the combined process-failure
  and old-service fencing matrix.

Each signed record is written to a new file, forced, atomically renamed, and its
directory forced. The signed head marker is then replaced and forced before success.
A process-wide file lock excludes a second writer. Startup verifies signatures,
installation, every transition and the full contiguous chain against the head marker.
A valid durable suffix ahead of the marker can replay forward after a crash. A missing
head, missing tail, gap, branch, unknown file, invalid signature or incompatible key
fails startup. There is no automatic truncation, reinitialization or rollback.
An ambiguous filesystem failure poisons the live instance until a verified restart.

The store assumes its trusted continuity has not been rolled back together with its
head marker and signing authority. A signature cannot detect rollback of every trusted
copy. There is no automatic recovery in that situation. Key rotation is unsupported;
an explicit ordered, old-key-signed upgrade must be implemented and qualified before
changing the pin. Never replace a pin using information from a restored catalog.

## Operator interface

The service distribution includes explicit standalone commands:

```text
sanctity policy-witness init /absolute/protected/witness.json
sanctity policy-witness inspect /absolute/protected/witness.json
sanctity policy-witness serve /absolute/protected/witness.json
```

The configuration is JSON with exactly these string-valued fields:

```json
{
  "installation": "11111111-1111-4111-8111-111111111111",
  "store": "/absolute/witness-data/journal",
  "identityStore": "/absolute/witness-trust/server.p12",
  "identityPasswordFile": "/absolute/witness-trust/identity-password",
  "trustStore": "/absolute/witness-trust/client-ca.p12",
  "trustPasswordFile": "/absolute/witness-trust/trust-password",
  "signingPrivate": "/absolute/witness-trust/signing-private.der",
  "signingPublic": "/absolute/witness-trust/signing-public.der",
  "writerFingerprint": "REPLACE_WITH_SHA256_OF_WRITER_CERTIFICATE_DER",
  "recoveryFingerprint": "REPLACE_WITH_SHA256_OF_RECOVERY_CERTIFICATE_DER",
  "port": "9443"
}
```

This is a template, not an enrollment procedure. Input paths must be absolute,
canonical, owned by the process account, nonsymlink regular files with mode 0600
inside mode-0700 directories. The store parent must already exist with mode 0700;
`init` requires a new store directory. Trust/configuration inputs must be outside
the journal directory. Signing keys are PKCS8/X509 Ed25519 DER; TLS identities and
CA anchors use PKCS12. Keep recovery private credentials separate from writer
credentials and independently of primary catalog/object backups. The service binds
only to `127.0.0.1`; TLS remains mandatory on loopback. The server certificate must
match the client's hostname.

`inspect` verifies the complete journal before printing version, installation,
capability hash, sequence/epoch, pending/barrier flags and component readiness.
It prints no event bodies, actor names, TLS subjects, keys, credentials or paths.
Component readiness does not assert catalog readiness. Inspection requires exclusive
store access; stop the witness first. Protected protocol CURRENT provides live state
to authenticated writer/recovery clients.

This engineering store uses the current operating system's file-force/atomic-rename
semantics. Tests prove separate processes, authenticated connections, restrictive file
modes and restart behavior. They do not prove separate operating-system accounts,
physical power-loss behavior, separate disks, or host-loss durability. A production
witness needs independently administered credentials, account/storage isolation and
the independently qualified failure domain from O03.3/SITE01. Full-history replay is
an initial correctness implementation; bounded checkpointing and scale remain H04.

## Qualification

```sh
sbt 'postgres/test' 'service/test'
```

`PolicyWireSuite` contains RFC signature/canonicalization vectors and malformed-input
tests. `PolicyJournalSuite` checks state transitions, exact retries, store integrity,
permission checks and ambiguous disk writes. `PolicyTransportSuite` generates disposable
synthetic TLS identities and launches a separate JVM with its own store. It kills that
process after record-force and head-force for each operational record kind, before
the client receives a response, then checks the recovered outcome. No institutional
identity or primary catalog is involved. The test-only process entry point exposes
fault hooks; the packaged service command does not.

O03.6.1 evidence belongs in `docs/evidence/institutional-pilot/O03.6.1/receipt.json`.
The parent receipt remains absent until catalog integration and the complete independent
primary/witness fault matrix pass. Existing F03.2 evidence is historical and retained;
it is not represented as an integrated witness test.

Catalog integration and its maintenance procedure are documented in
[catalog enrollment and acknowledgement](policy-catalog.md). Organization restore
remains closed pending integrated qualification and replay.
