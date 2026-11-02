# Verified organization backups

`sanctity backup capture STORAGE NEW_BUNDLE RUNNING_ARTIFACT_ZIP` creates a
`sanctity-backup-v2` bundle from a PostgreSQL 17 organization catalog enrolled with
an independent policy witness. Use the operator database connection and protected
`SANCTITY_POLICY_CONFIG`. The supplied artifact must contain the exact jars of the
running packaged service; a source-classpath invocation cannot claim an artifact
identity. Java 21 is the qualified runtime.

The operator needs `pg_dump` from PostgreSQL 17 on PATH. `SANCTITY_PG_DUMP` can select
an explicit executable. `SANCTITY_BACKUP_DEADLINE_SECONDS` is bounded to 10–3600
seconds (default 300). The target must be new and outside source storage; its parent
must already exist. Keep writer trust outside storage and the capture parent.

## Capture and consumer contract

Capture exports one repeatable-read PostgreSQL snapshot. It reads the schema
migration checksums, enrollment certificate, local policy head and required object
inventory in that snapshot, then authenticates a fresh CURRENT response from the
independent witness. The two policy heads must agree, with no preparation or
recovery barrier. `pg_dump --snapshot` uses that same snapshot. Scientific and
policy writes can continue; changes after the captured head belong to the
independently retained recovery suffix. A race before CURRENT causes refusal.

Required objects include registered blobs and acquired import files. Each immutable
object is copied in 64 KiB chunks, checking its recorded size and SHA-256. The
format bounds the inventory to 100,000 objects and the total files to 64 GiB.
Current storage does not garbage collect objects; any future collector must retain
objects referenced by an active backup snapshot.

The root contains `bundle.json`, `catalog.dump`, `policy-link.json` and exactly the
listed `storage/objects/HH/SHA256` files. The manifest has these closed fields:

| Field | Meaning |
| --- | --- |
| `version` | Exactly `sanctity-backup-v2` |
| `createdAt` | Capture completion timestamp; never evidence of policy currency |
| `artifact` | ZIP, package manifest and source inventory SHA-256, package version |
| `schema`, `migrations`, `postgresVersionNumber` | Exact catalog and migration identities |
| `installation`, `capabilities` | Installation UUID and complete supported authority inventory |
| `policyHead` | Witness head authenticated during capture |
| `objects` | Required object digest/size records |
| `files` | Relative filename to exact digest/size record for every payload file |

`policy-link.json` contains the signed enrollment certificate (including its
checkpoint), captured head and an explicit statement that live replay is required
before reopening. Trust anchors, witness signing keys, writer credentials and
witness storage are never backup payloads. The bundle identifier is SHA-256 of the
canonical manifest (UTF-8, sorted object keys, compact JSON).

All payload files and directories are forced before atomic rename publishes a
local bundle. Failed attempts remain under `.pending-*` and never acquire the
requested final name. `sanctity backup verify BUNDLE` checks every byte, the exact
object inventory, missing/extra files and path aliases. It does **not** grant
permission to open a restored catalog. The capture result reports verified status,
bundle identity, byte/object counts, elapsed time, enrollment checkpoint digest
and captured policy-head digest.

The O03.7 consumer must use this exact version, verify the artifact/schema/profile,
restore into a fresh closed target, authenticate the checkpoint through independent
trust, join contiguous history to a fresh live barrier/head, and only then reconcile
and release. A valid backup hash or old signed head cannot certify current access.

## Isolated store

`scripts/backup-store.py` requires Python 3.11 or newer. Run the service under a
separate OS identity with its own volume and no source-storage mount. A protected
writer token permits staging and publication; a distinct protected reader token
permits inventory and recovery downloads. The writer cannot read backups, and the
reader cannot alter them. Token files require mode 0600 or stricter; the service
creates private files with umask 077.

```
python3 scripts/backup-store.py serve --root /backup-store \
  --writer-token /protected/writer --reader-token /protected/reader \
  --port 8443 --certificate /protected/server.crt --private-key /protected/server.key
python3 scripts/backup-store.py push --bundle /captures/backup \
  --url https://backup.example:8443 --token-file /protected/writer
python3 scripts/backup-store.py inventory \
  --url https://backup.example:8443 --token-file /protected/reader
python3 scripts/backup-store.py pull --bundle-id MANIFEST_SHA256 --output /restore/bundle \
  --url https://backup.example:8443 --token-file /protected/reader
```

Clients require HTTPS except with explicit `--allow-insecure-test`. Qualification
uses that flag only on a disposable local Docker network; it is not deployed TLS
or physical failure-domain qualification. The store limits concurrent requests to
8, pending/published bundle directories to 128, total stored bytes to `--max-bytes`
(default 64 GiB), and each upload to 300 seconds with a 30-second socket timeout.
Clients retry transport failures at most three times. Partial uploads remain
unpublished. A retry can replace corrupted unpublished staging; published names
are immutable. Publication verifies the complete inventory before atomically
moving it into `bundles/`. Downloads verify all bytes before publishing a local
recovery copy. Repeated publication of the same verified manifest is idempotent.

Inventory reports each successful publication, age, bytes, objects, captured
policy head and policy-link file digest. `verifiedAtPublication` describes the publication check; it does not
assert a new full disk scrub on each inventory request. A recovery download checks
integrity again. Retain failed staging for diagnosis; remove it only through the
operator's explicit retention procedure. Capacity exhaustion fails closed.

For bounded scheduling, write a JSON array containing the capture command with
one whole `{output}` argument, then run:

```
python3 scripts/backup-store.py schedule --capture-command /protected/capture.json \
  --staging-root /captures --interval 3600 --count 24 \
  --url https://backup.example:8443 --token-file /protected/writer
```

The schedule inherits the operator's protected environment, creates a fresh local
capture for each run and publishes it after verification. Each capture has a
3600-second subprocess bound; interval and count are bounded, and a failure stops
the schedule. Use an external service supervisor for continuing schedules.

## Qualification and limits

`scripts/check-isolated-backup.py ARTIFACT --output NEW_EVIDENCE_DIRECTORY` exercises
packaged capture against PostgreSQL 17 and a TLS policy witness, concurrent policy
and release publication, a closed dump probe, interrupted capture, missing/corrupt
objects, a separate store UID/volume, independent credential denials, interrupted
copy, restart, scheduling and tampered recovery downloads. The recovery client has
only its own volume and reader credential; neither source storage nor source DB
credentials is mounted into it or the store.

All data are synthetic. Separate containers on one host do not establish off-host
durability, institutional media acceptance, or disaster recovery. H03.1 establishes
backup engineering only; O03.7/O03.8 and O03.3/SITE01 retain their separate gates.
