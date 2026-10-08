# Sanctity

Sanctity is a BIDS-native neuroimaging repository with a pure Scala kernel, an in-memory reference catalog, PostgreSQL persistence, and immutable managed bytes.

A **release** fixes content, scientific interpretation, and admission evidence. A **frozen selection** fixes query inputs and membership. A **prepared scientific view** becomes an ordinary assessed release with its own custody and grants. Imports, corrections, and views all use the same preparation and atomic acceptance protocol.

[Product requirements](docs/prd.md), [implementation plan and evidence](docs/implementation-plan.md), [design baseline](docs/design-baseline.md), [decision record](docs/decisions-and-evidence.md), and [bids4s integration](docs/bids4s-integration.md) describe the broader product and remaining qualification.

Current work prioritizes the [scientific core](docs/scientific-core.md): explicit acquisition relationships, companion files, image/table consistency and coherent BIDS materialization.

## Implemented slices

| Area | Working behavior |
| --- | --- |
| Authority | Durable principals, dataset and fixed-release grants, independent metadata/content/propose/accept powers, curator-controlled administration, revocation and transactional checks |
| Scientific relationships | Recorded B0 estimation groups and IntendedFor links, field-origin evidence, relationship correction impact, historical identity and bounded closure |
| Scientific queries | Explicit artifact/participant/session correlation; SQL and reference interpreters; lexical participant values; versioned query facts and durable selections |
| Scientific views | Selected MRI acquisitions, projected participant columns, flattened approved effective metadata, projection evidence, ordinary release acceptance and sharing |
| Durable operations | Inventoried imports, verified file checkpoints, renewable worker leases, fenced publication, completion recovery, independent leased outbox targets |
| Recovery | Catalog/blob backup, transactional closed restore gate, integrity verification, reconciliation against separately supplied current policy |
| DICOM | Pinned real converter, bounded/cancellable process lifetime, retained source/diagnostic evidence, explicit output mapping and exclusions |
| XNAT | Authenticated API streams, explicit resource inventories, experiment pagination, checksums, source-change checks, matching resource ZIP import |
| HTTP and operator CLI | Revocable principal credentials, release/metadata inspection, correction and acceptance, fixed-release grants, frozen queries, guarded file streams, resumable local import |

These are bounded implementations with executable evidence. Institutional identity integration, full conformance, representative scientific qualification, production migration and performance remain open.

## Build and native demonstration

