# Sanctity design baseline

Consolidated 7 October 2026 from the supplied vision, PRD and roadmap, and architectural review.

Current product requirements and delivery scope are defined in the [PRD](prd.md) and [implementation plan](implementation-plan.md). This baseline retains the architectural rationale.

Sanctity should make neuroimaging data understandable and reusable from acquisition and legacy archives through curation, collaboration, and reproducible analysis. Its defining property is agreement between stored content, queried scientific meaning, acceptance evidence, and materialized BIDS.

The first objective is a beautiful core: a small model with precise guarantees, useful explanations, strong laws, and narrow extension contracts. The implementation should earn additional complexity through demonstrated needs.

This is a consolidated design direction. Explicit user requirements, inherited proposals, and decisions still needing evidence are distinguished in [Decisions and evidence](decisions-and-evidence.md).

## Product direction

The user has established these priorities:

- Treat BIDS as the repository's native scientific language.
- Use Scala and bids4s; aim for the clarity, composability, and rigor associated with excellent Typelevel and Scala design.
- Support ingestion from DICOM, XNAT exports, and the XNAT API.
- Build a core that is performant, modular, elegant, and extensible.
- Provide a UI eventually.
- Support interoperability, particularly XNAT and Neurobagel.
- Prepare for tasks and pipelines such as fMRIPrep to consume data and return derivatives.
- Prioritize the core before building a general processing platform.

The proposed first operational outcome is a scoped XNAT-to-BIDS migration that a laboratory can inspect, reconcile, query, export, and recover. Native BIDS import is the first implementation slice because it isolates the repository's own correctness from conversion and source-system complexity.

Researchers need trustworthy selections and exact inputs. Curators need source evidence, explainable mappings, and impact previews. Operators need truthful job states, access enforcement, integrity checks, and recovery. Extension authors need public contracts that preserve the same guarantees.

## The consolidated architecture

Two complementary objects organize the system:

**A Release is an accepted scientific state.** It binds exact content, a selected interpretation, and admission evidence. Acceptance does not mean external publication.

**A ScientificView is a fixed scientific selection and prepared representation.** It is the common object for bounded querying, sharing, export, and processing. Its content identity does not grant access.

The shared lifecycle is:

```text
Sources / curator changes / derivative outputs
                    |
              Durable draft
                    |
       Prepare exact candidate and evidence
                    |
       Interpret, assess, and build indexes
                    |
          Atomically accept a Release
                    |
           Plan a ScientificView
                    |
       Materialize / deliver / execute
                    |
          Outputs return as a draft
```

Current authority governs preparation, acceptance, planning, delivery, and output deposit. External publication and projections have their own completion states.

The earliest proposal used “revision” for several of these concerns. The consolidated model separates them explicitly.

## Scientific identity and evidence

| Concept | Meaning |
| --- | --- |
| DatasetId | Durable identity of a managed dataset |
| ArtifactId | Logical managed-file identity across explicitly recorded edits and renames |
| BlobRef | Exact bytes identified by a strong digest and supporting size/algorithm information |
| ContentRevision | Immutable logical tree: relative paths, artifact identities, bytes, and pinned dataset composition |
| Interpretation | Meaning and semantic projections for exact content under an identified scientific profile |
| Assessment | Completed checking result against identified inputs and tool/configuration versions |
| Release | Accepted binding of content, interpretation, selected evidence, and admission decision |
| Draft | Durable, editable proposal with a generation and expected base |
| PreparedCandidate | Exact candidate plus applicable interpretation, assessment, index, and storage-readiness evidence |
| ScientificView | Frozen release universe, resolved membership, disclosure profile, and planned representation |
| MaterializationReceipt | Evidence for the exact emitted tree and materializer/profile version |
| Activity | Import, conversion, curation, execution, or other transformation evidence |
| ExternalRef | Qualified source identity and observation context |

A draft generation and an accepted dataset head are separate concurrency checks. Neither is a content hash.

### Identity, location, and bytes

