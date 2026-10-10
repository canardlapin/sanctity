# Sanctity decisions and evidence

Consolidated 7 October 2026. This register distinguishes explicit project direction from architectural proposals in the supplied material. It does not make an unreviewed proposal into an approved implementation decision.

The [PRD](prd.md) and [implementation plan](implementation-plan.md) now define the current proposed requirements, scope, and delivery estimates. Earlier schedule figures below remain historical planning assumptions.

The companion [design baseline](design-baseline.md) is the coherent working model. Identifiers here and its INV identifiers belong to this synthesis; they do not claim to reproduce the earlier PRD's requirement numbering.

## Decision status

| Status | Meaning |
| --- | --- |
| Required direction | Explicit in the user's project brief |
| Working proposal | Repeated or well-supported architectural recommendation in the supplied discussion |
| Reserved contract | A distinction to preserve now; the full capability can arrive later |
| Open decision | Needs source evidence, a pilot choice, or an implementation experiment |
| Acceptance hypothesis | Proposed measurement or demonstration, not an achieved result |

| ID | Decision or direction | Status |
| --- | --- | --- |
| DEC-01 | BIDS-native scientific repository; core quality and extensibility take priority | Required direction |
| DEC-02 | Scala implementation using canardlapin/bids4s for BIDS support | Required direction |
| DEC-03 | DICOM, XNAT export, and XNAT API ingestion | Required direction |
| DEC-04 | Eventual UI, interoperability, and processing with derivative return | Required direction |
| DEC-05 | Distinguish ContentRevision, Interpretation, Assessment, and Release | Working proposal |
| DEC-06 | One preparation/acceptance protocol for all producers | Working proposal |
| DEC-07 | ScientificView unifies fixed selections, authorized representations, and processing inputs | Working proposal |
| DEC-08 | Modular service, PostgreSQL, managed immutable bytes, CLI/API first | Working proposal |
| DEC-09 | Cats Effect/FS2 with http4s, Tapir, Circe, Doobie/HikariCP, MUnit/ScalaCheck | Working proposal; compatibility unverified |
| DEC-10 | Identity, membership, custody, provenance, and current authority stay distinct | Working proposal |
| DEC-11 | Begin with dataset actions and a small set of fixed-release/disclosure profiles | Working proposal |
| DEC-12 | Rich delegation, federation, broader identity integration, UI administration, and execution backends | Reserved contracts |
| DEC-13 | Native BIDS import/edit/export is the first architectural proof | Working proposal |
| DEC-14 | Scoped MRI migration is the first controlled production outcome | Working proposal |
| DEC-15 | Numerical performance, staffing, and schedule figures require evidence | Acceptance hypotheses |

These proposals should become ADRs when actual choices are made. No ADR is marked accepted merely because it appears in these notes.

## What the consolidation changes

The three supplied layers are a vision, an expanded PRD, and a comparative architectural review. They are cumulative, but their terminology and scope need reconciliation.

| Earlier framing | Consolidated interpretation |
| --- | --- |
| Immutable dataset revision as the central object | Content is immutable; a Release explicitly accepts content plus interpretation and evidence |
| A single revision identifier for scientific use | Exact release, selection, view manifest, and execution input may have different identities |
| Export as a storage function | Full export preserves bytes; subset/pipeline export compiles an assessed representation |
| Authorization as a supporting concern | Current authority is a separate dimension at each use boundary |
| Future pipelines as jobs | Execution receives bounded authority and returns a draft through ordinary acceptance |
| Completed upload/import | Completion is supported by a durable, scoped receipt |
| A 26-week pilot plan | A planning hypothesis made before the full later collaboration scope was reconciled |
| Competitor findings as motivation | Retain the corresponding invariant and failure fixture; verify claims before using them externally |

The unifying operation is to prepare an exact scientific object, establish applicable authority, and retain a durable receipt. This does not require every operation to have the same state machine: import, release acceptance, materialization, execution, and external delivery each need truthful completion semantics.

## Open decisions in useful order

