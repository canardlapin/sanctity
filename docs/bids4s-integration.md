# bids4s integration status

Updated 10 October 2026.

Sanctity uses the sibling checkout at `../bids4s`, whose canonical repository is [canardlapin/bids4s](https://github.com/canardlapin/bids4s). The inspected base commit is `ed67e0e1578e752fe95c41387eff3458b3bb59db`. The metadata and relationship improvements are committed and pushed as `6308ed506d3a8b1f86fc022a9eeeec94ad35dd4b`; this is the companion revision for Sanctity's initial commit, not a published dependency version. The companion-selection and DWI-consistency additions are committed locally as `980a18663066d92b0f7b3aac092d3a43424748bf`; that commit has not been pushed.

The project owner has authorized refining, upgrading, and optimizing this library as Sanctity's requirements reveal reusable BIDS needs. Its repository guidance requires a pure portable domain, JVM-only effectful adapters, checked public failures, deterministic diagnostics, and JVM plus Scala.js verification.

## First implementation pass

The original resolver scanned the complete manifest per query, could inherit raw metadata across a derivative boundary, ordered ambiguous same-directory sidecars by specificity/path, recursively merged object-valued fields, and exposed no field-origin record.

The new portable `BidsMetadataResolver` indexes JSON candidates by directory and suffix for reuse. `BidsProject.resolveMetadata` exposes:

- Effective JSON metadata.
- The selected dataset boundary.
- Ordered contributing source documents.
- Final origins for top-level keys.
- Per-key contribution history, including overridden values.

Boundaries come from declared derivative roots and inventoried nested `dataset_description.json` files. The deepest applicable root wins. Same-directory ambiguity and applicable inventoried sidecars without decoded content produce typed failures.

Inherited JSON uses top-level key replacement; a lower-level object replaces the value of the same key. The existing general-purpose `JsonValue.merge` helper keeps its recursive behavior. Direct, non-inherited metadata access remains available.

The synchronous and effectful checked loaders now expose resolution failures as content diagnostics; strict loading rejects those findings. Permissive loading remains permissive, while metadata queries return the relevant typed failure.

These are pre-release semantic corrections. Consumers that relied on cross-dataset leakage, same-directory precedence, or recursive inherited JSON merging must adapt. The library's loading/validation guide documents the behavior and examples.

## Evidence

The required `sbt compileAll testAll` gate passed with 111 JVM tests and 92 Scala.js tests. The downstream first-contact module compiles against the public explanation API on both platforms. The executable documentation check, `sbt docs/mdoc`, also passed: 11 files compiled with zero errors.

Thirteen portable regression tests cover origins/history, scalar/object/null overrides, derivative and nested boundaries, root ordering, ambiguity, missing decoded content, non-applicable documents, explicit direct access, unsafe target paths, and immutable project copies. A JVM integration regression verifies both loader implementations and all three loading policies.

The performance improvement is structural: repeated resolution reuses an index instead of filtering the whole manifest. No numerical speedup claim is made; workload measurements remain part of the plan.

## What this enables

This supplies a first reusable input for PRD requirements S-01 and S-03: effective values can be tied to authored documents, and candidate interpretations can be compared while retaining source evidence.

The library does not itself implement repository impact analysis. Sanctity's first slice now compares the old and proposed stored interpretations, so newly applicable sidecars are included. It distinguishes effective-value changes from changes to source history under a retained local override.

Sanctity now includes reference content/interpretation/assessment/release records, preparation and in-process acceptance. Durable persistence, general queries, scientific views and authority remain repository responsibilities. Sanctity now implements bounded slices of each independently of the portable BIDS library.

## Current executable consumer

Sanctity's `bids` module depends directly on `ProjectRef(../bids4s, coreJVM)`. The build generates SHA-256 fingerprints of the library and interpreter source inputs for the semantic profile; a mutable checkout's version label alone is insufficient identity. This records the code used, without substituting for a reviewed dependency release and reproducible build pin.

The adapter preserves authored bytes separately, then parses bounded metadata using Circe/Jawn with duplicate keys disabled and strict UTF-8 decoding. It rejects numeric values that would lose decimal meaning when converted to the current bids4s JSON representation. JSON syntax, profile support and inheritance findings remain visible on prepared candidates; errors prevent acceptance.

The consumer profile supports one raw dataset. It rejects nested/derivative compositions explicitly while preserving their staged bytes; it does not claim that the library is incapable of their interpretation. The current `native-reference-v5` profile includes explicit NIfTI fieldmap declarations, inherited DWI gradient/BOLD event selection, bounded DWI gradient/header consistency, and selected BOLD event content/timing assessments. Full conformance, stimulus/HED semantics, implicit companions and scientific qualification remain outside this profile.

No additional bids4s source changes were needed for the native lifecycle integration. The previously recorded JVM/Scala.js evidence applies to the library; Sanctity has its own kernel and filesystem integration test gate (`sbt check`).

## Remaining upstream work

| Need | Current boundary | Planned treatment |
| --- | --- | --- |
| Explicit schema/registry context | Several parsing/classification paths still use the built-in registry | Audit and design a consistent context in WP-02/WP-04 |
| Full inherited-metadata conformance | Regression coverage improved; complete placement rules and oracle coverage remain partial | Expand specification fixtures and differential cases |
| Associations and companions | Explicit fieldmap groups and inherited DWI/event companions resolve with typed evidence/findings; implicit fieldmap companions, stimulus references and broader modalities remain open | Extend bounded profiles and independent scientific fixtures |
| Inventory/streaming scale | The existing project/manifest still holds an inventory vector | Sanctity streams durable inventories; qualify smaller reusable semantic operations upstream |
| Authored-byte preservation | The JSON model retains parsed values, including Double numbers | Sanctity stores exact original bytes; never reconstruct full export from parsed JSON |
| Complete scientific indexing | The library has no repository transaction/index lifecycle | Keep authoritative persisted interpretation in Sanctity |
| Publication and version pin | The first relationship pass is pushed at the revision above; the companion and DWI checks are committed locally at `980a18663066d92b0f7b3aac092d3a43424748bf` | Pin a reviewed revision/artifact before reproducible CI consumes them |

The upstream parity ledger remains partial. Passing these regression tests is evidence for these cases, not a blanket BIDS-conformance or PyBIDS-parity claim.

## Scientific relationship pass

The portable `BidsFieldmapResolver` now resolves explicit NIfTI `B0FieldSource`/`B0FieldIdentifier` groups and `IntendedFor` references. It retains group membership, per-field metadata origins, participant/dataset scope, and deterministic typed findings. Its batch seam reuses already resolved metadata. Existing loaders preserve their prior behavior; consumers opt into the relationship assessment.

Seventeen portable tests cover multi-member and multiple groups, participant scope across sessions, isolated datasets, inherited origins, self-reference, modern and legacy paths, missing/external/cross-boundary/unsupported targets, malformed declarations, unsafe paths, metadata ambiguity, ordering, coexisting mechanisms, folder-label mismatch and bounded expansion. The required checks passed with 128 JVM tests, 109 Scala.js tests and 11 executable documentation files. The external first-contact module compiles against the new API on both platforms.

Sanctity persists this evidence in a versioned relationship graph within interpretation identity. Canonical v1/v2 records remain readable without a graph; new records use v3 and explicitly identify their relationship profile. Correction previews include affected relationship groups, and subset planning refuses undeclared coverage or omitted cross-session fieldmaps.

This is declaration semantics and repository consistency. That pass covered explicit declarations. The following pass adds gradient/event selection; implicit phase/magnitude companions, image geometry and full processing-bundle qualification remain later work. The [scientific-core plan](scientific-core.md) is the immediate roadmap.

## Companion-selection pass

The portable `BidsCompanionResolver` indexes candidates by directory and role. It explains selected DWI gradients and BOLD events using the complete applicable hierarchy and matched entities. Checked inventories accept inherited non-JSON companion filenames. The existing JSON inheritance resolver and permissive/checked/strict loading contracts are retained.

Fifteen portable cases cover inheritance, independent gradient extensions, ambiguity, participant/session/task isolation, multi-echo events, numeric run equivalence, nested/derivative boundaries, sibling exclusion, typed absence/unsupported outcomes, scan budgets and deterministic inventory order. The downstream first-contact module exercises the public API.

Sanctity maps this into typed companion-selection evidence, canonical interpretation v4 and the `native-reference-v3` profile while retaining historical v1/v2/v3 identities. No gradient values, event rows or image-header consistency are assessed in this slice. See [scientific contracts and lifecycle proof](scientific-core.md).

Companion-pass verification: `sbt compileAll testAll 'docs/mdoc'` passed 143 JVM tests, 124 Scala.js tests, both downstream first-contact builds and 11 executable documentation files. Sanctity's complete PostgreSQL runner passed 123 tests plus crash/retry and coordinated restore; evidence is recorded in the [implementation plan](implementation-plan.md#verification-record).

## DWI consistency pass

Portable `BValues`, `BVectors`, `NiftiHeaderFacts` and `DwiConsistency` APIs now separate checked numeric/header interpretation from storage and decompression. Both first-contact targets exercise the public boundary. Sanctity supplies bounded prefix reads, explicit numeric-policy observations, exact input binding and durable assessment checks; it never reconstructs authored gradients from parsed Doubles. See [scope, limits and evidence](scientific-core.md#implemented-slice-dwi-content-consistency). The new companion and content APIs are committed locally at `980a18663066d92b0f7b3aac092d3a43424748bf`, pending upstream publication/review.

DWI-pass verification: the complete upstream build passed 159 JVM tests, 140 Scala.js tests, both first-contact builds and 11 executable documentation files. Sanctity passed 145 tests (85 ordinary plus 60 PostgreSQL), crash/retry and coordinated restore. Evidence: `target/postgres-check.s4c672/`.

## Event-content pass

`CheckedEvents` adds strict bounded quoted TSV assessment with exact decimal seconds while preserving the permissive `BidsEvents`/`EventsTable` APIs. `EventTiming` distinguishes regular, variable and unavailable context. Sanctity binds table/image/metadata inputs into ordinary checks and shares its existing bounded header reader. Sidecar validation now allows a single `VolumeTiming` value for a single stored volume and rejects negative volume times. This pass is local work beyond the recorded commits above. See [contracts and limits](scientific-core.md#implemented-slice-task-event-content-and-timing).

Event-pass verification: `sbt compileAll testAll 'docs/mdoc'` passed 182 JVM tests, 163 Scala.js tests, both first-contact builds and 11 executable documentation files. Sanctity passed 162 tests (96 ordinary plus 66 PostgreSQL), including 43 shared lifecycle cases on each catalog, plus crash/retry and coordinated restore. Evidence: `target/postgres-check.XNTo3m/`.

## Subset-materialization pass

The existing quoted-TSV scanner is now a reusable portable `CheckedTsv` API. It retains strict lexical/missing-value semantics, typed physical positions and budgets while allowing summary/event column projection and deterministic quoted output. Strict event assessment uses that scanner; permissive table/event loaders are unchanged. Both first-contact consumers exercise parse/select/render.

Sanctity owns dependency closure, authorization, field masks, reference rewriting, source/output receipts and the effectful read boundary. These repository responsibilities remain outside bids4s. See [subset contracts and limits](scientific-core.md#implemented-slice-coherent-mri-subsets). No release of either repository is implied by this local slice.

Subset-pass verification: the complete upstream build passed 187 JVM tests, 168 Scala.js tests, both first-contact consumers and 11 executable documentation files. Final Sanctity verification passed 192 tests (114 ordinary and 78 PostgreSQL), including 55 lifecycle contracts per catalog. Final tests: `target/subset-check.azzBVC/`; crash/retry and coordinated restore: `target/postgres-check.81SWyI/`.

## Scientific change preparation

The subsequent change slice uses the existing bids4s name/URI and checked TSV APIs without further upstream source edits. Sanctity owns simultaneous base-relative moves/removals, exact-plan acknowledgment, reference repair proposals, old/new dependency impact and ordinary draft admission. The versioned BIDS adapter handles IntendedFor and scans-table rewrites within its documented raw MRI profile.

Verification passed 217 Sanctity tests (129 ordinary and 88 PostgreSQL), including 65 shared lifecycle cases per catalog, plus crash/retry and coordinated restore with current revocation. Evidence: `target/postgres-check.zhdagz/check.log`. The upstream verification above was unchanged at the end of the scientific-change pass. See the [scientific change contract](scientific-core.md#implemented-slice-scientific-file-changes).

## Context and conformance pass

The local upstream tree now exposes checked `BidsContext`, canonical registry
and rule descriptors, configurable compound extensions, context-bound manifests,
metadata results and sync/effect loaders. Defaults remain supported. Manifest
construction/copy reparses files when switching contexts; the former case class
retains apply/unapply/copy entry points but is no longer a Product. This is a
pre-release source change, with no binary compatibility assertion.

Sanctity records those descriptors and source fingerprints in interpretation v5,
keeps authored BIDSVersion separate, and provides explicit same-content profile
adoption. Historical v1–v4 identities are retained. Context checking in edit/view
planners prevents an accidental switch to built-in semantics. See the
[context contract](scientific-core.md#implemented-slice-explicit-semantic-context-and-profile-evolution)
and [independent corpus](../modules/app/src/test/resources/conformance/README.md).
No official-schema interpreter or full conformance claim is added.

Context-pass verification: 194 JVM tests, 174 Scala.js tests, both downstream
first-contact builds and 11 executable documentation files passed. Sanctity
passed 235 tests (144 ordinary and 91 PostgreSQL), including 68 shared lifecycle
cases per catalog, plus crash/retry and coordinated restore. Five pinned
official-validator comparisons also passed. Evidence:
`target/postgres-check.8ZCgnu/check.log`, `target/conformance-gr6_pdnp/`, and
`../bids4s/target/context-upstream-check.log`.