An artifact's identity, BIDS path, and byte identity have different purposes. Two artifacts may contain identical bytes while having different scientific meanings. A path correction can preserve established artifact lineage while changing the content revision.

BIDS participant labels are dataset-scoped. Cross-dataset participant reconciliation requires an explicit mapping. Neither matching labels nor matching image bytes establish person identity.

External references include the source installation, object kind, native identifier, observation/version evidence, and relevant parent/project/label context. Local identifiers never replace this evidence.

The manifest identity must include logical names and structure. A bag of blob hashes cannot identify BIDS meaning.

### Content, interpretation, assessment, acceptance

Changing a metadata document creates new content. Changing the resolver can produce a new interpretation of unchanged content. Running another validator creates another assessment. Accepting an identified combination creates a release.

An interpretation records the selected BIDS schema/profile, bids4s and resolver identity, configuration, and relevant extension versions. Physical database index maintenance must preserve that interpretation's meaning or become explicit reinterpretation.

Assessments identify their exact inputs. Some checks examine content alone; others depend on interpretation or additional evidence. Admission policy determines which completed findings permit acceptance. It is distinct from access policy and from scientific qualification.

A later validator or resolver does not silently replace the interpretation or admission evidence of an earlier release.

### Trees, relationships, and retained content

The authoritative inventory must preserve ordinary files, directories where meaningful, companion files, and unsupported entries under an explicit preservation policy. BIDS validity is an interpreted property: raw inventory cannot require every path to be a valid BIDS filename.

Use safe relative storage paths and separately derived BIDS classifications. Path normalization, collisions, symlinks, and portable export behavior need an early decision.

Multi-file acquisitions and directory-based formats are relationships and collections. MRI-specific typed views should not become the universal artifact superclass.

Reproducibility applies while required content is retained. Authorized withdrawal or destruction can make historical materialization unavailable; retain the permitted tombstone and audit evidence instead of claiming permanent retrievability.

## Core guarantees

These are the design obligations around which interfaces and tests should be organized.

| ID | Guarantee |
| --- | --- |
| INV-01 | Accepted scientific content and interpretation never change silently |
| INV-02 | Required content, semantic indexes, and admission evidence are ready before the release becomes visible |
| INV-03 | Every producer uses the same preparation and acceptance contract |
| INV-04 | Authored bytes remain distinguishable from parsed and effective metadata |
| INV-05 | Scientific predicates preserve their artifact, acquisition, session, participant, or dataset scope |
| INV-06 | Frozen selections identify their release universe and resolved membership |
| INV-07 | Retried acceptance with the same scoped key and payload resolves to one durable result |
| INV-08 | Stale draft/head assumptions produce explicit conflicts |
| INV-09 | Current authority governs use; historical permission is evidence, not perpetual access |
| INV-10 | Scientific dependency closure cannot widen disclosure or execution authority |
| INV-11 | Each inventoried source object receives an explicit disposition before migration accounting is complete |
| INV-12 | Execution completion, output collection, release acceptance, and external delivery are distinct states |
| INV-13 | Provenance establishes relationships without implicitly granting access or assigning custody |
| INV-14 | Retained full exports reproduce the declared paths and bytes; transformed exports identify their new manifests |
| INV-15 | Resource use is bounded, streams cancel cleanly, and partial work has a recoverable lifecycle |

Prepared Scala values describe verified states at a point in a workflow. Commit-time storage, concurrency, and authorization checks remain necessary.

## BIDS semantics and metadata

bids4s owns reusable BIDS meaning: names, entities, schema interpretation, selection semantics, inheritance, standard tables, references, and diagnostics.

Sanctity owns durable inventory, revision and release identity, institutional authority, persistence, transactions, jobs, and admission workflows.

The proposed early bids4s workstream is to verify and, where necessary, improve:

- Explicit schema/registry context across parsing and interpretation.
- Inheritance correctness, dataset boundaries, ambiguity reporting, and field origins.
- Relationship and association semantics, including legitimate groups.
- Small operations suitable for streamed inventories and batched indexing.
- Query behavior shared between a pure interpreter and SQL.
- Lossless preservation independent of parsed numeric and JSON representations.

