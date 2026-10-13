# Coherent analysis inputs: implementation plan

Epic: `bd-01M4T933PKWMQNENXQ7NKQ3WYK`. Authorized 13 October 2026. Implementation proceeds in dependency order; mote is the live status record.

## Acceptance gate

Select a multi-echo task acquisition, materialize its coherent family/events/stimulus closure under current authority, run the qualified per-image header-QC recipe, publish the outputs, correct the source, and reproduce the original bundle/results from immutable historical evidence. Multi-echo combination is outside this tranche.

## Work items

| Key | Mote | Work | Depends on |
| --- | --- | --- | --- |
| B1 | `bd-01M4T95H0P4128JWQ613E78J7Y` | Checkpoint exact starting sources | — |
| B2 | `bd-01M4T95H55YA91AVCCX84KRZ8A` | Pin complete bids4s sources and build in isolation | B1 |
| E1 | `bd-01M4T95H917RRD4N5NKJMTNMDQ` | Resolve bounded multi-echo BOLD families | B1 |
| E2 | `bd-01M4T95J36G48A1W9P5W6AK1GM` | Check multi-echo metadata and image coherence | E1 |
| E3 | `bd-01M4T95J96NZEYVS9CR20VN5NJ` | Record versioned multi-echo relationships | E1, E2 |
| E4 | `bd-01M4T95JE5EXMAV06CJNZPYBRT` | Materialize coherent echo families in MRI views | E3 |
| E5 | `bd-01M4T95JJHWZZQ4JCS3XXWV6A6` | Preserve echo families through reviewed curation | E3 |
| E6 | `bd-01M4T95JPHQKRHM74E2MEQEGP3` | Qualify complete multi-echo recipe inputs | E4 |
| E7 | `bd-01M4T95JTY8F98KEKRV1CRXJ7N` | Verify echo selection, authority and history | E4, E5, E6 |
| S1 | `bd-01M4T95K02NVRQTREV7902T4W8` | Resolve local event stimulus references | B1 |
| S2 | `bd-01M4T95K4F0J2RPF7CEEZFMT9M` | Bind stimulus dependency evidence to interpretation | S1, E3 |
| S3 | `bd-01M4T95K8JS59NPQS5RS1G2ZRP` | Project authorized stimulus assets into views | S2, E4 |
| S4 | `bd-01M4T95KE7AFS9AQHRFAM77NGJ` | Repair stimulus references during reviewed curation | S2, E5 |
| Q1 | `bd-01M4T95KJD4VHYF31G3954E3X7` | Build independent multi-echo and stimulus corpus | B1 |
| Q2 | `bd-01M4T95KQHXPJWC19GGYJHYJRX` | Demonstrate select-to-publication historical reproducibility | E7, S3, S4, Q1 |
| Q3 | `bd-01M4T95KXZ98QGENQJKJAS2ZMG` | Run final tranche gate and document limits | B2, Q2 |

## B1: Checkpoint exact starting sources

Capture tracked and untracked project inputs in both repositories without changing supplied edits; record base revisions and per-file digests in a restorable local checkpoint.

## B2: Pin complete bids4s sources and build in isolation

Replace implicit sibling dependency with verified immutable source snapshot by default; retain explicit development override. Include license, upstream test/first-contact/docs inputs and clean-source CI. Fail on snapshot digest mismatch.

## E1: Resolve bounded multi-echo BOLD families

Portable bids4s ADT/API groups same dataset/parent/entities except echo, numeric index equivalence and lexical participant/session. Reject duplicate echoes, mixed indexed/unindexed BOLD and unsupported collection entities. Deterministic bounded outcomes; JVM/JS and public consumer coverage.

## E2: Check multi-echo metadata and image coherence

Positive finite distinct EchoTime values ordered by echo index; matching regular/variable acquisition timing and scalar NIfTI dimensions/pixel dimensions. Record exact input evidence. Define profile policy separately from BIDS validity; do not claim registration or scanner qualification.

## E3: Record versioned multi-echo relationships

Integrate symmetric family links and evidence with native interpretation/admission and content checks. Preserve legacy decoding and identities; expose correction impact without replacing historical meaning.

## E4: Materialize coherent echo families in MRI views

Expand a selected echo to its recorded family under authorized scope; retain exact image bytes, inherited events, metadata and fieldmaps. Bind all copies in projection receipt and reject masks that remove required echo metadata.

## E5: Preserve echo families through reviewed curation

Support simultaneous coherent family moves; repair supported references/scans; reject incomplete or ambiguous resulting families and stale plans. Show exact old/new dependencies and preserve historical exports.

## E6: Qualify complete multi-echo recipe inputs

Version readiness contract and producer compatibility. Bundle all family members with exact identities and independent per-image outputs; no echo combination or inferred analysis algorithm. Preserve historical contract support.

## E7: Verify echo selection, authority and history

Shared reference/Postgres lifecycle cases for selection closure, metadata correction, mask denial, current revocation, exact bytes, and immutable historical inputs/results.

## S1: Resolve local event stimulus references

Portable strict event-table stim_file resolver; root stimuli/ scope, missing n/a, repeated assets, Unicode and bounded diagnostics. Reject absolute/traversal/URI/unsafe refs and absent files; no HED or external assets.

## S2: Bind stimulus dependency evidence to interpretation

Read bounded selected event tables, attach exact table/column evidence and asset metadata nodes in relationship graph; version profile and record findings. Corrected tables/assets affect current dependency scope, while history stays fixed.

## S3: Project authorized stimulus assets into views

Retain only assets referenced by retained stim_file columns and included event tables; exact bytes and receipts, explicit field authorization, no hidden-column leakage. Unsupported HED remains rejected.

## S4: Repair stimulus references during reviewed curation

