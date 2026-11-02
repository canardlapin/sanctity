# Closed organization recovery

The organization recovery commands consume `sanctity-backup-v2` bundles created by
the verified backup path (repository guide: `docs/backup-bundle.md`). They restore retained science and
reconstruct current authority from the independently retained witness. A backup's
old permissions never authorize reopening.

## Inputs and sequence

Use Java 21, PostgreSQL 17, an operator database connection to a **fresh database**,
a new storage directory, the exact source artifact ZIP and the verified bundle.
`pg_restore` and `psql` must be available; `SANCTITY_PG_RESTORE` and `SANCTITY_PSQL`
can name explicit executables. Runtime database logins cannot perform recovery.
Keep recovery and new writer trust files outside backup and storage directories.
They retain the protected-file requirements of normal witness clients.

The supported source packages are the exact running schema-14 package, or the
qualified schema-13 H03.1 predecessor with ZIP SHA-256
`8c5f11f521e6e53d98f200758a909959243ff7606a3da880433715a3f6ea050d`.
Matching version labels alone do not establish compatibility. The consumer checks
embedded dependencies, source inventory, bundle artifact identity, every migration
checksum and the complete enrolled capability profile.

Choose a new recovery session UUID and record the responsible operator principal
UUID. Provision a new writer certificate, using an identity that has never served
this installation. Keep both UUIDs, the new storage path and trust inputs unchanged
when retrying the same session. Upgrade the independent witness to this qualified
package against its existing journal before starting. The release record requires
the new witness code; preserve its signing identity and protected store.

```text
sanctity recovery preview BUNDLE SOURCE_ARTIFACT NEW_STORAGE SESSION OPERATOR RECOVERY_CONFIG NEW_WRITER_CONFIG
sanctity recovery verify BUNDLE SOURCE_ARTIFACT NEW_STORAGE SESSION OPERATOR RECOVERY_CONFIG NEW_WRITER_CONFIG
sanctity recovery status SESSION RECOVERY_CONFIG NEW_WRITER_CONFIG
```

Preview authenticates the live witness, checkpoint and complete history without
changing the target. Verify copies and hashes every required object, then imports
the catalog, applies any supported migration, closes access and records session
ownership in **one database transaction**. No imported catalog is visible in an
open state. Existing unknown schemas or storage directories are refused.

Verify obtains a live recovery barrier, permanently fences the old writer and
waits a fresh five-second drain. It reads every signed record from enrollment to
that barrier, checks contiguous sequence/hash links and generations, verifies the
captured backup head as a prefix, and checks the same live head again. Missing,
truncated, reordered, foreign, unsupported or changed history is refused. An
unresolved PREPARE must be resolved on the original writer before recovery starts;
the recovery identity cannot invent its outcome.

After verification, update the independent witness's protected configuration to
admit the new writer certificate and restart that witness against its existing
journal. This is an operator trust change; the restore command never rewrites
certificates or signing keys. Then run:

```text
sanctity recovery reconcile SESSION STORAGE RECOVERY_CONFIG NEW_WRITER_CONFIG
sanctity recovery status SESSION RECOVERY_CONFIG NEW_WRITER_CONFIG
```

Reconcile applies the verified current policy while access remains closed. It
records an attributable receipt, obtains a signed release binding that exact
receipt to the new writer, and requires a fresh CURRENT response authenticated as
that writer before atomically opening the catalog. Provision a fresh least
privilege runtime database login and issue new API credentials through the normal
governed operator path. Start the service using the new writer configuration.

## Durable phases and interrupted work

| Phase | Durable state and safe next action |
| --- | --- |
| Restored | Catalog and objects are restored; access is closed. Retry verify. |
| Verified | Fresh barrier and replay have been verified. Retry reconcile. |
| Applied | Current policy and the exact receipt/release intent are durable; access is closed. Retry reconcile. |
| Released | Witness release certificate is durable; access remains closed until the new writer is admitted. Retry reconcile. |
| Opened | The same completed session is returned on retry. Check live readiness before starting work. |

A lost release response is retried with the identical operation and command. It
cannot create a second release or switch the receipt. Once fenced, an old writer
identity cannot be reused in a later recovery. Failure to authenticate the new
writer leaves the target closed even if the witness already recorded release.
`status` reports both the durable phase and whether the current witness agrees
with the local epoch/head/writer; an old successful receipt is not live readiness.

All restored API credentials are invalidated, including otherwise valid owner
credentials. Credential IDs issued after the backup are retained as revoked
identities, preventing an idempotent issue operation from minting them again.
Import and delivery leases are cleared. Active runs are marked Failed with a
recovery explanation while their specifications and attribution remain intact.
Old runtime database privileges are retired on the target.

Replay preserves retained dataset/release IDs, manifests, interpretations and
scientific actor attribution. Current principal state, appointments, stewardship,
placement, sharing and expirations come from verified authority history. Policy
for a dataset or release absent from the backup is retained in restrictive
tombstones; it does not fabricate scientific rows. Database triggers prohibit
reuse of those missing scientific IDs.

## Limits and evidence

Replay is bounded to 10,000 signed records, 128 MiB of signed transcript and five
minutes. Object copy and SQL import each have five-minute deadlines; SQL streaming
also has the backup format's 64 GiB limit. Operational failures retain a closed
session and private staging material for diagnosis. Diagnostics omit credentials
and checkpoint contents. Do not delete the witness journal to make an old backup
appear current.

The legacy recovery commands continue to refuse organization catalogs. Current
capabilities cover local principals, API credentials and available scientific
objects; institutional identity, browser sessions and future lifecycle states
require their own capability inventory and recovery qualification. Unknown
enabled capabilities fail closed.

The repository receipt `docs/evidence/institutional-pilot/O03.7/receipt.json` records packaged
closed-restore checks, real process kills across apply/release/opening, restricted
runtime probes, lease invalidation and fresh pending-work admission. The
repository receipt `docs/evidence/institutional-pilot/O03.8/receipt.json` records the two-lab
source-loss rehearsal, independent positive/negative access oracles, exact retained
exports and a next-chunk denial after revocation. Both use synthetic data, Java 21
and PostgreSQL 17. The witness, store and reader have distinct OS identities and
volumes on one host. These results establish local engineering behavior; off-host
and institutional disaster recovery acceptance remain open.

For a missing witness, keep the target closed and restore the independently
controlled witness service; retry the same session after fresh authentication
works. For an unsupported profile, stop and qualify its producers and recovery
semantics before enabling it. For an interrupted command, inspect status and retry
the phase shown above. An unresolved source PREPARE requires the original writer's
verified outcome; neither a guessed ABORT nor a manually changed gate is recovery.