These are verification tasks inherited from the supplied analysis, not a statement about the current upstream implementation.

### Three representations of metadata

Keep authored documents, effective typed metadata, and resolution evidence connected but distinct.

A curator changing dataset-level task metadata should see which acquisitions inherit the change, which retain overrides, and which relationships or downstream inputs are affected. The edit changes authored BIDS content; an index-only metadata edit would break export agreement.

Impact analysis must examine old and new applicability. Newly added sidecars can affect artifacts that previously had no dependency edge to them.

Preserve original lexical values. Distinguish absent, invalid, and supported typed values. Domain descriptors may supply bounded-value semantics without discarding the original representation.

Metadata origins and explanations are also disclosures. A permitted effective value does not automatically authorize exposing a restricted source document or field.

Start with full semantic rebuilding over manifests and small metadata documents. Measure before introducing incremental invalidation. Metadata work should not reread large image payloads unless the operation requires it.

## Query and view semantics

A typed query algebra must make correlation scope explicit. “A female participant older than 60” requires both predicates on the same participant. “A session with T1w and resting-state BOLD” requires a shared session.

Use a pure reference interpreter and a PostgreSQL interpreter with a documented common language. Cover missing entities, padded indices, field types, bounded values, relationships, ordering, and supported text matching. Query expressiveness and query cost need explicit limits.

A frozen multi-dataset selection captures an identified release universe and resolved membership. Pagination stays pinned to that selection. New dataset heads cannot alter later pages.

Live operational views—current job status, newly arrived derivatives, or recent assessments—carry observation context and different guarantees. They must not masquerade as immutable scientific selections.

If authorization changes, a previously saved selection becomes restricted or unavailable as appropriate. Do not silently shrink it while reporting the same complete selection.

### View planning as a compiler

A view planner takes exact releases, a selection, and a supported disclosure profile. It resolves dependencies, proposes any permitted transformations, emits a manifest, and records assessment and planning evidence.

The planner must account for inherited metadata, companion files, participant/session/scan tables, fieldmaps, dataset links, and reference repairs.

If a required dependency is forbidden, it must either construct an explicitly authorized transformation or report a blocked plan. Scientific usefulness cannot authorize additional disclosure.

Filtered tables and projected sidecars become new artifacts with their own digests and provenance. They do not overwrite source releases.

A pipeline bundle has additional scientific input requirements beyond an ordinary coherent BIDS subset.

There are three materialization contracts:

| Contract | Promise |
| --- | --- |
| Full release export | Reproduce retained source paths and bytes within the declared inventory contract |
| Subset/view export | Produce an identified, assessed representation with supported dependency and disclosure rules |
| Pipeline input bundle | Satisfy an identified tool-specific input contract over exact selected releases |

Unsupported transformations fail explicitly. Writable workspaces must be isolated from canonical blobs; a writable hard link is not isolation.

Cache immutable prepared content by identity, then reauthorize its use. Neither an object digest nor a view identifier is a bearer credential.

## Authority and collaboration

Model durable actors separately from login providers and data custody.

| Concept | Purpose |
| --- | --- |
| Principal | Stable human or service actor for attribution and grants |
| IdentityBinding | Verified external login association |
| Organization / Workspace | Governed institutional or collaborative context |
| Membership | Current relationship to an organization or team |
| Custodian | Responsible party for a dataset |
| AccessContext | Principal, acting context, authentication assurance, and applicable policy context |
| Grant | Recipient, resource scope, action set, constraints, and delegation lineage |

Identity linking requires verified control or a governed recovery process. Mutable email matching alone must not grant authority. Linked identities must not weaken issuer or assurance requirements.

Distinguish discovery, detailed metadata reading, byte reading, source-evidence reading, proposing edits, accepting releases, execution, output deposit, delegation, and external disclosure. Roles can bundle actions.

Initial grants can support dataset-wide actions and fixed-release views. Reject unsupported fine scopes until their enforcement and projection semantics exist. A general policy language is unnecessary for this first contract.