Prerequisites: Java 21, sbt, and the sibling `../bids4s` checkout with the metadata-resolution work described in [integration status](docs/bids4s-integration.md). The canonical library is [canardlapin/bids4s](https://github.com/canardlapin/bids4s). A direct sbt project dependency avoids a publication step.

```sh
sbt check
sbt 'app/run'
```

The native demonstration imports two synthetic BOLD acquisitions, explains inheritance, changes shared repetition time from 1.5 to 1.8, preserves a local override, accepts the correction and exports both releases. It prints a fresh workspace containing the source, immutable objects, exports and report.

An explicit destination must be new:

```sh
sbt 'app/run /absolute/path/new-demo-workspace'
```

Use `-Dsanctity.bids4s.path=/absolute/path/bids4s` for another library location.

The pinned application baseline is Scala 3.3.8, Cats Effect 3.5.7, FS2 3.11.0, http4s 0.23.30, Circe 0.14.10, Doobie 1.0.0-RC6, PostgreSQL JDBC 42.7.14 and MUnit 1.3.0. PostgreSQL checks use version 17.11.

## Modules and public contracts

| Module | Responsibility |
| --- | --- |
| `kernel` | Identities, manifests, evidence, admission, authority rules, scoped query algebra, frozen selections, projection and lease values |
| `application` | Capability interfaces, reference catalog, native lifecycle, view preparation, durable import coordination |
| `bids` | bids4s interpretation, inherited metadata, strict participant tables, supported MRI view compiler |
| `objects` | Immutable filesystem bytes, verification, inventory, isolated exports, bounded process resources |
| `postgres` | Incremental checked migrations, catalog transactions, SQL queries, grants, operations, delivery leases, recovery |
| `import-dicom` | Pinned conversion, explicit versioned mapping, output accounting, source evidence |
| `import-xnat` | Qualified resource references, HTTP acquisition, pagination, declared ZIP export import |
| `http` | Authenticated workflow routes, strict request grammar, explicit public JSON representations |
| `app` | Assembly, API server, operator/import/credential/recovery CLI, demonstrations and shared tests |
| `integration` | Disposable PostgreSQL transaction, worker, recovery-gate and lifecycle tests |

Start at [NativeRepository](modules/application/src/main/scala/sanctity/application/NativeRepository.scala), [Catalog](modules/application/src/main/scala/sanctity/application/Capabilities.scala), [ScientificViews](modules/application/src/main/scala/sanctity/application/ScientificViews.scala), and [DurableImports](modules/application/src/main/scala/sanctity/application/DurableImports.scala).

The reference and PostgreSQL catalogs share pure [Admission](modules/kernel/src/main/scala/sanctity/kernel/Admission.scala) and [Authority](modules/kernel/src/main/scala/sanctity/kernel/Authority.scala) rules. Application ports and access contexts are trusted internal capabilities. The [authenticated HTTP adapter](modules/http/src/main/scala/sanctity/http/RepositoryHttp.scala) obtains contexts from verified credentials and exposes bounded workflows with explicit public representations. The reference catalog deliberately refuses credential-bearing contexts; the PostgreSQL adapter rechecks their liveness transactionally.

## Scientific relationships

[bids4s resolves explicit MRI declarations](../bids4s/modules/core/shared/src/main/scala/bids4s/BidsFieldmaps.scala). Sanctity binds the resulting grouped relationships, their field-origin evidence and resolver version into immutable interpretation identity. A metadata correction can preview changed acquisition relationships even when the affected image's own metadata is unchanged.

`Interpretation.relationshipClosure` follows recorded relationships and returns associated artifacts plus metadata documents. This is a bounded declaration closure; complete companion resolution and qualified pipeline bundles are later scientific slices. Historical interpretations without this graph retain their identities and explicitly lack this coverage. See the [scientific-core plan and proof](docs/scientific-core.md).

## Queries and sharing

[ScientificQuery](modules/kernel/src/main/scala/sanctity/kernel/Query.scala) preserves correlation explicitly. For example:

```scala
ScientificQuery(
  participants = ParticipantPredicate.HasSession(Vector(
    ArtifactPredicate.Suffix("T1w"),
    ArtifactPredicate.All(Vector(
      ArtifactPredicate.Suffix("bold"),
      ArtifactPredicate.Entity("task", "rest")
    ))
  )),
  artifacts = ArtifactPredicate.MetadataBelow("RepetitionTime", BigDecimal("2"))
)
```

The participant must have both acquisition kinds in one session. Matching output artifacts must also satisfy the timing predicate. Separate participants or sessions cannot satisfy the conjunction accidentally.

Call `catalog.select(context, pinnedReleases, query)` to persist a frozen selection. `catalog.selection(context, id)` rechecks every contributing release; revoked access produces a denial instead of smaller membership. Pages use stable stored ordering. Entity equality is lexical, including zero padding. Numeric participant comparisons accept bounded decimal lexical values; values such as `89+` remain preserved and do not become guessed numbers.

Views use `ScientificViews.prepare`, followed by ordinary `repository.accept`. The destination is a separate empty dataset. The compiler retains selected image bytes, generates local JSON from approved effective fields, projects `participants.tsv`, and generates a neutral description. Source disclosure authority is checked during preparation and acceptance, including retries. The resulting release can receive a fixed-release grant without granting access to the source.

The current view profile supports T1w/T2w and single-echo resting BOLD NIfTI. It requires recorded relationship coverage and refuses to omit explicit associated inputs, including links from another session. It blocks unsupported associations, fieldmaps, events/physiology, SBRef dependencies and file collections. Required BOLD task/timing fields cannot be masked. Disclosure profiles require curator approval; scientific and de-identification qualification remain separate.

Grant actions are `ReadMetadata`, `ReadContent`, `Propose`, and `Accept`. The dataset curator administers grants. Dataset grants explicitly cover future releases; fixed-release grants permit reading only. Export reauthorization occurs before each chunk of at most 64 KiB. Expiring grants, delegation, group membership, institutional login and arbitrary source-evidence delivery are future work.

## Run the PostgreSQL slice

With Docker running and Python 3.11 or later:

```sh
scripts/check-postgres.sh
```

The runner creates a private loopback PostgreSQL container, runs ordinary and database tests, demonstrates crash/retry recovery, then exercises backup/restore after an intervening grant revocation. It restores into a fresh database and verifies that the closed recovery gate prevents access before current policy is applied. The container and its volume are removed afterward. Logs, backups, policy receipts and exports remain under the printed `target/postgres-check.*/` directory.

Database tests are separate from `sbt check`; they fail without the explicit disposable-test environment.

For an existing development database:

| Variable | Meaning |
| --- | --- |
| `SANCTITY_JDBC_URL` | PostgreSQL JDBC URL |
| `SANCTITY_DB_USER` | Database user |
| `SANCTITY_DB_PASSWORD` | Password supplied through the environment |
| `SANCTITY_DB_SCHEMA` | Private schema, default `sanctity` |

The user must be able to manage that schema. Migrations apply in order and reject checksum drift or unknown versions.

```sh
sbt 'app/runMain sanctity.app.PostgresDemo stage /absolute/path/new-workspace'
sbt 'app/runMain sanctity.app.PostgresDemo resume /absolute/path/new-workspace'
```

The checkpoint records demonstration identities and references. Scientific records, preparation and receipts live in PostgreSQL.

## Run the authenticated API

The [API runbook](docs/http-api.md) covers provisioning, resumable imports, credential rotation, curation, queries and sharing. A machine-readable [OpenAPI contract](docs/http-api.openapi.json) describes the twelve routes.

```sh
export SANCTITY_STORAGE=/absolute/path/managed-storage
export SANCTITY_HTTP_PORT=8080
sbt 'app/runMain sanctity.app.RepositoryCli issue-credential PRINCIPAL_UUID 3600 curator-client /absolute/path/new-credential.json'
sbt 'app/runMain sanctity.app.ApiServer'
```

Use an existing provisioned principal, with PostgreSQL variables set as above. The CLI creates the credential file with mode `0600` and never prints its token. The server binds to loopback; external transport requires a TLS reverse proxy. Tokens authenticate stable principals, and current grants govern every action. Private source evidence stays out of public release responses. Institutional login and attenuated worker credentials are later integration work.

## Durable work and recovery

`DurableImports.begin` freezes a bounded inventory. `resume(..., publish = false)` acquires missing files, verifies retained checkpoints and prepares a durable candidate. After review, `resume(..., publish = true)` accepts through the same repository protocol. An expired worker cannot bind new work or publish. Response loss after acceptance is reconciled from the existing receipt without reacquiring source data.

Outbox delivery is at least once, with independent `(sequence, target)` identities and expiring claims. Consumers must deduplicate that identity and use the event's exact release. The queue is implemented; production Neurobagel or other external consumers are not yet supplied.

Operator scripts use standard PostgreSQL `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER` and `PGPASSWORD`/service-file configuration, plus `SANCTITY_DB_SCHEMA`. They require compatible PostgreSQL client tools:

```sh
scripts/backup-postgres.sh /managed/storage /new/backup-bundle
scripts/restore-postgres.sh /backup-bundle /new/restored-storage
```

Restore requires a fresh target schema and stopped application processes. It closes the access gate in the same transaction that restores catalog contents. The bundle verifies copied objects and the database archive.

Current policy must come from a trusted authority source independently of the old backup. The supplied operator CLI can capture that policy from the authoritative running database:

```sh
sbt 'app/runMain sanctity.app.RecoveryCli capture-policy /new/current-policy.json'
sbt 'app/runMain sanctity.app.RecoveryCli reconcile /current-policy.json TRUSTED_DIGEST /restored/storage'
```

Reconciliation checks retained blobs, restores current custody/grants, invalidates old worker leases and all restored API credentials, records the policy digest and opens the gate. A digest checks identity; the operator must establish the policy snapshot's authenticity and currency. If authoritative current policy is unavailable, the restored service stays closed. Withdrawal journals, production backup media and RPO/RTO qualification remain open.

## Run the DICOM slice

The fixture downloader verifies the official release archive and writes the executable and pin under `target/tools/`. It supports macOS arm64 and Linux x86_64; another platform can supply an independently pinned executable.

```sh
python3 scripts/fetch-dcm2niix.py
export SANCTITY_DCM2NIIX="$PWD/target/tools/dcm2niix-v1.0.20260724/dcm2niix"
export SANCTITY_DCM2NIIX_SHA256="$(python3 -c 'import json; print(json.load(open("target/tools/dcm2niix-v1.0.20260724/pin.json"))["sha256"])')"
scripts/check-dicom.sh
```

The demonstration generates synthetic DICOM slices, converts with [dcm2niix v1.0.20260724](https://github.com/rordenlab/dcm2niix/releases/tag/v1.0.20260724), maps outputs explicitly, accepts and exports them. An independent checker verifies every voxel's physical coordinates and value, image dimensions and scaling.

[DicomImport](modules/import-dicom/src/main/scala/sanctity/dicom/DicomImport.scala) separates conversion from mapping. Every converter output needs a destination or an explicit exclusion reason. Metadata supplementation is recorded. Original inputs, converter outputs, diagnostics and mapping receipts remain managed evidence and are checked during acceptance. The initial mapping profile permits explicit MRI NIfTI/JSON pairs; it does not infer participant/session/run labels. Input files are recorded as retained source evidence; conversion success does not establish per-instance scientific coverage.

## XNAT scope

[XnatApi](modules/import-xnat/src/main/scala/sanctity/xnat/XnatApi.scala) takes an HTTPS installation, credentials and an explicit `/data/.../files` resource scope. It retains native URIs, declared installation/project context, file observations and optional MD5 checksums; destination bytes receive SHA-256 identities. Credentials stay outside URLs and receipts. Experiment discovery uses the [documented limit/offset contract](https://wiki.xnat.org/xnat-api/paging-restful-tables). File collections use bounded complete listings; incomplete counts, duplicate rows, forbidden access and source changes are explicit failures.

[XnatExport](modules/import-xnat/src/main/scala/sanctity/xnat/XnatExport.scala) reads a resource ZIP against its supplied listing and optional archive prefix. Missing, extra, duplicate and unsafe members fail accounting. API and export sources can feed either native BIDS import or DICOM conversion. The API tests include a real loopback HTTP server and API/export byte agreement.

This is explicit resource migration support. A whole-installation survey, custom-data mapping, participant reconciliation, persistent assignment registry and live-source cutover report require the selected XNAT installation and representative fixtures. Listings observe accessible scope; they do not certify a simultaneous global source snapshot.

## Guarantees and current limits

Accepted scientific roots become visible atomically with complete required metadata/query rows. The transaction checks current authority, expected base/draft generation, evidence, worker fencing where applicable and idempotency, then commits the release/head, receipt, audit and outbox. Stored canonical identities are verified on decode. New interpretation profiles do not reinterpret historical releases silently; old interpretations without query facts explicitly decline scientific queries.

The default native demo uses the volatile reference catalog. The PostgreSQL implementation persists scientific state, grants, selections, operations and delivery state. Managed files are streamed and hashed, flushed before catalog publication and copied into isolated exports. All installed bytes remain retained; garbage collection is disabled.

The profile currently supports one raw dataset and at most 10,000 inventory entries. JSON is limited to 4 MiB per document, 32 MiB total and depth 128; participant TSV is limited to 4 MiB and a strict unquoted format. Unsafe paths, symlinks, case/Unicode collisions, invalid UTF-8, duplicate JSON keys and lossy JSON numbers are rejected. Authored bytes remain authoritative for full export.

Complete BIDS conformance, generic association closure, representative converter science, derivatives, processing bundles, general remote upload/view/operations interfaces, institutional authentication and large-scale performance remain unqualified or unimplemented. Tests use synthetic data and local fixtures; no live institutional XNAT migration has been performed.
