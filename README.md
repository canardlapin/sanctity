# Sanctity

Sanctity is a BIDS-native neuroimaging repository with a pure Scala kernel, an in-memory reference catalog, PostgreSQL persistence, and immutable managed bytes.

A **release** fixes content, scientific interpretation, and admission evidence. A **frozen selection** fixes query inputs and membership. A **prepared scientific view** becomes an ordinary assessed release with its own custody and grants. Imports, corrections, views and derivative submissions all use the same preparation and atomic acceptance protocol.

[Product requirements](docs/prd.md), [implementation plan and evidence](docs/implementation-plan.md), [design baseline](docs/design-baseline.md), [decision record](docs/decisions-and-evidence.md), and [bids4s integration](docs/bids4s-integration.md) describe the broader product and remaining qualification.

Current delivery planning follows the [core vision](docs/vision.md) and
[institutional readiness assessment](docs/institutional-readiness-plan.md): labs,
projects, current access, complete curator workflows, and an operable, recoverable
service. The existing [scientific core](docs/scientific-core.md) remains the integrity
foundation; additional numerical processing is deferred. Institutional readiness
has not yet been established.

The immediate milestone is a [reliable local installation and guided demo](docs/local-demo-plan.md)
on the product owner's computer, with documentation to support later institutional
discussions. Institutional approval is not assumed. The [six-area core-hardening
plan](docs/core-hardening-plan.md), coordinated by meta-epic EP9 in mote, sets the
engineering order, acceptance criteria and integrated release review.

## Implemented slices