Delegation may only narrow finite resource sets, field masks, actions, approved recipes, audiences, and validity periods. Reading does not imply delegation.

Input access, destination write authority, and derivative custody are independent. A collaborator can consume Institution A's authorized view and deposit outputs into Institution B's draft when both authorities permit it.

Provenance alone cannot decide derivative access obligations. Capture applicable input-use conditions and the explicit destination admission/disclosure decision.

Revocation controls future repository-mediated delivery and defined continuing access. It cannot retrieve copies already delivered. Long-running transfers and jobs require a stated enforcement boundary.

## Storage and atomic acceptance

The proposed deployment is one modular Scala service, with separately runnable workers from the same codebase.

PostgreSQL stores accepted roots, manifests/membership, semantic projections, provenance, drafts, assessments, inventories, jobs, access records, audit, and an outbox. Managed immutable storage holds exact bytes.

Use typed relational fields for common predicates and JSONB for extensible values where appropriate. Content addressing starts at whole-file granularity. Stream and hash bytes with bounded memory.

Avoid duplicating image payloads for metadata-only edits. Measure catalog, membership, and index amplification across revisions; shared bytes do not make revision metadata free.

### Preparation and acceptance

1. Stage bytes and verify their identities.
2. Construct the exact candidate manifest.
3. Produce the selected interpretation and required index generation.
4. Complete the required assessments and admission checks.
5. In one short PostgreSQL transaction, verify current authority, scoped idempotency, draft generation, expected head, and applicable preparation evidence.
6. Create the release, advance the head, and record audit and outbox entries atomically.

Bind an idempotency key to operation scope and payload. A reused key with different intent is a conflict. Returning a prior receipt must still respect current authority.

Converters, validators, uploads, and large index construction run before the acceptance transaction. The PostgreSQL adapter owns the complete transaction; independently sequenced repository calls do not provide atomicity.

Conflicting writers prepare again against the new base where semantics require it. Do not silently overwrite or mechanically reuse stale assessments.

Use durable leases and reference accounting to protect staged work, accepted content, active materializations, and recovery points from garbage collection. Storage-readiness evidence and garbage collection need a shared protocol.

Optional thumbnails, Neurobagel projections, and external publication run after acceptance through the outbox. Their status identifies the exact release and target generation, so a late worker cannot mark an obsolete projection current.

### Recovery and operations

Provide structured diagnostics, bounded concurrency, cancellation, persistent checkpoints, lease recovery, integrity verification, and backup/restore early.

A BIDS export is not a repository backup. Recovery also requires policies, custody, drafts/jobs, journals, and administrative state.

Restoring scientific state must not revive withdrawn content or obsolete permissions. A recovery-authoritative lifecycle/policy journal or a fail-closed reconciliation procedure remains an implementation decision.

## Ingestion and migration

Every route distinguishes receipt, interpretation, and acceptance. A successful transfer or process exit is only one piece of evidence.

Source adapters discover and stream objects with qualified identities and resumable observations. Mapping and conversion produce proposals; adapters never directly publish accepted state.

### Native BIDS

Inventory and preserve supplied content, identify dataset boundaries, interpret the supported profile, assess the candidate, accept it, and reproduce the full export.

Keep preservation, interpretation, BIDS conformance, and scientific qualification as separate support claims. Admission may allow explicitly classified findings under a stated policy.

### DICOM

Use a pinned dcm2niix executable/container as the proposed converter; use dcm4che within the adapter when header inspection is needed.

Keep source-to-output conversion many-to-many. Record input digests, converter identity, arguments, output inventory, mapping rules, and curator overrides.

A versioned mapping profile decides dataset/subject/session assignments, entities, associations, metadata supplementation, and exclusions. Persist run/session assignments; discovering an earlier acquisition must not accidentally renumber accepted data.

Missing events or acquisition details remain findings. Conversion success and BIDS validation do not establish scientific correctness. Qualification includes geometry, orientation, scaling, timing, companion outputs, and fieldmap relationships.