The defaults below are proposals for keeping the first implementation tractable. They are not answers supplied by the user.

| ID | Resolve | Proposed starting position | Needed evidence |
| --- | --- | --- | --- |
| OPEN-01 | Pilot installation, source scope, volume, modalities, custom resources, scientific owner | One named collection and an explicitly bounded MRI profile | Source survey and accountable curator/operator |
| OPEN-02 | Authoritative inventory, directory entries, symlinks, unsafe paths, normalization, case collisions | Preserve safe regular content; reject or classify unsupported path behavior explicitly | Portable import/export fixtures |
| OPEN-03 | BIDS schema/profile selection and current bids4s capabilities | Pin a tested semantic profile; contribute general fixes upstream | Focused current source review and conformance tests |
| OPEN-04 | Interpretation identity, persisted results, and physical reindexing | Retain enough evidence/results to reproduce historical answers; prove index migrations equivalent | Two interpreters over unchanged content |
| OPEN-05 | Initial admission policies and unsupported content | Separate preservation from semantic/conversion qualification | Native valid/invalid/unsupported fixtures |
| OPEN-06 | Draft concurrency and acceptance idempotency | Expected draft generation plus expected release head; scoped key bound to payload | Concurrent clients and lost-response fixtures |
| OPEN-07 | Initial authority scopes and disclosure transformations | Dataset actions plus one fixed view profile; unsupported scopes fail explicitly | Restricted sharing fixtures across all API surfaces |
| OPEN-08 | Policy consistency during acceptance, active delivery, jobs, and identity refresh | State a supported revocation boundary; enforce current local authority at admission/use points | Revocation-race tests and chosen identity architecture |
| OPEN-09 | Private provenance versus externally visible identifiers and receipts | Keep restricted source lineage private; deliberately choose disclosed fields | Noninterference tests with excluded data changed |
| OPEN-10 | Storage backend, staging/GC coordination, retention, and withdrawal | Managed filesystem first if deployment permits; durable leases and explicit lifecycle roots | Crash, collection, corruption, and withdrawal fixtures |
| OPEN-11 | Backup policy and recovery-authoritative access/lifecycle state | Restore must reconcile current restrictions before serving | Full restore drill including intervening revocation |
| OPEN-12 | XNAT consistency and identity reconciliation | Qualified source references; claim only observed scope/consistency | Actual API/export behavior and source-change rehearsal |
| OPEN-13 | Conversion profiles, stable run assignments, and fieldmap family | Narrow qualified MRI profile with persisted assignments | Scientist-reviewed representative DICOM |
| OPEN-14 | Query language and physical catalog growth | Small correlated AST; bulk SQL indexing; measure full rebuild/membership costs | Reference/SQL agreement and realistic scale fixture |
| OPEN-15 | Derivative admission obligations and bounded worker execution | One simple producer, exact bundle, independent destination draft | Cross-custodian derivative scenario |
| OPEN-16 | Staffing and calendar after access/extension scope is selected | Re-estimate after foundation experiments | Named capacity, risk reserve, and explicit exclusions |

No live source credentials, participant mappings, clinical examples, or operational secrets belong in these design notes.

## Proposed pilot scope

The inherited scope proposal qualifies automatic conversion for T1w, T2w, single-echo resting/task BOLD, supplied task events, SBRef, and one agreed fieldmap family. Reverse phase-encoding EPI was the planning default.

Native DWI preservation and indexing were included; automatic DWI conversion was deferred unless deliberately traded into scope. Multi-echo, quantitative MRI, and further modalities require separate qualification.

This is a proposed scope boundary, not an agreed coverage claim. Native preservation outside the interpreted profile should carry explicit support status.

The controlled pilot should also prove one restricted sharing profile, access by an external collaborator, revocation of cached delivery, and derivative output to a separately governed destination. This is narrower than a production collaboration and general execution platform.

## Delivery assumptions and targets

The inherited capacity assumption is two experienced full-time Scala/backend engineers, approximately 0.5 FTE neuroimaging scientist/curator, approximately 0.25 FTE platform support, and a product owner able to resolve scope and acceptance. Later UI work assumes dedicated frontend capacity.