| Area | Working behavior |
| --- | --- |
| Authority | Durable principals, dataset and fixed-release grants, independent metadata/content/propose/accept powers, curator-controlled administration, revocation and transactional checks |
| Interpretation context | Explicit registry/rule/coverage identity, authored BIDS version kept separately, unchanged-content profile adoption and stable historical queries |
| Scientific relationships | Recorded B0 groups, IntendedFor links, inherited DWI gradients and BOLD events, typed evidence, correction impact and bounded closure |
| Scientific queries | Explicit artifact/participant/session correlation; SQL and reference interpreters; lexical participant values; versioned query facts and durable selections |
| Scientific views | MRI dependency closure, localized gradients/events, projected participant/session/scan tables, rewritten references, exact transformation receipts, ordinary acceptance and sharing |
| Processing | Versioned input readiness, frozen accepted bundles, separate derivative destinations, resumable output collection and an external header-QC producer |
| Durable operations | Inventoried imports, verified file checkpoints, renewable worker leases, fenced publication, completion recovery, independent leased outbox targets |
| Recovery | Catalog/blob backup, transactional closed restore gate, integrity verification, reconciliation against separately supplied current policy |
| DICOM | Pinned real converter, bounded/cancellable process lifetime, retained source/diagnostic evidence, explicit output mapping and exclusions |
| XNAT | Authenticated API streams, explicit resource inventories, experiment pagination, checksums, source-change checks, matching resource ZIP import |
| HTTP and operator CLI | Revocable principal credentials, release/metadata inspection, correction and acceptance, fixed-release grants, frozen queries, guarded file streams, resumable local import |
| Service distribution | Pinned Java runtime artifact with API/console, migrations and trusted operator commands; source/dependency/digest/license inventory and disposable PostgreSQL install smoke |
| Browser console | Release/file inspection, metadata origins, findings/relationships, sidecar correction and conflict recovery, frozen queries and access actions over the same API; [v4g adoption gates](docs/implementation-plan.md#console-baseline-and-adoption) remain open |

These are bounded implementations with executable evidence. Institutional identity integration, full conformance, representative scientific qualification, production migration and performance remain open.

The [service distribution guide](docs/service-distribution.md) explains packaging
and running the service with Java 21, PostgreSQL and managed storage. Deployment
configuration and separate runtime/migration database roles are implemented and tested.
The [historical compatibility fixture](docs/upgrade-compatibility.md) passes old/current
package comparisons; full release-job and future-migration qualification remain open.
The [organization model](docs/organization-reference.md) now persists labs/projects,
membership, private placement and explicit stewardship in PostgreSQL. Reviewed legacy
migration preserves historical payloads and exact grants; full role/action enforcement
is next. [ADR004](docs/decisions/004-policy-durability.md) defines independent policy
durability; its witness implementation and organization recovery remain open. [Workload budgets](docs/engineering-workload.md) are frozen targets,
with performance measurement still pending.

## Build and native demonstration

Prerequisites: Java 21 and sbt. The default build verifies and extracts the exact [bids4s source pin](vendor/README.md), including the upstream license and unpublished scientific APIs. No sibling checkout is needed. The canonical library is [canardlapin/bids4s](https://github.com/canardlapin/bids4s).

```sh
sbt check
sbt 'app/run'
```

The native demonstration imports two synthetic BOLD acquisitions, explains inheritance, changes shared repetition time from 1.5 to 1.8, preserves a local override, accepts the correction and exports both releases. It prints a fresh workspace containing the source, immutable objects, exports and report.

An explicit destination must be new:

```sh
sbt 'app/run /absolute/path/new-demo-workspace'
```

Use `-Dsanctity.bids4s.path=/absolute/path/bids4s` as an explicit upstream development override. After upstream checks, refresh the pin with `python3 scripts/source-snapshot.py pin`. `bash scripts/check-clean-source.sh` verifies an isolated source build; `.github/workflows/scientific-core.yml` runs the scientific and recovery gates in CI.

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
| `processing-mri` | Header QC and temporal image statistics through one durable local runner; payload qualification, image derivative admission and resumable collection |
| `import-xnat` | Qualified resource references, HTTP acquisition, pagination, declared ZIP export import |
| `http` | Authenticated workflow routes, strict request grammar, explicit public JSON representations |
| `service` | Packaged API/console server, migration and trusted operator/import/credential/recovery entry points |
| `app` | Development demonstrations, optional adapters/processing assembly and shared tests |
| `integration` | Disposable PostgreSQL transaction, worker, recovery-gate and lifecycle tests |

Start at [NativeRepository](modules/application/src/main/scala/sanctity/application/NativeRepository.scala), [Catalog](modules/application/src/main/scala/sanctity/application/Capabilities.scala), [ScientificViews](modules/application/src/main/scala/sanctity/application/ScientificViews.scala), and [DurableImports](modules/application/src/main/scala/sanctity/application/DurableImports.scala).

The reference and PostgreSQL catalogs share pure [Admission](modules/kernel/src/main/scala/sanctity/kernel/Admission.scala) and [Authority](modules/kernel/src/main/scala/sanctity/kernel/Authority.scala) rules. Application ports and access contexts are trusted internal capabilities. The [authenticated HTTP adapter](modules/http/src/main/scala/sanctity/http/RepositoryHttp.scala) obtains contexts from verified credentials and exposes bounded workflows with explicit public representations. The reference catalog deliberately refuses credential-bearing contexts; the PostgreSQL adapter rechecks their liveness transactionally.

## Scientific processing demonstration

```sh
sbt 'app/runMain sanctity.app.ProcessingDemo'
```

Requires `/usr/bin/python3` in addition to the build prerequisites. This freezes
one selected MRI acquisition into an assessed input bundle, runs a small external
header-QC producer, resumes output collection into a separately governed dataset,
and accepts/exports its derivative through the ordinary core. The printed workspace
contains input and derivative exports and exact bundle/processing receipts. The QC table is a
repository-local derivative profile; it does not claim motion, voxel-quality,
registration or fMRIPrep qualification. See the [contract, limits and evidence](docs/scientific-core.md#implemented-slice-processing-inputs-and-derivative-return).

The demo now creates a private bundle using a researcher's source-read grant,
records a durable run before execution, accepts two successive QC releases and
queries their exact-input measurements. It writes `run-and-observations.json` and
both derivative exports, then demonstrates that source revocation blocks further
private-bundle delivery. Source-bound checks reject returned values that disagree
with the frozen input, even when the producer's inventory matches those bytes.

`ProcessingInputs.preparePrivate` requires current source access and produces a
creator-only bundle. `prepare` still creates a separately governed shared view and
requires disclosure authority. `LocalQcRuns` adds recovery before collection;
`beginStagedAt` creates a derivative revision against an explicit prior release.
Migration V006 adds run records, observation indexes and idempotent submission
bindings. See the [scientific contracts](docs/scientific-core.md#implemented-slice-derivative-evidence-observations-and-private-execution)
for query bounds and remaining qualification limits.

## Temporal image-processing demonstration

```sh
scripts/setup-mri-python.sh
export SANCTITY_MRI_PYTHON="$PWD/target/mri-python/bin/python"
sbt 'app/runMain sanctity.app.TemporalDemo'
```

This second recipe produces float64 temporal mean and population-SD images from
fully assessed BOLD payloads. It shares the durable run/collection mechanism with
header QC, checks source geometry, and exposes map summaries through scientific
queries. Full export and re-import retain image bytes and supported metadata.
[Contracts, qualification and limits](docs/scientific-core.md#implemented-slice-temporal-image-statistics)
include the explicit repository-local derivative naming profile.

Run the independent numerical oracle with the pinned Python environment:

```sh
"$SANCTITY_MRI_PYTHON" scripts/check-temporal-statistics.py --qualify target/temporal-qualification-new
```

The same environment is required for the new Scala lifecycle tests. The checker
itself uses only Python's standard library; its separate producer subprocess uses
NiBabel and NumPy. Qualification output includes per-case voxel comparisons,
source/code digests, logical I/O and peak process RSS.

The temporal demo also completes a run before a competing release advances its
destination, then recovers through `DerivativeSubmissions.publish` with an explicit
`PublicationIntent`. It deletes that run's execution workspace before collection,
retains all output digests, and queries three successive releases. Inspect
`publication.json` and independently verify the reconstructed `publication-oracle`
directory with `scripts/check-temporal-statistics.py --workspace PATH`.
Publication is owner-only, requires current source and destination authority, and
uses its own retry key; it leaves the original execution evidence unchanged.
Run `scripts/check-publication-recovery.sh` for the disposable PostgreSQL
crash/retry and backup/restore proof, including source revocation after backup.

## Scientific changes

`ScientificChanges.plan` prepares read-only rename/removal proposals against a pinned release. The BIDS adapter proposes `IntendedFor` and scans-table repairs; `prepare` requires the inspected plan digest and produces an ordinary assessed candidate. Moves retain artifact identity and image bytes. Previews explain before/after dependency scope; conflicting heads, blocking dependency findings and stale evidence cannot be accepted. See the [supported edit profile and proof](docs/scientific-core.md#implemented-slice-scientific-file-changes).

## Scientific relationships

[bids4s resolves explicit MRI declarations](../bids4s/modules/core/shared/src/main/scala/bids4s/BidsFieldmaps.scala). Sanctity binds the resulting grouped relationships, their field-origin evidence and resolver version into immutable interpretation identity. A metadata correction can preview changed acquisition relationships even when the affected image's own metadata is unchanged.

`Interpretation.relationshipClosure` follows recorded relationships and returns associated artifacts plus metadata documents. It includes selected DWI gradient pairs and BOLD event tables with their JSON metadata. Overridden companions remain evidence. Stimulus dependencies and production analysis qualification remain later scientific slices; the bounded header-QC bundle contract is implemented. Historical interpretations retain their identities and recorded coverage. `NativeRepository.correctCompanion` prepares bounded gradient/event edits and previews selection changes or changed dependency bytes. See the [scientific-core plan and proof](docs/scientific-core.md).

DWI preparation now checks selected gradient values and counts against bounded NIfTI-1/2 header facts, including gzip and either byte order. Versioned `ContentCheck` records retain exact input digests, observations and findings within assessment identity; rejected corrections leave the accepted head unchanged. The named vector-norm policy performs no reorientation or voxel/affine qualification.

Selected BOLD events now receive strict TSV/decimal assessment and recorded regular, variable or unavailable acquisition timing. Invalid content blocks acceptance; events beyond the documented comparison window produce review warnings. Every check binds exact table, image and metadata inputs through the existing `ContentCheck` record.

The current v8 profile also records MRI spatial/time units, dimensions and coded
transform observations, with explicit structural findings and TR discrepancies.
The v7 subset compiler includes resolved phasediff and phase1/phase2 magnitude families, including
cross-session dependencies. See the [MRI scientific contract](docs/scientific-core.md#implemented-slice-independent-mri-evidence-and-phase-difference-families)
and [independent licensed corpus](modules/app/src/test/resources/mri-conformance/README.md)
for rules, limits, independent header comparison and official-validator differences.

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

The `sanctity-mri-view-v8` profile supports T1w/T2w, single-echo and bounded multi-echo task/rest BOLD and DWI, with resolved gradients/events and explicit EPI, phasediff and phase1/phase2 fieldmap groups, including dependencies in another session. It localizes inherited companions, projects participant/session/scan tables and their column dictionaries, and rewrites `IntendedFor` to retained targets. Each output file has a persisted source-to-output receipt. Export/re-import preserves the supported scientific meaning.

`DisclosureProfile` has explicit `eventFields`, `sessionFields` and `scanFields` in addition to participant and metadata fields. Events require an explicit mask including `onset` and `duration`; retained column definitions are part of that column disclosure. Required acquisition/reference metadata cannot be masked. Stimulus/HED, SBRef/physiology, EPI gradients and file collections remain outside this profile. See [scope, limits and proof](docs/scientific-core.md#implemented-slice-coherent-mri-subsets). This is a bounded structural profile; scientific and de-identification qualification remain separate.

Grant actions are `ReadMetadata`, `ReadContent`, `Propose`, and `Accept`. The dataset curator administers grants. Dataset grants explicitly cover future releases; fixed-release grants permit reading only. Export reauthorization occurs before each chunk of at most 64 KiB. Expiring grants, delegation, group membership, institutional login and arbitrary source-evidence delivery are future work.

## Run the PostgreSQL slice

With Docker running and Python 3.11 or later:

```sh
scripts/check-postgres.sh
```

The runner creates a private loopback PostgreSQL container, runs ordinary and database tests, demonstrates crash/retry recovery, then exercises backup/restore after an intervening grant revocation. It restores into a fresh database and verifies that the closed recovery gate prevents access before current policy is applied. The container and its volume are removed afterward. Logs, backups, policy receipts and exports remain under the printed `target/postgres-check.*/` directory.

Database tests are separate from `sbt check`; they fail without the explicit disposable-test environment.

The opt-in `bash scripts/check-query-scale.sh` harness measures accepted scientific
queries up to 9,993 inventory entries and observations across 100 inputs and 100
releases. It retains oracle-checked results, query plans, setup/storage costs and
source fingerprints under `target/query-scale.*/`. See the
[query qualification contract](docs/scientific-core.md#scientific-query-qualification-and-scale-harness)
for cache conditions and interpretation limits. The [retained comparison](docs/evidence/query-scale-summary.json)
shows the bounded source-validation change reducing the repeated 100-input case
from 8.53 s to 355 ms and the 100-release case from 18.39 s to 9.68 s in one local
repeat pair. All results match the independent oracle; shared-host timing limits
and both comparison pairs are retained. The harness is separate from ordinary CI.

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

The same server serves the browser console at `http://127.0.0.1:8080/` (or the
configured port). Connect with the issued token and dataset ID. `/?demo` uses
synthetic browser fixtures; normal mode uses the authenticated `/v1` API. The
[console baseline and adoption checklist](docs/implementation-plan.md#console-baseline-and-adoption)
records the reviewed scope, correction-safety contract, recovered evidence and
remaining VoiceOver gate. Run `bash scripts/check-console.sh` for the browser
regressions and v4f differential replay. Run `bash scripts/check-console-backend.sh`
for the headless browser rehearsal against a disposable PostgreSQL database and
the real authenticated API. It exercises correction, concurrent editing, frozen
queries and grant/revoke, then verifies committed database records. The
[screen-reader runbook](docs/http-api.md#console-checks-and-voiceover-exercise)
provides the real VoiceOver tasks and a local synthetic conflict fixture.

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

Complete BIDS conformance, generic association closure, representative converter science, general derivative/processing profiles, general remote upload/view/operations interfaces, institutional authentication and large-scale performance remain unqualified or unimplemented. Tests use synthetic data and local fixtures; no live institutional XNAT migration has been performed.


### Coherent multi-echo analysis inputs

Selecting one echo now retains its recorded BOLD family, shared events, and local
stimulus assets referenced by retained `stim_file` cells. Echo times, timing and
header dimensions/spacing have explicit consistency checks. Views preserve exact
image/asset bytes and source receipts; dropping `stim_file` from the event mask
also drops its assets. Reviewed family and stimulus moves preserve relationships.

```sh
sbt 'app/runMain sanctity.app.AnalysisInputsDemo'
target/mri-python/bin/python scripts/check-analysis-input-conformance.py
```

The demonstration selects echo 2, freezes three echoes and two stimuli, publishes
per-image header QC, corrects the source, verifies historical bytes, and exercises
private-input and publication revocation. The conformance command uses the
previously pinned Deno 2.5.4 executable at `target/conformance-tools/deno` (or
`--deno /path/to/deno`). [Plan, motes and limits](docs/analysis-input-plan.md).
Echo combination, SBRef, physiology, HED and scanner/registration qualification
remain outside this profile.