Source DICOM and identifying evidence have a distinct access scope from curated BIDS and discovery metadata.

### XNAT

Export and API adapters converge on the same internal source inventory, evidence, and disposition model. Inventory the declared scope, including relevant scan resources, assessor outputs, custom metadata, and accessories.

Preserve installation identity, accession/native identifiers, parent context, and project-specific labels. Session and participant reconciliation are explicit policies.

Distinguish inaccessible resources from empty ones. Export formats may supply different evidence; missing context becomes a finding.

Persist cursors and checkpoints. Reconcile after acquisition as appropriate for source consistency. Timestamps, sizes, and weak listings can guide work but do not prove content equality or absence of unseen changes.

Every in-scope object must be imported, represented through a recorded conversion, retained as evidence, or explicitly excluded with a reason. Pending, blocked, unknown, failed, changed, or missing objects remain visible and prevent complete accounting until resolved.

Migration completion has four separate gates:

- Accounting: every observed in-scope object has a successful terminal disposition.
- Curation: intended acquisitions meet agreed mapping and admission criteria.
- Scientific verification: qualified profiles and representative outputs have review evidence.
- Cutover: final reconciliation, source-consistency claim, retrieval, and restore pass.

A receipt can certify only its declared accessible scope and available observations.

## Scala design and module boundaries

Prefer immutable values and pure functions in the kernel. Use opaque identifiers for consequential distinctions, closed ADTs for stable operations/errors, and versioned descriptors for evolving scientific vocabularies.

Use effect-polymorphic capabilities at persistence, streaming, process, HTTP, clock, and identity boundaries. Select IO in application assembly. Use ordinary constructors and managed resources.

Independent content findings should accumulate. Operational failures, conflicts, unsupported capabilities, authorization failures, and scientific findings should remain distinguishable.

Abstractions should have observable laws. Do not invent a commutative composition instance for ordered, conflicting change sets. Do not introduce a runtime plugin loader, distributed service topology, or elaborate generic framework before an extension requires it.

| Proposed module | Responsibility |
| --- | --- |
| kernel | Identity, manifests, changes, workflow values, conflicts, provenance |
| bids | bids4s integration, interpretation, metadata resolution, assessments, materialization rules |
| application | Capabilities and preparation, acceptance, query, export, and durable workflows |
| postgres | Catalog, transactions, query compilation, jobs, audit, outbox |
| objects | Immutable storage, verification, streaming, staging, materialization primitives |
| import-dicom | Inspection, conversion, mapping, source/output accounting |
| import-xnat | API/export acquisition, source identity, reconciliation |
| app | CLI/HTTP, configuration, dependency assembly, workers |

Keep institutional policy composition out of bids4s. Keep adapter-specific classes out of kernel and application contracts. Additional modules should follow actual coupling and ownership needs.

The supplied stack preference is Scala 3, Cats/Cats Effect, FS2, http4s, Tapir, Circe, Doobie/HikariCP, PostgreSQL, MUnit, and ScalaCheck. A compatible pinned dependency set remains to be established.

## Extension contracts

### Processing

Resolve exact input views; prepare a frozen bundle; execute with bounded authority; collect outputs and execution evidence; submit a derivative draft; assess and accept through the usual pathway.

A worker's authority should be limited to its input materialization, approved recipe, destination draft, diagnostics, resource limits, and expiry. It does not inherit the initiating user's full account powers.

Record software/container identity, parameters, external resources, execution assumptions, exact input tree, generated support files, outputs, and outcome. Image hashes alone are insufficient for reuse.

Identical requests do not prove deterministic outputs. Cache reuse needs an explicit execution contract and current authorization.

A failed output collection leaves recoverable staging. Successful execution is distinct from accepted derivative publication.

A “compute without download” promise requires controlled execution and output channels. It cannot follow from an ACL flag alone.

### Interoperability

XNAT receives/acquires qualified source objects and transformation receipts. OpenNeuro/DataLad integration can exchange assessed BIDS with retained external snapshot references. Neurobagel consumes an approved versioned projection with explicit readiness and disclosure scope.