The earlier planning hypothesis was 26 weeks plus six weeks of reserve:

| Milestone | Earlier nominal window | Evidence gate |
| --- | --- | --- |
| M0 | Weeks 1–2 | Scoped pilot, fixtures, owners, vocabulary, measurement conditions |
| M1 | Weeks 3–6 | Native fidelity and semantic kernel |
| M2 | Weeks 7–12 | Atomic acceptance, scoped queries, access, recovery, CLI/API |
| M3 | Weeks 13–16 | Scientifically qualified DICOM profiles |
| M4 | Weeks 17–22 | XNAT export/API migration and complete scoped accounting |
| M5 | Weeks 23–26 | Performance, lifecycle, restore, extension proofs, handover |
| M6 | After pilot; prior rough estimate 6–10 weeks | UI and selected production integrations with frontend capacity |
| M7 | After pilot; estimate after backend choice | Production processing backend and fMRIPrep workflow |

These are retained planning assumptions. The later identity/sharing/execution constraints require a revised estimate. Gates should constrain scope changes; a calendar date cannot waive scientific or transactional invariants.

| Area | Inherited proposed target | Conditions or unresolved part |
| --- | --- | --- |
| Metadata query | Under 250 ms p95 | Defined warm 100-row query over 1 million entries; 20 concurrent readers; reference hardware and selectivity still to specify |
| Final acceptance | Under 1 second p95 | Preparation complete; 10 competing clients; specify contention and successful/conflict outcomes |
| Streaming | Process a 100 GiB input within configured bounds | Fix memory/buffer budget and concurrency before measurement |
| Metadata-only changes | No additional image payload storage | Measure membership/index amplification separately |
| Recovery point | At most 15 minutes | Depends on chosen production durability/backup design |
| Catalog/API restoration | At most four hours | Blob storage available; authority reconciliation included |
| Full storage loss | Separate measured recovery estimate | Actual dataset volume, backup medium, and restore throughput |
| Scale characterization | Approximately 10k, 100k, and 1m files | Import rate, allocations, semantic rebuild time, cancellation, catalog growth |

No listed performance or recovery number is an observed result.

## Acceptance proof catalog

These are requirements for later tests and demonstrations, not tests implemented by this notes-only task.

| ID | Fixture or experiment | Required observation |
| --- | --- | --- |
| PROOF-01 | Import and full export | Declared tree and every retained file's bytes agree |
| PROOF-02 | Shared sidecar edit with local overrides | Correct old/new impact, origins, and preserved prior release |
| PROOF-03 | Two interpreters/validators over identical content | Historical release answers stay pinned; adoption is explicit |
| PROOF-04 | Two-person age/sex example and split-session acquisitions | Correlated query cannot combine unrelated people or sessions |
| PROOF-05 | Publish new releases during pagination | Frozen universe and membership stay unchanged |
| PROOF-06 | Failure at each preparation/acceptance boundary | Only coherent accepted releases become visible |
| PROOF-07 | Retry after committed response is lost; reuse key with changed payload | Same authorized receipt on retry; explicit mismatch on changed intent |
| PROOF-08 | Concurrent draft editors and head advancement | Explicit conflicts, no lost updates or stale admission |
| PROOF-09 | Add sidecar with previously absent dependencies | New applicability participates in impact calculation |
| PROOF-10 | Restricted subset with forbidden fields/dependencies | Supported transformed result or blocked plan; no authority expansion |
| PROOF-11 | Change only excluded participants/fields | Approved disclosed outputs remain invariant, with explicit exceptions for allowed provenance |
| PROOF-12 | Revoke access after caching and during bounded use | Declared revocation boundary enforced; cached identity does not grant authority |
| PROOF-13 | Link/unlink login; remove one institution's membership | Attribution/custody stay stable; independent grants behave correctly |
| PROOF-14 | Narrow child grant; attempt broader audience/scope/actions/expiry | Allowed attenuation only |
| PROOF-15 | Cross-custodian derivative; worker attempts unrelated access | Valid input/destination use succeeds; broader actions fail |
| PROOF-16 | Fail second required output collection | Recoverable staging, no accepted partial derivative |
| PROOF-17 | Same-size source change, missing object, denied resource, interrupted XNAT scan | Truthful inventory/dispositions and scoped reconciliation |
| PROOF-18 | New earlier acquisition | Previously accepted run assignments remain stable |
| PROOF-19 | Scientific DICOM examples | Independent geometry, orientation, scaling, timing, and association review |
| PROOF-20 | External projection outage and out-of-order workers | Accepted release stays coherent; exact generation/status remains truthful |
| PROOF-21 | GC competes with draft/export/acceptance; writable materialization changes | Live roots remain protected; canonical blobs unchanged |
| PROOF-22 | Restore an old backup after withdrawal or revocation | Restricted state reconciled before access resumes |
| PROOF-23 | Transfer custom references and colliding labels; federation node unavailable | Explicit identity mapping and incompleteness, no silent merge/omission |
| PROOF-24 | Reference versus SQL property tests and scale runs | Supported semantics agree; measured resource/latency behavior documented |

