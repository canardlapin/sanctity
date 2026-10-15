# Historical compatibility fixture

H06.2 preserves the pre-hardening Java distribution and exact source outside `target/`:
`local-artifacts/compatibility/pre-core-20261014/`. The old ZIP SHA-256 is
`2ae3efa92d344520dc9abae1ce1d6c879998a187cebe1897ce096b3ced6b432e`.
The accepted populated fixture is **corpus-v2**. Its manifest and oracle are copied
into [H06.2 evidence](evidence/institutional-pilot/H06.2/receipt.json); the private
catalog dump, managed bytes and synthetic revoked credential remain local. Ordinary
build cleanup cannot remove this directory. Preserve it with release evidence.

The old binary created two accepted releases with different descriptions and stored
interpretations; fixed-R1, dataset-metadata and revoked grants; a revoked credential;
acceptance receipts, audit and pending outbox events. A test-only trigger paused a
second dataset's import after two files were durably acquired. The process was killed,
its transaction terminated, and the trigger removed before taking the snapshot. The
operation has no candidate/receipt and can resume after its real lease expires.

The oracle records old release/content/interpretation identities, serialized public
representations, projected historical-table hashes, migration checksums and explicit
allow/deny answers. File hashes come from authored fixture bytes. These answers are
frozen **before** the candidate sees the database. The verifier never rewrites them.
Fresh positive credentials avoid expiring the fixture's successful access assertions;
the original revoked row is compared before credential writes, and its retained token
must remain denied. After that token's original expiry, the row comparison remains
the specific evidence of preserved revocation.

```sh
python3 scripts/check-upgrade-compatibility.py verify \
  --artifact local-artifacts/compatibility/pre-core-20261014/sanctity-0.1.0-SNAPSHOT.zip \
  --fixture local-artifacts/compatibility/pre-core-20261014/corpus-v2 \
  --output target/compatibility-old-NEW

python3 scripts/check-upgrade-compatibility.py verify --modern --organization \
  --artifact target/service-release-f022-20261014/sanctity-0.1.0-SNAPSHOT.zip \
  --fixture local-artifacts/compatibility/pre-core-20261014/corpus-v2 \
  --output target/compatibility-candidate-NEW
```

Each invocation creates a labelled disposable pinned PostgreSQL 17.11 container and
private temporary service root; it accepts no live database URL. The old server uses
its historical operator profile. Modern verification explicitly migrates, grants the
runtime profile, and serves with a separate login. With `--organization`, it explicitly
previews and bootstraps V008 under a distinct operator, checks idempotent genesis,
private placement and explicit curator stewardship, and refuses legacy policy capture.
The original oracle and all fourteen historical table projections remain unchanged.
V008 qualification terminates the migration backend after its first ALTER, verifies
transactional rollback against the old oracle, then retries explicitly. It also runs
the retained old binary against V008 and verifies refusal. This startup/version check
does not establish fencing of an already-running old process. Server processes stop before any
migration. Restores compare history before adding verification records, retrieve all
historical files, test access, replay completed work without moving a newer head, and
resume interrupted work with one acceptance across retries. Deliberate checksum drift
and a future version-999 record must cause migration refusal without repair.

The source directory is retained at its recorded canonical path because native import
observations include filesystem identity. This version's pending-source replay is a
same-host fixture, not a portable source-loss restore. Moving the fixture requires a
new, separately qualified fixture version; never patch source labels/observations in
the frozen dump. Accepted-history checks do not read that source; operation resumption
explicitly does. O03 owns source-independent recovery.

`freeze` refuses an existing fixture and accepts only the pinned old binary. For a
new fixture version, retain the old binary/source and run it into a new output path;
do not regenerate expected answers with an upgraded binary. The first draft corpus
is retained but **not accepted**: its denial oracle incorrectly expected 403 instead
of the API's non-disclosing 404. The harness also now waits for TCP readiness, avoiding
PostgreSQL's temporary initialization socket. These were harness defects; their failed
logs and the corrected runs are retained.

The current package passed the same checks with unchanged SQL V001–V007 and a changed
scientific build fingerprint. Historical interpretation payloads remained exact.
[O01.3 evidence](evidence/institutional-pilot/O01.3/receipt.json) separately includes
actual migration-backend termination, transactional DDL rollback and explicit retry.
F02.2 reruns those comparisons on the actual V008 organization migration, recorded in
[its receipt](evidence/institutional-pilot/F02.2/receipt.json). O01.4 must qualify the
complete release procedure and mixed-version writers; H00 repeats comparisons on the
final candidate. These receipts do not qualify a future migration, hosted CI,
mixed-version writers, in-place downgrade or physical disaster recovery.