Explicit local stimulus moves repair selected event cells preserving other lexical data; reject referenced deletion and unsafe targets, preserve table inheritance, digest-bound plan review and immutable history.

## Q1: Build independent multi-echo and stimulus corpus

Direct-byte licensed synthetic fixtures and hand-authored expected family/stimulus facts; pinned official validator comparisons with reviewed differences, independent header checks and malformed boundary cases.

## Q2: Demonstrate select-to-publication historical reproducibility

Executable demonstration and both-catalog tests: multi-echo task/events/stimuli input, qualified per-image recipe, accepted derivative, corrected source, unchanged old bundle/output evidence, and current source revocation.

## Q3: Run final tranche gate and document limits

Refresh source pin and verify upstream compileAll/testAll/docs, full Sanctity/Postgres/recovery, clean-source build, independent fixture/conformance checks, and retained evidence. Close epic only with exact passing commands and remaining limits.

## Scientific scope and sources

Bounded support: NIfTI BOLD echo families and local assets explicitly named by selected event-table `stim_file` cells. Echo family completeness means all compatible members recorded in the accepted inventory; no inference of unavailable scanner echoes. Single indexed echoes are assessed explicitly. SBRef, physiology, HED, general file collections, external/nested datasets, echo combination, registration and scanner qualification remain outside the profile. Views authorize the entire retained closure; source provenance never grants access.

Rules are reviewed against BIDS 1.11.1: [MRI](https://bids-specification.readthedocs.io/en/v1.11.1/modality-specific-files/magnetic-resonance-imaging-data.html), [events](https://bids-specification.readthedocs.io/en/v1.11.1/modality-agnostic-files/events.html), and [file collections](https://bids-specification.readthedocs.io/en/v1.11.1/appendices/file-collections.html). Repository consistency policies will be identified separately from normative specification requirements.

## Evidence and preservation

Preserve existing dirty work in both repositories. Capture a source checkpoint before editing; use a content-addressed source snapshot while upstream work remains unpublished. No automatic Git commits or remote publication. Validate portable semantics on JVM and Scala.js, history/authority on reference and PostgreSQL catalogs, and scientific fixtures with independent expected facts. Final receipts record exact sources, commands, outputs and limits.


## Implementation decisions

- Profiles: `native-reference-v9`, `reference-admission-v8`, relationship vocabulary
  `bids-mri-relationships-v6`, table-column evidence encoding 3, interpretation
  identity domain v7, MRI view v8, file edits v4, header-QC input contract v3.
  Historical encodings retain their original identity rules; the producer still
  supports accepted v1/v2 bundles. Temporal-statistics v1 remains single-echo.
- Echo families contain 2–16 recorded magnitude BOLD images. Positive finite
  increasing EchoTime and equal authored timing/acquisition fields are repository
  consistency policy. Header checks compare dimensions, active pixel dimensions
  and units; affine agreement and registration are not claimed. A family cannot
  establish the number of echoes originally acquired by a scanner.
- Local stimuli are opaque bytes, including `.json` assets. Only selected event
  tables create relationships; referenced assets have explicit table-column
  evidence. Asset bytes are retained only when `stim_file` is retained in the
  event mask. Event output is localized once per run, shared by all echoes.
- Reviewed image edits operate on the whole recorded family. Stimulus moves stay
  inside `stimuli/` and rewrite literal event cells once; deleting a referenced
  asset is rejected. Metadata corrections identify impacts even when membership
  and metadata-origin paths stay unchanged.
- Current source authority gates private input retrieval/materialization and new
  publication/retries. Accepted derivative releases retain their existing separate
  custody and access model; source provenance does not itself revoke their grants.
- The default build uses a SHA-256-pinned complete upstream source archive, including
  unpublished changes, license, tests, public consumers and docs. A sibling checkout
  is an explicit development override. Git commits and remote publishing are not
  part of this tranche.

## Independent qualification

The [fixture contract](../modules/app/src/test/resources/mri-conformance/analysis-inputs/contract.json)
contains twelve pre-authored cases. The [official comparison](../modules/app/src/test/resources/mri-conformance/analysis-inputs/official-results.json)
retains validator 3.0.2 / schema 1.2.2 / BIDS 1.11.1 results with NIfTI checks enabled
and no ignored issues. NiBabel independently checks all three image headers and
72 voxel values. Reversed/equal echo times and changed voxel spacing have no
corresponding official errors; they fail the repository's stronger family policy.
Missing local stimuli agree with the official missing-stimulus finding. Unsafe and
URI references fail locally before any external access; the official validator
reports those literal targets missing. Singleton cases retain orphaned sidecars,
which the official validator reports separately. Duplicate padded echo indices
lack a matching sidecar and also trigger an official missing EchoTime error.


## Final gate

All 16 work items and the epic are complete in mote. The final gate passed 414
Sanctity tests (265 ordinary, 149 PostgreSQL), including 122 lifecycle cases on each
catalog, plus actual crash/retry and coordinated restore. The final upstream pin
passed 243 JVM and 223 Scala.js tests, both public consumers and 11 mdoc files even
from isolated archive extraction. A fresh source-only Sanctity build passed all
265 ordinary tests, and an intentionally corrupted archive failed at build load.

The standalone demo verifies 12 exact input files, three published output files,
three independent QC rows, immutable historical metadata/bytes after correction,
and current private-input/publication revocation. Its execution workspace was
removed before publication. All twelve official corpus cases and the independent
header/voxel oracle passed; the fixture generator reproduced its bytes exactly.

[Verification receipt](evidence/analysis-input-verification.json),
[demo receipt](evidence/analysis-input-demo.json), and
[mote ID map](evidence/analysis-input-motes.json). CI configuration is ready; hosted
execution awaits a future push. No Git commits or remote publishing were performed.
