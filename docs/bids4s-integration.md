# bids4s integration status

Updated 7 October 2026.

Sanctity uses the sibling checkout at `../bids4s`, whose canonical repository is [canardlapin/bids4s](https://github.com/canardlapin/bids4s). The inspected base commit is `ed67e0e1578e752fe95c41387eff3458b3bb59db`. The metadata and relationship improvements are committed locally as `6308ed506d3a8b1f86fc022a9eeeec94ad35dd4b`; this is the companion revision for Sanctity's initial commit, not a published dependency version.

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

The consumer profile supports one raw dataset. It rejects nested/derivative compositions explicitly while preserving their staged bytes; it does not claim that the library is incapable of their interpretation. The current `native-reference-v2` profile adds explicit NIfTI fieldmap declarations. Full conformance, image/gradient/event consistency, implicit companions and scientific qualification remain outside this profile.

No additional bids4s source changes were needed for the native lifecycle integration. The previously recorded JVM/Scala.js evidence applies to the library; Sanctity has its own kernel and filesystem integration test gate (`sbt check`).

## Remaining upstream work

| Need | Current boundary | Planned treatment |
| --- | --- | --- |
| Explicit schema/registry context | Several parsing/classification paths still use the built-in registry | Audit and design a consistent context in WP-02/WP-04 |
| Full inherited-metadata conformance | Regression coverage improved; complete placement rules and oracle coverage remain partial | Expand specification fixtures and differential cases |
| Associations and companions | Explicit B0 groups and IntendedFor links now resolve with typed evidence/findings; implicit fieldmap, nearest-file, bval/bvec and events remain open | Extend bounded profiles and independent scientific fixtures |
| Inventory/streaming scale | The existing project/manifest still holds an inventory vector | Sanctity streams durable inventories; qualify smaller reusable semantic operations upstream |
| Authored-byte preservation | The JSON model retains parsed values, including Double numbers | Sanctity stores exact original bytes; never reconstruct full export from parsed JSON |
| Complete scientific indexing | The library has no repository transaction/index lifecycle | Keep authoritative persisted interpretation in Sanctity |
| Publication and version pin | Companion changes are committed locally at the revision above; the build still uses the sibling checkout | Pin a reviewed revision/artifact before reproducible CI consumes them |

The upstream parity ledger remains partial. Passing these regression tests is evidence for these cases, not a blanket BIDS-conformance or PyBIDS-parity claim.

## Scientific relationship pass

The portable `BidsFieldmapResolver` now resolves explicit NIfTI `B0FieldSource`/`B0FieldIdentifier` groups and `IntendedFor` references. It retains group membership, per-field metadata origins, participant/dataset scope, and deterministic typed findings. Its batch seam reuses already resolved metadata. Existing loaders preserve their prior behavior; consumers opt into the relationship assessment.

Seventeen portable tests cover multi-member and multiple groups, participant scope across sessions, isolated datasets, inherited origins, self-reference, modern and legacy paths, missing/external/cross-boundary/unsupported targets, malformed declarations, unsafe paths, metadata ambiguity, ordering, coexisting mechanisms, folder-label mismatch and bounded expansion. The required checks passed with 128 JVM tests, 109 Scala.js tests and 11 executable documentation files. The external first-contact module compiles against the new API on both platforms.

Sanctity persists this evidence in a versioned relationship graph within interpretation identity. Canonical v1/v2 records remain readable without a graph; new records use v3 and explicitly identify their relationship profile. Correction previews include affected relationship groups, and subset planning refuses undeclared coverage or omitted cross-session fieldmaps.

This is declaration semantics and repository consistency. Implicit phase/magnitude companions, DWI gradients, task events, image geometry and full processing-bundle qualification remain work for later scientific profiles. The [scientific-core plan](scientific-core.md) is the immediate roadmap.