## Inherited research and its limits

The supplied discussion contains a previous source review and links to a [PRD and roadmap Page](https://chatgpt.com/space/page_516ae99cd8048191a7bcc3e04f069b24). That Page was not edited by this consolidation. Its complete content and stable requirement IDs are not reproduced here.

The following references preserve the evidence trail of the supplied analysis. The earlier audit's claims apply to its pinned sources and examined paths; this consolidation does not establish current production behavior or re-audit those systems.

| Prior observation area | Source pointers carried forward | Design consequence retained |
| --- | --- | --- |
| bids4s readiness, inheritance, inventory, JSON behavior | [Pinned README](https://github.com/canardlapin/bids4s/blob/5dafc800499b2a4642a19ff47fa5924516b2c024/README.md), [coverage ledger](https://github.com/canardlapin/bids4s/blob/5dafc800499b2a4642a19ff47fa5924516b2c024/docs/pybids-parity.md), [resolver](https://github.com/canardlapin/bids4s/blob/5dafc800499b2a4642a19ff47fa5924516b2c024/modules/core/shared/src/main/scala/bids4s/BidsProject.scala) | Verify semantics upstream; preserve authored bytes independently |
| Snapshot and selected validation identity | [OpenNeuro validation](https://github.com/OpenNeuroOrg/openneuro/blob/160b62a854f28ee76f5d0250c31999ab7887dc20/packages/openneuro-server/src/graphql/resolvers/validation.ts), [snapshot](https://github.com/OpenNeuroOrg/openneuro/blob/160b62a854f28ee76f5d0250c31999ab7887dc20/packages/openneuro-server/src/models/snapshot.ts) | Bind interpretation and chosen admission evidence |
| Dataset discovery versus correlated cohort semantics | [OpenNeuro mapping](https://github.com/OpenNeuroOrg/openneuro/blob/160b62a854f28ee76f5d0250c31999ab7887dc20/packages/openneuro-search/src/mappings/datasets-mapping.json), [predicate construction](https://github.com/OpenNeuroOrg/openneuro/blob/160b62a854f28ee76f5d0250c31999ab7887dc20/packages/openneuro-server/src/graphql/resolvers/build-search-query.ts) | Explicit correlation scopes and frozen selections |
| Login migration and attribution | [OpenNeuro user migration](https://github.com/OpenNeuroOrg/openneuro/blob/160b62a854f28ee76f5d0250c31999ab7887dc20/packages/openneuro-server/src/libs/authentication/user-migration.ts) | Durable principal separate from login and custody |
| Dataset roles and sharing scopes | [OpenNeuro permissions](https://github.com/OpenNeuroOrg/openneuro/blob/160b62a854f28ee76f5d0250c31999ab7887dc20/packages/openneuro-server/src/models/permission.ts), [XNAT sharing](https://wiki.xnat.org/documentation/understanding-data-sharing-in-xnat-s-security-stru) | Independent actions, explicit delegation, compiled BIDS views |
| Derivative destination and lineage | [Container Service finalizer](https://github.com/NrgXnat/container-service/blob/1c80bc6f0407416664b1a0cf280206470e1eee1d/src/main/java/org/nrg/containers/services/impl/ContainerFinalizeServiceImpl.java), [OpenNeuro derivative discovery](https://github.com/OpenNeuroOrg/openneuro/blob/160b62a854f28ee76f5d0250c31999ab7887dc20/packages/openneuro-server/src/graphql/resolvers/derivatives.ts) | Independent destination authority and exact transformation receipts |
| Worker authority and archive mounting | [OpenNeuro authorization](https://github.com/OpenNeuroOrg/openneuro/blob/160b62a854f28ee76f5d0250c31999ab7887dc20/packages/openneuro-server/src/graphql/permissions.ts), [Container Service command resolution](https://github.com/NrgXnat/container-service/blob/1c80bc6f0407416664b1a0cf280206470e1eee1d/src/main/java/org/nrg/containers/services/impl/CommandResolutionServiceImpl.java) | Bounded run authority and frozen inputs |
| Cross-service publication and per-output side effects | [OpenNeuro snapshot orchestration](https://github.com/OpenNeuroOrg/openneuro/blob/160b62a854f28ee76f5d0250c31999ab7887dc20/packages/openneuro-server/src/datalad/snapshots.ts), Container Service finalizer above | Atomic scientific acceptance; separate external readiness |
| Upload completion and content equality | [OpenNeuro HTTP upload](https://github.com/OpenNeuroOrg/openneuro/blob/160b62a854f28ee76f5d0250c31999ab7887dc20/services/datalad/datalad_service/handlers/upload.py), [completion resolver](https://github.com/OpenNeuroOrg/openneuro/blob/160b62a854f28ee76f5d0250c31999ab7887dc20/packages/openneuro-server/src/graphql/resolvers/upload.ts) | Durable verified receipts; size alone cannot prove equality |
| Identity rewriting during interoperability | [Pinned XSync README](https://github.com/NrgXnat/xsync/blob/22b9468790b79425b0df45be47c5cb0511f1ffb3/README.md) | Qualified external references and explicit transformation reports |

The useful output of this comparative work is the requirement and failure fixture, not a claim of general superiority or a vulnerability claim about deployed systems.

Primary reference entry points supplied for later implementation verification:

- [BIDS schema](https://bids-specification.readthedocs.io/en/stable/appendices/schema.html), [inheritance/common principles](https://bids-specification.readthedocs.io/en/stable/common-principles.html), and [dataset description/provenance](https://bids-specification.readthedocs.io/en/stable/modality-agnostic-files/dataset-description.html).
- [dcm2niix BIDS notes](https://github.com/rordenlab/dcm2niix/blob/master/BIDS/README.md) and [dcm4che](https://www.dcm4che.org/).
- [XNAT downloads](https://wiki.xnat.org/xnat-api/how-to-download-files-via-the-xnat-rest-api) and [subject identity/sharing context](https://wiki.xnat.org/xnat-api/subject-api).
- [Neurobagel imaging preparation](https://neurobagel.org/user_guide/preparing_imaging_data/) and [API](https://neurobagel.org/user_guide/api/).
- [fMRIPrep usage](https://fmriprep.org/en/stable/usage.html).
- [OpenID Connect](https://openid.net/specs/openid-connect-core-1_0-errata2.html), [GA4GH DRS](https://ga4gh.github.io/data-repository-service-schemas/docs/), and [GA4GH Passports](https://www.ga4gh.org/product/ga4gh-passports/).
- [Cats Effect Resource](https://typelevel.org/cats-effect/docs/std/resource), [Doobie PostgreSQL support](https://typelevel.org/doobie/docs/15-Extensions-PostgreSQL.html), and [PostgreSQL concurrency](https://www.postgresql.org/docs/current/transaction-iso.html).

Unpinned stable/current links are entry points, not version locks. Implementation work should record the versions and documents it actually qualifies.

## Implemented refinements from the four-slice build-out

The implementation now reserves fewer special pathways:

- A compiled scientific view is a regular accepted release in a separate dataset. Projection evidence identifies its source release, frozen selection, disclosure profile and generated files. The common transaction rechecks source disclosure authority.
- Query facts are part of interpretation identity. Pre-query interpretations remain readable and explicitly refuse unsupported scientific queries. PostgreSQL and the reference evaluator preserve participant/session correlation and lexical entity equality.
- Dataset grants and fixed-release grants have different scope. Metadata, content, proposing and accepting are separate powers; only the curator administers grants in this profile. Network identity binding remains an independent boundary.
- An import operation retains its declared inventory, verified files and candidate. Expiring lease tokens fence publication. An already accepted result can finish journal reconciliation without rereading its former source.
- Outbox targets have independent, generation-bound delivery claims. Consumers deduplicate `(sequence, target)`; no external publication is inferred from scientific acceptance.
- The supported restore procedure closes access transactionally and requires trusted current policy plus byte verification before reopening. A policy digest identifies supplied evidence; it does not establish that the supplier is authoritative or current.
- Both XNAT resource routes produce the same native-source contract. DICOM conversion and explicit output mapping produce that contract too. Retained source evidence, converter diagnostics, receipts and output exclusions remain attached to the accepted activity.

Primary references checked during this implementation include the [BIDS common principles](https://bids-specification.readthedocs.io/en/stable/common-principles.html), [MRI specification](https://bids-specification.readthedocs.io/en/stable/modality-specific-files/magnetic-resonance-imaging-data.html), [official dcm2niix v1.0.20260724 release](https://github.com/rordenlab/dcm2niix/releases/tag/v1.0.20260724), [XNAT resource listings](https://wiki.xnat.org/xnat-api/image-scan-resource-api), [resource downloads](https://wiki.xnat.org/xnat-api/how-to-download-files-via-the-xnat-rest-api), and [experiment/subject pagination](https://wiki.xnat.org/xnat-api/paging-restful-tables). File collections use bounded whole listings; paging is used only for documented experiment tables.

The macOS archive pin is `d393006dd1edfba4013bc4b8eccc875b23d611ecbc53fda5a5542f629591008f`; the tested executable pin is `15d41af9fc7626a68e8c545237cc78e4f84bcee13b57d5d061bc6db30519fd98`. The fixture downloader records the selected platform's archive and executable identities. Source-derived semantic fingerprints remain separate from converter identity.

The [implementation verification record](implementation-plan.md#verification-record) contains the current evidence. Supported synthetic examples do not substitute for the PRD's institutional and scientific qualification gates.

## Authenticated workflow boundary

The next slice uses operator-issued opaque API credentials before selecting an institutional identity provider. Each credential authenticates an existing stable principal; its powers remain the principal's current catalog grants. It is not a bounded processing-worker token or an OIDC implementation. Only a random 256-bit token's SHA-256 digest is stored. The local operator CLI writes the secret once to a new mode-0600 file and revokes a newly issued credential if writing fails.

A credential reference accompanies the internal access context. PostgreSQL checks its principal, expiry and revocation within authorized transactions, with row locks that serialize revocation against in-flight transactions. The volatile reference catalog rejects these contexts because it cannot check their durable liveness. File delivery reauthorizes each chunk; it cannot recall previously emitted bytes.

The restore profile invalidates every restored API credential before reopening. This avoids requiring an independent credential-revocation journal in the initial API slice. Principal attribution and reconciled custody/grants remain intact; clients receive fresh credentials after recovery.

The HTTP adapter has an explicit JSON boundary. It accepts curation/query/grant requests and release-relative paths, never arbitrary internal manifests, evidence blobs, activities or a caller-supplied principal. Public release responses omit private source/converter evidence. Full disclosure and controlled view creation continue to use the established application contracts.

The runnable adapter uses the existing [http4s 0.23 DSL](https://http4s.org/v0.23/docs/dsl.html) and [RFC 6750 bearer header/challenge conventions](https://www.rfc-editor.org/rfc/rfc6750). No new crypto implementation or identity-provider assumption was introduced. The checked-in [OpenAPI contract](http-api.openapi.json) is explicit; Tapir-generated endpoints/clients remain a future consolidation if the endpoint surface grows.

## Scientific-core priority and explicit relationships

The user's 7 October direction prioritizes scientific semantics over further institutional-login work. The first resulting slice makes explicit acquisition relationships part of immutable interpretation, with a reusable portable BIDS resolver upstream.

The kernel stores generic, versioned relationship records with grouped source members and metadata-origin evidence. B0 identifiers are participant-tree scoped, including sessions, and their multiple defining images form one estimation group. Multiple identifiers and IntendedFor declarations retain their own records. No nearest-file heuristic or algorithm precedence is inferred. The rules were checked against the [BIDS MRI declaration specification](https://bids-specification.readthedocs.io/en/stable/modality-specific-files/magnetic-resonance-imaging-data.html#expressing-the-mr-protocol-intent-for-fieldmaps), version 1.11.2.

New semantic profile `native-reference-v2` assesses these declarations. Its relationship evidence participates in canonical interpretation v3 identity; historical v1/v2 identities and assessments remain intact. A metadata edit can change a consumer's group without changing that consumer's own effective metadata, so previews now carry separate relationship deltas.

Closure follows the recorded vocabulary and includes authored metadata dependencies. It does not promise all implicit companions or a qualified analysis bundle. The subset compiler now requires current recorded relationship coverage and blocks omitted declared inputs, including a fieldmap linked from another session. This fixes an omission in the old proximity-based guard.

The next scientific slices are companion resolution, image/table consistency, and complete subset profiles with explicit reference rewriting. See [scope and proof](scientific-core.md).

## Companion selection and historical evidence compatibility

The 10 October scientific slice adds inherited gradient and event selection. BIDS semantics remain upstream in a portable directory/role index; Sanctity owns admission, correction, historical identity and persistence. The resolver's candidate list explains both selection and override without copying or merging file contents.

Relationship evidence now distinguishes metadata-field declarations from companion-selection rules. The latter records a selected path, dataset root, rule identity, matched entities and applicable hierarchy. It does not manufacture a JSON origin for a filename-based relationship. Evidence encoding 2 uses interpretation identity v4. Existing field-only v3 graphs retain their original canonical tokens and decoding; v1/v2 records remain unchanged. A fixed v3 identity regression guards this boundary.

DWI gradients are a paired dependency group. Missing gradients and ambiguity block the current admission profile; absent non-rest BOLD events are a warning. Companion content consistency and stimulus references remain explicitly unassessed. Adding a local events table changes only applicable consumers; changing selected table bytes appears as a direct dependency change even if link endpoints stay equal. Historical releases retain the earlier bytes, evidence and interpretation.

The bounded application correction capability uses the same authority, staging, draft and acceptance operations as JSON correction. It adds no alternate write path or automatic processing behavior. The existing restricted subset compiler requires current recorded coverage and refuses to drop inherited events. Full subset rewriting remains future work.

## DWI content-check evidence — 10 October 2026

The scientific kernel now records generic `ContentCheck` results: rule, target, exact inputs, observations and findings. Assessments with checks use canonical v2 identity; older assessments retain v1. Admission verifies evidence-to-manifest binding and includes check findings automatically. MRI-specific parsing, norms and header inspection stay in bids4s and the BIDS adapter.

The initial `native-reference-v4` profile checks selected DWI gradient tables against bounded scalar NIfTI headers. The policy records its low-b and norm tolerances. Gzip reads inspect a bounded prefix and do not certify the entire compressed payload. Authoritative bytes remain untouched. Event content and anatomical orientation qualification remain separate work. The [scientific-core contract](scientific-core.md#implemented-slice-dwi-content-consistency) specifies exact limits and lifecycle proof.