DRS can provide object retrieval while Sanctity preserves a separate path-sensitive scientific manifest identity. GA4GH identity/data-use integrations are later adapters feeding explicit local trust decisions.

Transfers report preserved, rewritten, omitted, unsupported, and unresolved information. Federation identifies contributing releases or observation states, semantic mappings, and incomplete nodes; it cannot presume a global simultaneous snapshot.

### UI

Durable drafts, findings, mapping decisions, diffs, metadata explanations, view plans, and progress are application resources from the start.

The UI can then provide a dataset explorer, import workbench, metadata inspector, revision/provenance views, and sharing/processing flows over existing operations.

## Implementation sequence

Build demonstrations that test the model, with evidence gates rather than feature counts.

| Stage | Demonstration and exit evidence |
| --- | --- |
| Foundation | Pilot boundaries, vocabulary, preservation rules, fixture ownership, and explicit invariants |
| Native semantic slice | Import native BIDS, explain inheritance, preview a correction, accept it, export both states |
| Operational core | PostgreSQL acceptance, indexed scoped queries, access, retries, recovery, CLI/API |
| DICOM | Versioned mapping and scientifically reviewed conversion with complete source/output accounting |
| XNAT | Export/API convergence, resumed migration, reconciliation, truthful scope and cutover evidence |
| Extension proof | One restricted sharing profile and one derivative producer using public contracts |
| Product expansion | UI, richer profiles, production federation, and a selected processing backend |

The first demonstration isolates semantic fidelity. The later collaboration demonstration proves that fidelity survives realistic authority boundaries:

1. Institution A accepts an MRI release.
2. A curator prepares a fixed participant/acquisition/metadata view.
3. An externally identified researcher receives a nondelegable grant.
4. Queries, counts, previews, explanations, and exports obey the same disclosure policy.
5. A bounded worker processes the exact bundle into Institution B's draft.
6. Interrupted output collection resumes without exposing an accepted partial result.
7. Normal assessment and acceptance admit the derivative.
8. A newer source release leaves the original view and run unchanged.
9. Revocation stops subsequent unauthorized repository delivery and reuse.
10. Historical receipts remain interpretable under current disclosure rules.

Reserve the contracts needed for this scenario early. Full group administration, arbitrary disclosure rules, multi-provider account recovery, production federation, and a general scheduler can follow after the restricted proof.

## Design tensions to resolve deliberately

The synthesis reveals several obligations that deserve explicit decisions rather than optimistic wording.

**Historical meaning versus implementation upgrades.** Pinning a version string is not sufficient if an old index is overwritten and cannot be reconstructed. Decide which interpreted results and recipes are retained and how physical index migrations demonstrate equivalent meaning.

**Reproducibility versus lifecycle policy.** Exact historical state does not imply indefinite retrievability. Define retention, withdrawal, permitted tombstones, and missing-content behavior together.

**Frozen scientific state versus changing authority.** Specify the enforcement boundary for acceptance, cached delivery, active streams, delegated jobs, and external identity refresh. A one-time access check cannot imply immediate revocation everywhere.

**Dependency closure versus least disclosure.** A required sidecar may be scientifically necessary and too revealing. The supported view profiles must say when approved rewriting can solve this and when the operation is blocked.

**View privacy versus provenance.** A full-source revision identifier may change when only excluded data changes. Private lineage can record that source while disclosed output identities and receipts follow an explicit disclosure profile. Do not promise noninterference while exposing an unrestricted global source hash.

**Extensibility versus a small kernel.** Stable identity and transition laws belong in the kernel. Modality rules, organizational policy, source schemas, and backend behavior evolve through versioned capabilities and descriptors.

**Pilot feasibility versus the expanded vision.** The later collaboration/security requirements add work to the earlier roadmap. The original schedule remains a hypothesis until that restricted scope and the available team are re-estimated.

The measure of success is a few concepts that carry these obligations clearly through real data and failure cases.
