# Sanctity product requirements document

Version 0.1 — draft for implementation planning\
Updated 7 October 2026\
Product owner, technical lead, scientific lead, and operator: to be assigned

Sanctity is a BIDS-native neuroimaging repository built around exact scientific states, explainable transformations, and explicit authority. Its first product is a dependable Scala core that a laboratory can use to ingest, curate, query, share a bounded selection, and recover a scoped MRI collection.

**Product promise:** for every accepted state whose required content is retained, Sanctity can identify its exact content, explain the interpretation used to answer queries, show why it was accepted, and reproduce its declared BIDS representation. Current access and lifecycle policies govern use.

This PRD defines required product behavior and acceptance evidence. The [implementation plan](implementation-plan.md) defines the proposed engineering sequence. The [design baseline](design-baseline.md) retains architectural rationale; [decisions and evidence](decisions-and-evidence.md) retains the earlier discussion and open questions. This PRD and its plan supersede earlier scope and schedule proposals where they differ. All documents remain proposals until the relevant decisions are adopted.

The canonical BIDS dependency is **[canardlapin/bids4s](https://github.com/canardlapin/bids4s)**. Its current README describes a pre-release Scala 3 library and does not imply an available stable release. Phase P0 must pin a usable revision/artifact and verify API coverage and compatibility. [bids4s README](https://github.com/canardlapin/bids4s/blob/main/README.md). The sibling checkout and first implemented metadata-resolution improvements are recorded in [bids4s integration status](bids4s-integration.md); they do not yet constitute a published dependency pin.

## 1. Problem and opportunity

Neuroimaging repositories must coordinate several meanings of “the data”: received source objects, curated files, effective metadata, selected analysis inputs, derivatives, and information permitted for a collaborator. Errors arise when those meanings drift apart or when one successful step is presented as completion of the whole operation.

Sanctity must make these questions answerable:

- What exactly arrived, and what happened to every source object?
- Which content and semantic rules produced this query result?
- Why does this acquisition have this metadata value?
- What will a correction affect?
- What exact representation may this collaborator receive?
- What inputs produced this derivative, and who may accept or disclose it?
- What remains usable after an interruption, permission change, or restore?

The opportunity is a coherent scientific lifecycle supported by a small set of stable contracts. A new source, modality profile, interface, or processing backend should reuse those contracts.

## 2. Objectives and success measures

| ID | Objective | Evidence of success |
| --- | --- | --- |
| OBJ-01 | Preserve exact scientific state and historical meaning | Full-export fidelity and interpreter-upgrade fixtures pass |
| OBJ-02 | Make curation explainable | A curator resolves mappings and metadata changes through the CLI/API without editing database rows |
| OBJ-03 | Produce correct scientific selections | Reference and SQL query interpreters agree; correlation and pagination fixtures pass |
| OBJ-04 | Share useful data within explicit authority | A restricted view remains coherent and exposes only its approved representation |
| OBJ-05 | Migrate a real scoped collection | Every observed in-scope object has a supported final disposition; scientific and cutover gates pass |
| OBJ-06 | Recover predictable operations | Retry, interruption, integrity, retention, and restore exercises pass |
| OBJ-07 | Demonstrate extension quality | A derivative producer and Neurobagel projection use public contracts without another acceptance path |

All objectives are pilot requirements within the supported profile. Performance numbers below are targets to qualify, not existing measurements. Product adoption and subjective elegance are not substitutes for these correctness gates.

## 3. Users and responsibilities

| User | Main workflow | Acceptance responsibility |
| --- | --- | --- |
| Curator | Inventory, map, inspect findings, correct metadata, prepare and accept | Mapping clarity and supported curation workflows |
| Researcher | Query, inspect metadata, freeze selections, obtain exact inputs | Scientific usefulness and retrieval |
| Custodian | Assign bounded access, control acceptance/disclosure, withdraw access | Sharing scope and governance behavior |
| Pipeline integrator | Consume a bundle, run an approved recipe, return outputs | Exact input/output evidence and destination authority |
| Operator | Deploy, monitor, recover, verify integrity, manage lifecycle | Durability, diagnostics, restore, resource limits |
| Extension developer | Implement a source, interpreter/profile, or adapter | Contract compatibility and preservation of core laws |

One person may perform several roles. Roles are convenient action bundles; custody, principal identity, and employment/membership remain separate records. Named acceptance owners are a P0 deliverable.

## 4. Delivery scope

**Core alpha** proves native BIDS preservation, interpretation, metadata correction, correlated queries, atomic acceptance, dataset-level access, full export, durable operations, and basic restore through CLI/API.

**Controlled pilot** adds a qualified MRI conversion profile, XNAT export/API migration, one restricted sharing profile, limited delegation, a bounded derivative-return demonstration, a Neurobagel projection fixture, performance qualification, and lifecycle/restore hardening.

**Later expansion** adds the graphical interface, broader modalities and disclosure profiles, richer institutional identity administration, production federation, and selected execution backends including a production fMRIPrep workflow.

| Capability | Core alpha | Controlled pilot | Later |
| --- | --- | --- | --- |
| Native BIDS preservation and supported interpretation | Required | Required | Broader profiles |
| Exact releases, curation, full export | Required | Required | Additional editing tools |
| Correlated queries and frozen release selections | Required | Required | Additional operators |
| Dataset actions and current authorization | Required | Required | Rich administration |
| Restricted participant/acquisition/metadata view | Domain contract | One qualified profile | More profiles |
| Delegation | Distinct action in model | One bounded child-grant level | Richer chains |
| DICOM | Source/derivation contracts | Qualified MRI profiles | Additional conversions |
| XNAT | Qualified source-reference contract | Export and API routes | Further source variations |
| Derivatives | Ordinary draft contract | One approved producer, separate destination | Production pipelines/schedulers |
| Neurobagel | Projection contract | Compatible fixture | Operated production integration |
| UI | Durable resources and HTTP/CLI | CLI/API | Graphical workflows |
| Object storage | Adapter contract | Filesystem default | Object-store adapter as needed |

The pilot excludes general arbitrary-code execution, automatic participant identity merging, arbitrary user-authored policy languages, unrestricted third-party plugins, global federated snapshots, generic anonymization guarantees, and multi-region availability. It also excludes treating BIDS acceptance as a guarantee of clinical or scientific validity beyond the qualified checks.

## 5. Scientific support profile

Preservation, interpretation, conformance assessment, and conversion qualification are independent support claims. A release may preserve unsupported content under a declared admission policy, but queries and transformed exports must identify unsupported semantics instead of pretending to understand them.

Proposed pilot qualification:

| Data/profile | Preservation | Interpretation | Automatic conversion |
| --- | --- | --- | --- |
| T1w, T2w | Yes | Required | Required after qualification |
| Single-echo resting/task BOLD | Yes | Required | Required after qualification |
| Supplied task events | Yes | Required for supported tasks | Import supplied data; do not invent events |
| SBRef | Yes | Required | Required after qualification |
| One fieldmap family | Yes | Required | Reverse phase-encoding EPI is the proposed default |
| DWI | Yes | Native indexing/relationships within declared coverage | Deferred unless explicitly traded into scope |
| Multi-echo, quantitative MRI, other modalities | Safe retained content | Declared coverage only | Separate qualification |

P0 must record actual scanner/protocol examples, selected BIDS schema, source formats, fieldmap rules, deidentification/disclosure requirements, and a scientific reviewer. Missing acquisition facts remain findings. dcm2niix explicitly documents that information absent from its source headers cannot be recovered by the converter. [Converter documentation](https://github.com/rordenlab/dcm2niix/blob/master/BIDS/README.md).

## 6. Non-negotiable core

1. Exact content, interpretation, assessment, and acceptance have distinct identities.
2. Every scientific producer uses the same preparation and acceptance protocol.
3. Dependencies and metadata origins are explainable, and changes examine old and new applicability.
4. Prepared views identify exact selections and authorized representations.
5. Current authority remains independent of immutable scientific identity.
6. Consequential transitions leave durable evidence and truthful completion states.

The pure kernel holds records and transition rules. The operational core must enforce storage readiness, transaction boundaries, resource ownership, and current authority. Type-level distinctions do not replace these runtime obligations.

The required laws are:

| Law | Observable meaning |
| --- | --- |
| Fidelity | Full export reproduces the declared retained logical tree and file bytes |
| Historical meaning | Later software or curation cannot silently change an earlier release's interpretation |
| Atomic visibility | A reader observes a complete accepted state |
| Idempotent acceptance | One scoped key and payload resolves to one accepted result; changed intent conflicts |
| Correlation | Predicates preserve declared artifact/session/participant/dataset scope |
| View agreement | Queries and materialization agree under the view's declared interpretation and transformations |
| Authority containment | Closure, subsetting, delegation, and execution cannot widen permission |
| Bounded disclosure | Excluded information cannot influence disclosed deterministic outputs except through explicitly approved disclosures |
| Truthful accounting | Completion claims match the declared scope, verified content, and dispositions |
| Recoverable lifecycle | Cancellation, crash, retention, and restore cannot silently corrupt accepted state |

## 7. Domain vocabulary and lifecycle

| Record | Identity and required meaning |
| --- | --- |
| Dataset | Durable organizational identity, custody, and accepted head |
| Artifact | Logical identity preserved only through explicit edit/rename lineage |
| Blob | Exact byte identity with digest algorithm and length |
| Manifest / ContentRevision | Path-sensitive immutable tree, explicit directories, artifact/content references, and pinned composition |
| Interpretation | Exact content plus versioned schema, resolver, bids4s/profile/configuration and semantic results |
| Assessment | Completed findings against identified inputs, tool versions, and configuration |
| Release | Accepted content/interpretation/evidence binding and admission receipt |
| Draft | Durable change proposal, expected base, generation, author, and working evidence |
| PreparedCandidate | Frozen candidate and applicability/readiness evidence used at acceptance |
| ViewSpec | Selection and disclosure intent against a declared release universe |
| PreparedView | Resolved membership, transformed manifest, interpretation, profile, and derivation |
| MaterializationReceipt | Exact emitted logical tree and materializer identity |
| Activity / Derivation | Inputs, decisions, software/configuration, outputs, and qualified actor evidence |
| Grant | Recipient, scope, actions, constraints, authority source, and optional parent |
| Operation | Durable progress, attempt history, checkpoints, outcome, and result references |

Source paths need safe preservation even if they fail BIDS naming rules. Digests identify bytes, not people. BIDS labels and external labels are scoped identifiers. Composition pins child releases; the first pilot may reject transformed views crossing unsupported composition boundaries.

Drafts support editing, preparation, blocked findings, ready candidates, stale candidates, acceptance, and abandonment. Editing during preparation creates a new generation; a completed older candidate cannot become applicable to that new generation.

Interpretations and assessments are immutable completed records. Reassessment is allowed without accepting a new release. Reinterpretation of the same content requires explicit acceptance before becoming a dataset's new accepted state.

A prepared view is immutable while availability and authorization remain current. Releases and views never encode a perpetual permission grant.

Operations may be queued, running, waiting for a curator, retryable, completed, failed, or cancelled. A completed preparation operation does not mean a release was accepted; a completed run does not mean its outputs were accepted.

## 8. Primary user journeys

### Native import and correction

A curator inventories a directory, reviews preservation and conformance findings, and accepts a release. The curator inspects an acquisition's effective metadata and origins, proposes an authored-document correction, reviews the affected acquisitions and references, and accepts a second release. A researcher can export either retained release and explain why their metadata differs.

### Legacy migration

A curator declares an XNAT installation/project/resource scope. Sanctity inventories accessible objects, records inaccessible portions, acquires bytes, converts qualified acquisitions, and proposes mappings. The curator resolves ambiguity. Interrupted work resumes. Final reporting separates source accounting, curation, scientific verification, and cutover readiness.

### Restricted collaboration

A custodian prepares a fixed participant/acquisition view with approved metadata fields. A collaborator receives read/use permission without source-editing or automatic delegation. The same disclosure rules govern query predicates, counts, explanations, receipts, and downloaded content. Revocation affects further access and bounded continuing access according to the declared enforcement contract.

### Derivative return

The collaborator obtains an exact pipeline bundle and a bounded run assignment. An approved producer records software and parameters and deposits outputs into another custodian's draft. A failed collection resumes. Normal assessment and acceptance publish the derivative internally; external disclosure is a separate action.

### Recovery

An operator restores catalog and blobs from the documented backup set, reconciles current lifecycle/access decisions, verifies selected releases and pending operations, and enables service. A withdrawal made after the backup must not be silently undone.

## 9. Functional requirements

Requirements marked **A** are due at core alpha. **P** requirements are additional controlled-pilot obligations. A requirement's schema/contract may be introduced earlier than its complete behavior. Evidence labels refer to the existing [proof catalog](decisions-and-evidence.md); AT labels identify additional scenarios defined below.

### Scientific state and acceptance

| ID | Gate | Requirement and acceptance criterion | Evidence |
| --- | --- | --- | --- |
| K-01 | A | Preserve an explicit logical inventory with safe relative paths, file bytes, directory entries, and a versioned path-sensitive manifest identity. Reordering ingestion leaves identity unchanged; renaming changes it. | PROOF-01; AT-01 |
| K-02 | A | Keep dataset, artifact, byte, participant-label, and external-source identities distinct. Edit lineage is explicit; label/hash collisions never merge people automatically. | PROOF-23; AT-01 |
| K-03 | A | Bind interpretation to exact content and semantic implementation/profile/configuration; preserve historical semantic results through upgrades. | PROOF-03 |
| K-04 | A | Record assessment inputs, validator/configuration, findings, and admission decision. Preservation and acceptance status must not imply full BIDS or scientific qualification. | PROOF-03; AT-02 |
| K-05 | A | All scientific edits enter versioned drafts with base references. Creation of a dataset has an explicit empty base. Concurrent edits cannot lose updates. | PROOF-08 |
| K-06 | A | Preparation binds exact candidate content, interpretation, required assessments/indexes, and readiness evidence. Changed drafts/bases invalidate applicability. | PROOF-06; PROOF-08 |
| K-07 | A | Accept using one atomic catalog transaction with current authority, expected-head/generation checks, scoped idempotency, audit, and outbox. Retry returns the same authorized receipt; reused key/different payload conflicts. | PROOF-06; PROOF-07 |
| K-08 | A | Every accepted import/edit identifies its inputs, activity, decisions, and outputs. Restricted evidence is disclosed only with authority. | PROOF-02; AT-03 |
| K-09 | A | Distinguish content findings, unsupported capability, conflict, denied access, operational failure, cancellation, and unavailable retained content in stable API outcomes. | AT-02; AT-09 |

### BIDS interpretation and queries

BIDS metadata applicability and precedence can span a dataset hierarchy. Sanctity's implementation must use the selected schema's rules and make resolution evidence inspectable. [BIDS inheritance](https://bids-specification.readthedocs.io/en/stable/common-principles.html#the-inheritance-principle).

| ID | Gate | Requirement and acceptance criterion | Evidence |
| --- | --- | --- | --- |
| S-01 | A | Preserve authored metadata bytes separately from parsed values and effective values. Explain field origins, overrides, invalid values, and unresolved applicability within supported coverage. | PROOF-02; PROOF-09 |
| S-02 | A | Represent typed dependencies and associations, including legitimate groups. Missing/ambiguous/unsupported relationships produce explicit findings. | AT-04 |
| S-03 | A | Preview authored edits, renames, and new sidecars using old and proposed applicability; enumerate affected artifacts and reference repairs/findings. | PROOF-02; PROOF-09 |
| S-04 | A | Query artifact, acquisition, session, participant, and dataset scopes with explicit correlation. The reference and SQL interpreters agree for supported operators. | PROOF-04; PROOF-24 |
| S-05 | A | Freeze exact releases and resolved membership for saved selections; bind pagination to that universe. Report changed authority rather than silently reducing a supposedly complete result. | PROOF-05; PROOF-12 |
| S-06 | A | Distinguish absent, invalid, and typed values while retaining lexical form. Unsupported query operators fail explicitly; operational live views include observation context. | AT-05 |
| S-07 | A | Separate physical index generations from scientific interpretation. Reindexing proves equivalence or requires a new interpretation; required indexes become visible with the release. | PROOF-03; PROOF-06 |

### Views and materialization

| ID | Gate | Requirement and acceptance criterion | Evidence |
| --- | --- | --- | --- |
| V-01 | P | Compile a ViewSpec into resolved membership, a dependency-aware transformed manifest, interpretation, and derivation. A saved query alone is not a prepared view. | PROOF-10 |
| V-02 | A | Full export reproduces declared retained paths and bytes, including unsupported preserved files. Verify emitted content; isolate writable copies from canonical blobs. | PROOF-01; PROOF-21 |
| V-03 | P | Support one documented participant/acquisition/field disclosure profile with table filtering, metadata projection, companions, and reference handling. Forbidden dependencies yield an allowed transformation or a blocked result. | PROOF-10 |
| V-04 | P | Querying the prepared representation and reading its materialized BIDS produce equivalent supported values under declared transformations. | AT-06 |
| V-05 | P | Subsetting an existing view cannot recover excluded data. Restricted source lineage stays private; deterministic disclosed outputs obey the profile's explicit information limits. | PROOF-11; AT-03 |
| V-06 | P | One approved pipeline profile adds explicit input requirements and emits an exact bundle manifest/receipt. Unsupported bundles are rejected. | PROOF-15; AT-04 |

### Authority and custody

| ID | Gate | Requirement and acceptance criterion | Evidence |
| --- | --- | --- | --- |
| A-01 | A | Use stable principals, verified login bindings, memberships, and dataset custodians. One OIDC provider or equivalent selected institutional adapter is sufficient for the pilot. No email-only merge. | PROOF-13 |
| A-02 | A | Distinguish discovery, metadata/content/source reading, proposing, accepting, executing, depositing, delegating, and disclosing. Test each action independently. | AT-07 |
| A-03 | P | Enforce dataset scopes and fixed prepared-view scopes with explicit actions/expiry. Deny unsupported constraints and access to future releases unless separately authorized. | PROOF-10; PROOF-14 |
| A-04 | A | Apply supported scope/field rules to listings, counts, filter/sort predicates, previews, explanations, errors, receipt fields, and byte reads. Denied fields cannot be used as an undeclared inference channel. | PROOF-11; AT-03 |
| A-05 | A | Reauthorize new requests after a local revocation commits; bound continuing deliveries/jobs by an explicit lease contract. Recheck cache reuse and receipt retrieval. | PROOF-12; AT-08 |
| A-06 | P | Support one level of bounded delegation over finite views/actions/audiences/expiry. Child grants cannot outlive or widen parent authority; revocation invalidates dependent grants. | PROOF-14 |
| A-07 | P | Give a run only its exact input, approved recipe, destination draft, reporting capability, resource limits, and expiry. It cannot accept releases or act with its initiator's general credentials. | PROOF-15 |
| A-08 | P | Independently authorize input use and destination deposit/acceptance; record applicable input obligations and destination disclosure decision. Provenance grants no authority. | PROOF-15 |
| A-09 | A | Protect source DICOM, participant mappings, credentials, logs, and arbitrary identifying metadata separately from curated/discovery data. View acceptance is not an anonymization certification. | AT-03; AT-07 |

### Ingestion and migration

XNAT documents separate scan-resource and image-assessor download paths. The pilot inventory must explicitly select which accessible resource classes belong to its migration scope. [XNAT download API](https://wiki.xnat.org/xnat-api/how-to-download-files-via-the-xnat-rest-api).

| ID | Gate | Requirement and acceptance criterion | Evidence |
| --- | --- | --- | --- |
| I-01 | A | Native import creates a durable declared scope and inventory; account for accepted, rejected, ignored-with-reason, and inaccessible entries. Pin nested dataset boundaries. | PROOF-01; AT-01 |
| I-02 | A | Acquire and hash content in bounded streams. Sizes/timestamps may prioritize work but cannot prove equal bytes. Source mutation during acquisition cannot produce a false verified snapshot claim. | PROOF-17 |
| I-03 | P | Version mapping profiles and decisions, retain source evidence/overrides, and persist subject/session/run assignments. New discoveries cannot silently renumber accepted acquisitions. | PROOF-18 |
| I-04 | P | Pin conversion tool/configuration, support many-to-many input/output relationships, and qualify the agreed MRI profiles with independent scientific review. | PROOF-19 |
| I-05 | P | XNAT export/API routes converge on one source model; retain installation/native/parent/project identity, resume acquisition, and distinguish inaccessible from empty scope. | PROOF-17; PROOF-23 |
| I-06 | P | Reconcile source changes using declared observation/version evidence. Report inconsistent or weakly observed sources; do not claim a global source snapshot from weak listings. | PROOF-17 |
| I-07 | P | Produce a receipt with inventory, digests, transformations, dispositions, assessments, and destination references. Accounting completion excludes pending, failed, unknown, or unresolved objects; cutover also requires curation, science, and recovery gates. | PROOF-17; PROOF-19 |

### Operations and extensions

| ID | Gate | Requirement and acceptance criterion | Evidence |
| --- | --- | --- | --- |
| O-01 | A | Persist operations/checkpoints/leases; bound retries and concurrency; distinguish retryable failure from curator action. Cancellation releases resources and preserves recoverable evidence. | PROOF-06; AT-09 |
| O-02 | A | Record external work in an atomic outbox; consumers are idempotent and generation-aware. External failure cannot expose an incomplete accepted release or mark obsolete work current. | PROOF-20 |
| O-03 | P | Implement retention roots, staging/materialization leases, integrity verification, conservative collection, withdrawal, and unavailable-content reporting. Active/retained roots survive collection races. | PROOF-21; AT-10 |
| O-04 | A | Back up catalog and blobs coherently and restore representative releases/jobs; reconcile current access/lifecycle restrictions before serving. Production RPO/RTO qualification is due at pilot. | PROOF-22 |
| O-05 | A | Expose actionable structured logs, operation metrics, audit events, queue age, storage/integrity status, and correlations without source secrets or unauthorized metadata. | AT-09 |
| O-06 | P | Qualify schema/index upgrades and rollback/recovery procedure using a restored copy; unsupported old interpretations remain explicit rather than silently rewritten. | PROOF-03; PROOF-22 |
| E-01 | P | An external approved producer obtains a bundle and returns a derivative through the ordinary draft API; interrupted collection resumes without an accepted partial derivative. | PROOF-15; PROOF-16 |
| E-02 | P | Produce one approved Neurobagel-compatible imaging/phenotypic fixture through supported export/query contracts, recording release/profile/generation and downstream tool acceptance. | PROOF-20; AT-11 |
| E-03 | A | Provide CLI and versioned HTTP resources for drafts, findings, impact, acceptance, queries, exports, operations, and evidence. The later UI requires no alternate scientific write path. | AT-12 |

## 10. CLI and API experience

The CLI is the first curator interface and must be usable without SQL or storage-path knowledge. HTTP and CLI call the same application operations.

The API must expose durable, addressable resources for datasets/releases, drafts, preparation candidates, assessments/findings, source inventories, mapping decisions, saved selections, prepared views, grants, runs, materializations, and operations. Large result sets are paged; byte transfer and long-running work are streamed or represented by operation handles.

A read response identifies its scientific target and any observation time. Mutations identify their expected generation/base and idempotency scope. A denied read must not reveal a forbidden resource's existence through avoidable differences in returned details. Authorized operators may receive fuller diagnostics through a separate scope.

Findings carry a stable code, severity, affected object/path where disclosable, evidence, and supported next actions. Operational errors expose retryability and correlation identifiers. Clients must not need to infer completion from a log message or process exit code.

A curator must be able to review an impact report before acceptance. An automation may accept under an explicit policy, but it receives the same exact candidate and receipt semantics.

Suggested operation families are design names, not a frozen REST path specification:

| Family | Necessary operations |
| --- | --- |
| Dataset | Create with custodian; inspect head/history; inspect retained availability |
| Draft | Create; append/replace changes with generation check; inspect diff; abandon |
| Preparation | Start; inspect exact candidate/findings/impact; retry; cancel |
| Acceptance | Accept prepared candidate; inspect receipt; resolve conflict |
| Query | Select pinned releases/view; page; explain supported metadata; freeze membership |
| View | Plan supported projection; inspect blocked dependencies; prepare |
| Delivery | Materialize/download; inspect receipt; resume under current authority |
| Import | Declare scope; inventory; acquire; review mapping; reconcile; report |
| Access | Grant/revoke supported actions; inspect effective scope with authority |
| Run | Assign approved recipe/input/destination; report; collect outputs; inspect lineage |
| Operations | Inspect attempts/checkpoints; cancel/retry supported work |

## 11. Nonfunctional requirements and qualification targets

The reference environment, fixture generator, workload definitions, runtime versions, and raw results must be recorded with each benchmark. P0 chooses a representative deployment; P2 establishes the baseline. Changing a numerical target requires a documented scope/capacity decision. Correctness and authority laws cannot be traded away to meet a timing target.

| ID | Target or requirement | Qualification method |
| --- | --- | --- |
| NF-01 | Warm supported selective query: below 250 ms p95 for a 100-row page over 1 million entries, with 20 concurrent readers | Include authorization, database execution, and response serialization; exclude client network transfer. Fix selectivity/row width. Also report cold behavior and concurrent-write behavior. |
| NF-02 | Final acceptance: below 1 second p95 after preparation, with 10 competing clients | Report independent-dataset and same-head contention separately, successful and conflicting outcomes separately, and preparation duration separately. |
| NF-03 | Bounded streaming of a 100 GiB payload under a fixed configured memory budget | Size memory for deployment in P0; prohibit buffering whole payloads or whole migrations. Record resident memory, queue limits, cancellation, and throughput. |
| NF-04 | Metadata-only edits add no image-payload storage | Measure bytes plus catalog/index growth over at least 100 small revisions; identify the scale at which the baseline layout needs improvement. |
| NF-05 | Pilot recovery point target at most 15 minutes; catalog/API recovery at most four hours when blobs survive | Restore drill covers content, current authority/lifecycle reconciliation, pending work, and exact release retrieval. Full blob-store loss gets a separate measured estimate. |
| NF-06 | Committed local revocation affects new authorization checks immediately; continuing mediated use rechecks within at most 60 seconds in the pilot | Exercise cached export, resumed download, child grants, and the controlled worker. Reject execution/delivery modes that cannot meet the advertised bound. Copies already delivered remain outside this guarantee. |
| NF-07 | Secrets and restricted source data do not appear in general logs, metrics, errors, cache keys exposed to users, or unauthenticated delivery | Negative fixtures across API, CLI, diagnostics, and storage handles; authenticated transport and deployment-specific at-rest controls before real data. |
| NF-08 | Resource acquisition, cancellation, crash, and bounded retries leave coherent accepted roots and recoverable work | Failure injection across staging, verification, indexing, acceptance, collection, and outbox delivery; include disk-full and database-unavailable cases. |
| NF-09 | Pure kernel has no adapter imports, effect runtime, network/database handles, or modality-specific storage hierarchy | Build dependency enforcement, public contract tests, and two extension demonstrations; keep F at actual capability boundaries. |
| NF-10 | Supported deployment can be installed, upgraded, observed, backed up, and restored by an operator following supplied runbooks | Rehearse on a clean environment with configuration validation, dependency versions, disk/queue limits, health/readiness signals, and rollback/recovery steps. |

No availability percentage or full-store-loss RTO is promised until deployment topology and recovery evidence support it. External identity-provider membership changes have a separately declared synchronization bound; the local 60-second lease rule cannot claim immediate knowledge of changes the repository has not received.

## 12. Acceptance scenarios

The existing PROOF-01 through PROOF-24 remain the foundation. The scenarios below close product-specific gaps and make the PRD directly testable.

| ID | Scenario | Required result |
| --- | --- | --- |
| AT-01 | Same bytes at different paths; reordered inventory; explicit empty directory; path escape/collision; symlink; nested dataset | Deterministic logical identity; safe declared preservation behavior; no silent rewriting, traversal, or boundary merging |
| AT-02 | Valid content, malformed metadata, unsupported modality, validator operational failure, withdrawn bytes | Distinct preservation/interpretation/assessment/availability outcomes; admission follows the stated policy |
| AT-03 | Restricted participant field and restricted source reference across counts, predicates, errors, explanations, receipts, logs, and views | Only explicitly permitted information is disclosed; private provenance remains private |
| AT-04 | Companion group, multiple legitimate association members, competing interpretations, and missing tool-required input | Correct grouped relationship or typed finding; no arbitrary nearest-file choice |
| AT-05 | Missing field, invalid number, lexical bounded value, unsupported regex, and live derivative status | Documented distinct outcomes; no silent null coercion or unsupported operator fallback |
| AT-06 | Projected sidecar/table modifies supported metadata representation | Query values equal materialized interpretation after the declared transformations; full source exports still preserve original bytes |
| AT-07 | Multiple principals and memberships; read/propose/accept/delegate/deposit powers varied independently | Correct action checks and stable custody/attribution; no implicit privilege inheritance from a login or provenance edge |
| AT-08 | Revoke a grant during a cached/resumed transfer and an approved run; policy service unavailable | New checks deny, continuing use obeys its lease bound, unavailable authority cannot extend a lease |
| AT-09 | Cancel/retry at each long-running step; exhaust storage; crash worker; fail remote call | Truthful durable status, released resources, bounded retries, safe resume, useful redacted diagnostics |
| AT-10 | Concurrent collector, active export, abandoned draft, retained old release, withdrawn shared blob | Active roots protected; authorized collection respects all references; withdrawal semantics are explicit even when bytes remain for another authorized reference |
| AT-11 | Supported Neurobagel fixture with approved phenotypic annotations and imaging availability | Selected downstream version accepts the output; unsupported mappings are findings; no private local path or source identifier is disclosed |
| AT-12 | Curator completes native import/correction and pilot mapping through CLI/API | No direct SQL, manual blob editing, or private adapter API needed; documents and operation receipts explain each step |

Neurobagel's imaging preparation contract uses a BIDS metadata TSV with subject/session/suffix/path fields and supports generation through its CLI. The pilot must qualify the selected tool version against an approved view and appropriately annotated phenotypic inputs. [Neurobagel imaging preparation](https://neurobagel.org/user_guide/preparing_imaging_data/). Production federation is outside this acceptance scenario.

## 13. Product release gates

| Gate | Required outcome | Acceptance owners |
| --- | --- | --- |
| G0 | Named pilot, scientific profile, source/fixture access, storage/identity environment, dependency baseline, decision ownership | Product, science, technical lead, operator |
| G1 | Native semantic demonstration: import, explain, correct, preserve both states, export exactly | Technical lead and curator |
| G2 | Core alpha: all A requirements within the alpha profile; transaction/query/access/retry/restore evidence | Technical lead, operator, curator |
| G3 | Restricted view demonstration: correct projection, query/materialization agreement, current authority, bounded delegation | Custodian, technical lead, curator |
| G4 | DICOM qualification: every advertised conversion profile has independent scientific evidence | Scientific lead and curator |
| G5 | Scoped migration rehearsal: XNAT export/API convergence, interruption recovery, complete accounting and reconciliation | Curator, source owner, operator |
| G6 | Controlled pilot: all P requirements, nonfunctional qualification, extension proofs, restore drill, accepted source cutover report | Product, science, custodian, operator |

A gate is complete only when its requirement-to-evidence manifest is attached. Unsupported coverage is explicitly documented. No unresolved failure of a core law can be relabeled as a harmless limitation.

G6 requires all four migration dimensions to pass: accounting, curation, scientific verification, and cutover readiness. A source owner may authorize a narrower scope before a rehearsal; exclusions remain visible and do not erase inaccessible or unknown material.

## 14. Operational rollout

Use synthetic/deidentified fixtures first, then a representative read-only source rehearsal, then a full scoped rehearsal, then a controlled real pilot. Production data access requires the selected identity, storage, disclosure, and restore configuration to have passed their corresponding gates.

The pilot begins with a bounded cohort and explicit operator support. Run Sanctity and the legacy source side by side until reconciliation, retrieval, and recovery are accepted. The migration contract does not authorize deleting or modifying XNAT data.

Rollback before cutover keeps XNAT authoritative and retains Sanctity's receipts/staged work for diagnosis. After cutover, recover Sanctity through the documented journal/backup procedure; do not imply that resuming an old source automatically reconstructs later curation or access decisions.

The product owner, custodian, source owner, scientific lead, and operator must identify who authorizes operational cutover. This is a future product decision, not an approval requested by this documentation task.

## 15. Dependencies, risks, and decisions

| Risk or dependency | Product consequence | Control / decision due |
| --- | --- | --- |
| bids4s pre-release API/semantic gaps | Core behavior cannot be assumed from a dependency name | Pin revision; conformance ledger; upstream work in P0/P1 |
| Unknown XNAT custom resources/access | Migration completeness and estimate remain uncertain | Source survey in P0; representative export/API rehearsal before committing G5 scope |
| Converter success mistaken for scientific correctness | Incorrect data could be accepted as qualified | Independent per-profile qualification and explicit coverage |
| Projection complexity | Sharing may leak information or produce incoherent BIDS | One narrow supported profile; blocked unsupported transformations; G3 |
| Historical index/storage amplification | Retention cost could grow faster than payload size | P2/P6 measurements; internal optimization without changing object contracts |
| Authority drift and restore | Revoked access could reappear | Local policy versioning, bounded leases, restore reconciliation; G2/G6 |
| External-tool/identity availability | Preparation or use stalls | Durable statuses, bounded retry, declared identity refresh, explicit fallback limits |
| Team/fixture availability | Calendar estimates lose meaning | Named capacity and source/scientific owners at G0 |
| Kernel abstraction growth | First useful slice delayed; extensions require generic machinery | G1 executable demonstration; defer abstractions without two concrete uses |

Required early decisions correspond to OPEN-01 through OPEN-16 in the existing register. P0 must at minimum close pilot scope/owners, preservation contract, dependency pin, initial scientific/admission profile, deployment/identity baseline, and measurement conditions. Later decisions have explicit owners and deadlines in the implementation plan.

## 16. Change control and document ownership

Requirement IDs are stable. A change records its reason, affected requirements, acceptance evidence, supported profiles, and schedule impact. Removing a pilot feature is a scope decision; weakening a core law requires a new architectural decision and explicit consequences.

The product owner owns outcomes/scope, the technical lead owns architecture and consistency, the scientific lead owns qualification claims, the custodian owns disclosure policy, and the operator owns deployment/recovery acceptance.

The baseline now includes bounded implementations of the four requested build-out areas: correlated scientific queries and frozen selections; MRI disclosure views accepted through the common lifecycle; durable import/delivery leases and coordinated recovery; and initial DICOM/XNAT adapters. Action-specific dataset and fixed-release grants establish their authority boundary.

The current acceptance evidence is 108 passing Sanctity tests, 128 bids4s JVM tests and 109 Scala.js tests, compiled library documentation, independent-process/crash recovery, a real catalog/blob restore that preserves a later revocation, a real pinned dcm2niix synthetic conversion with an independent voxel/world-coordinate check, XNAT API/export agreement including loopback HTTP, and authenticated repository HTTP curation/sharing/revocation with private operator credential-file handling. [Run instructions](../README.md) and [implementation evidence](implementation-plan.md) state the exact profiles and limitations.

The [authenticated HTTP profile](http-api.md) now exposes exact releases, metadata, corrections, acceptance, grants, frozen selections and file delivery. Its opaque API credentials authenticate stable principals; this does not complete institutional login or membership requirements. Recovery invalidates all restored credentials, and operators provision fresh ones after authority reconciliation. E-03 is partially implemented; remote ingestion, view preparation and operational/evidence interfaces remain open.

The immediate engineering priority is now the [scientific core](scientific-core.md). Explicit B0 fieldmap groups and IntendedFor relationships are recorded with field-origin evidence, versioned interpretation identity, bounded closure and correction impact. Missing references block new acceptance; old interpretation identities remain stable. MRI subset planning refuses to drop declared inputs from other sessions. Companion-file resolution and image/table consistency follow; institutional-login expansion is deferred.

This advances portions of K-07/K-08, S-02/S-03/S-04/S-05/S-06, V-01/V-03/V-04/V-05, A-02/A-03/A-05, I-01/I-02/I-04/I-05/I-06/I-07, and O-01/O-02/O-04. It does not mark those broad requirements or the production gates complete. Institutional identity/membership/delegation, broader HTTP workflows, general association closure, representative scientific qualification, full migration reconciliation, withdrawal, metrics and performance qualification remain open. Actual pilot data, acceptance owners, deployment and staffing remain explicit inputs.
