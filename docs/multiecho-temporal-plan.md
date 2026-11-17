# Qualified multi-echo temporal analysis

Epic: `bd-01M4VJVR3NRFD9K7XKXH23EV92`. Authorized 13 October 2026. Mote is the live status record.

## Acceptance gate

Selecting one member of a three-echo BOLD family produces six independently verified mean/population-SD maps, each bound to its exact source image. Publish retained outputs after deleting the execution workspace; later source corrections leave the original inputs/results unchanged; current authority gates private inputs and publication.

## Work items

| Key | Mote | Work | Depends on |
| --- | --- | --- | --- |
| G1 | `bd-01M4VJVR54NSQK99MNA3TYCP0P` | Define coded spatial-transform qualification | — |
| G2 | `bd-01M4VJVR7JFFSMM68W3DTXXM14` | Bind cross-echo geometry evidence | G1 |
| G3 | `bd-01M4VJVRA8182AZ7FSTKMKQRFA` | Require payload evidence for every expanded echo | G2 |
| G4 | `bd-01M4VJVRD5PPZDJZ9QJEK477JK` | Enforce coherent temporal recipe budgets | G3 |
| T1 | `bd-01M4VJVRG14CCR0D54Z9BX9QH2` | Version the multi-echo temporal input contract | G4 |
| T2 | `bd-01M4VJVRJME95WKASM91AM9J6R` | Produce independent maps for every echo | T1 |
| T3 | `bd-01M4VJVRN9NFDECHQE36YNT6XH` | Admit complete echo-specific derivatives | T2 |
| T4 | `bd-01M4VJVRQYQ5J4QKKDZE32REN0` | Expose echo-specific observations and queries | T3 |
| Q1 | `bd-01M4VJVRTRH9GCW6HCXBAZ82FC` | Independently verify all multi-echo map voxels | T2 |
| Q2 | `bd-01M4VJVRXR458YXNM3KF0Y8B51` | Qualify malformed geometry and payload boundaries | G2 |
| Q3 | `bd-01M4VJVS0RDE7G0HK8AD3CCJ0X` | Retain a licensed acquired multi-echo fixture | — |
| Q4 | `bd-01M4VJVS3JYHX15E5FWNFQ8Z80` | Measure multi-echo resource behavior | Q1, Q3 |
| L1 | `bd-01M4VJVS78TEX5714R4JR1NRNB` | Exercise frozen multi-echo execution on both catalogs | T4 |
| L2 | `bd-01M4VJVSAF3QSCCFPTZDV26RWS` | Recover and publish retained temporal outputs | L1 |
| L3 | `bd-01M4VJVSDCKDCV4K3G6H9FE9C3` | Verify history and current source authority | L2 |
| L4 | `bd-01M4VJVSGAF0EQZDD69VXRM3ZP` | Run full gate and retain executable demonstration | L3, Q1, Q2, Q4 |

## G1: Define coded spatial-transform qualification

Implement explicit qform/sform validation and physical voxel-centre transforms, known units, finite/nondegenerate matrices and handedness. Preserve separately coded coordinate systems; cite primary NIfTI rules and document stricter recipe policy.

## G2: Bind cross-echo geometry evidence

Add opt-in versioned payload/geometry assessment with exact blob-bound per-image and family checks, independent like-coded affine agreement, explicit missing/invalid/conflicting findings. Preserve header-only and payload-v1 behavior.

## G3: Require payload evidence for every expanded echo

Readiness expands a selected echo to its recorded family and verifies exact complete payload plus geometry evidence for every member. A bad unselected member must block the bundle.

## G4: Enforce coherent temporal recipe budgets

Bound expanded targets, complete bundle bytes, output inventory and per-image dimensions; deterministic roots, no silent truncation. Qualify boundary failures and v1 compatibility.

## T1: Version the multi-echo temporal input contract

Add temporal contract/profile v2 with immutable request semantics; v1 remains accepted for single-echo inputs with historical declarations.

## T2: Produce independent maps for every echo

Use the existing qualified all-volume mean/population-SD algorithm per image, preserve each source spatial declarations, stream echo-by-echo, bind code/request and all outputs.

## T3: Admit complete echo-specific derivatives

Require exact per-echo map/sidecar inventory, source grid and provenance; reject swapped, omitted, extra or altered outputs and forged versions.

## T4: Expose echo-specific observations and queries

Preserve echo entities and source image identities through derivative facts, frozen selection and exact-source observation queries; demonstrate no echo conflation.

## Q1: Independently verify all multi-echo map voxels

Extend stdlib byte/two-pass numerical oracle to multi-row requests; direct-byte multi-echo fixtures span scaling, formats and byte orders. Retain source hashes and tolerances.

## Q2: Qualify malformed geometry and payload boundaries

Pre-authored cases cover unknown/invalid transforms, singular/sheared matrices, handedness, qfac/quaternion, mismatched affines, missing/unbound payloads and compressed corruption. Compare geometry to NiBabel independently.

## Q3: Retain a licensed acquired multi-echo fixture

Find primary-source openly licensed multi-echo acquisition, pin/download a bounded sample with citation, license, hashes and transformations. Qualify actual geometry and numerical outputs without claiming scanner validation.

## Q4: Measure multi-echo resource behavior

Measure runtime, RSS and input/output bytes for several echo/volume sizes including the expanded-target boundary. Retain exact commands and avoid inferring a production SLA.

## L1: Exercise frozen multi-echo execution on both catalogs

Reference/PostgreSQL lifecycle verifies selection of one echo retains and processes the complete family with exact source identity and six maps for three echoes.

## L2: Recover and publish retained temporal outputs

Publish after execution workspace loss; retry idempotently and resolve stale publication targets without rerunning. Use existing durable run/acceptance protocol.

## L3: Verify history and current source authority

Correct source metadata after execution and preserve historical bundle/results; revoke source grant and deny current private-input retrieval and new publication. Preserve independently governed accepted derivatives.

## L4: Run full gate and retain executable demonstration

Add executable three-echo demo and verification receipt; full ordinary/PostgreSQL/crash/restore, isolated source build and numerical/geometry/acquired-data checks. Document scope and close all motes only on evidence.

## Scope and preservation

Per-echo temporal statistics use all volumes, scaled signal, population SD and float64 accumulation/output. This tranche does not introduce echo combination, motion correction, registration, masking or a claim of scientific image quality. Coded geometry describes declared coordinates. Explicit recipe policies are distinct from BIDS validity. Preserve accepted v1 contracts/profiles and the console adoption work. The source checkpoint is retained under `target/multiecho-temporal-checkpoint`; upstream is the existing verified source pin. No Git commits or remote publication are part of this tranche.

Geometry follows the [NIfTI transform definition](https://nifti.nimh.nih.gov/nifti-1/documentation/nifti1fields/nifti1fields_pages/qsform.html/document_view.html) and [qform/sform handedness guidance](https://nifti.nimh.nih.gov/nifti-1/documentation/nifti1fields/nifti1fields_pages/qsform_brief_usage.html). Each coded coordinate system is assessed independently; different qform/sform coordinate systems need not coincide.

## Implemented contract and geometry policy

- `PayloadBidsInterpreter(store, qualifyGeometry = true)` opts into
  `native-payload-v2` / `native-payload-admission-v2`. The default remains payload
  v1; native header-only admission is unchanged. New content rules are
  `sanctity.nifti-geometry.v1` and `sanctity.echo-geometry.v1`.
- Temporal input contract `sanctity.temporal-statistics:2` and derivative profile
  `sanctity-temporal-statistics-v2` accept 1–16 expanded BOLD images. Selecting one
  echo expands its complete recorded family. All members need exact, complete
  payload evidence and coded geometry checks. V1 retains its 1–8 single-echo
  contract, request shape and derivative declarations; the adapter supports both.
- Geometry requires positive finite spatial spacings, known metre/mm/micron
  units, transform codes 0–5, at least one coded transform, finite nonsingular
  active transforms, and consistent qform/sform handedness. Inactive fields are
  ignored. Quaternion norm tolerances follow the header's float precision. The
  normalized determinant must have magnitude at least 1e-10 and coefficients
  and voxel-centre coordinates must stay within 1e12 mm. These bounds are recipe
  policy, not additional NIfTI validity requirements.
- Each family has matching spatial dimensions and matching qform codes/presence
  and sform codes/presence. Like-coded forms are compared separately, across every
  pair of echoes, at all eight voxel-centre corners with absolute Euclidean
  distance at most 0.001 mm. Qform and sform within one image need not coincide;
  they may describe different coordinate systems. Matching declarations do not
  establish registration or acquisition quality.
- Each echo produces independent all-volume mean and population-SD maps. The
  algorithm, scaling, precision and metric definitions remain unchanged. Outputs
  retain the source's coded spatial declarations; v2 explicitly permits the
  NIfTI qfac=0 fallback to be serialized as +1 and records that policy in each
  sidecar. V1's spatial-field comparison remains unchanged.
- Readiness checks selected dependency byte totals; the producer separately
  checks the whole materialized bundle against 8 GiB. Existing limits remain
  8 GiB encoded/logical bytes per image, 16M spatial voxels and 1 MiB voxel offset.
  Images are processed sequentially. At 16 maximal image grids, retained output
  maps can occupy 4 GiB; input copy plus scratch plus maps can approach 20 GiB.
  Operators still own cleanup of failed workspaces. These maxima are not
  qualified production capacity targets.
- Derivative admission checks complete map/sidecar inventory, exact request and
  producer/profile version, source identities, grid declarations and payloads.
  Source-specific observations and echo entities remain queryable. Admission
  does not recompute submitted numerical maps; wrong bytes during collection
  fail the retained output digest, while independent fixtures qualify the local
  producer's numerical algorithm.

## Independent evidence

The acquired fixture is a CC0 excerpt from [Multi-echo Cambridge, OpenNeuro
ds000258 v1.0.1](https://doi.org/10.18112/openneuro.ds000258.v1.0.1), repository
commit `0f274e6244a246ba7588cab117326fd90aa75fa1`. The generator verifies all four
original compressed images against pinned git-annex size/MD5, records SHA-256,
and retains the first four volumes with their original big-endian voxel bytes,
scaling and spatial declarations. Only temporal dimension/array extent change.
The original headers have 239 volumes; the original README's prose describes a
different count, so observed header values govern the excerpt. The repository
retains original description/README, exact transformations, license and hashes in
[provenance](../modules/app/src/test/resources/mri-conformance/acquired-multiecho/provenance.json).
This is a small acquired-data software fixture, not scanner or study validation.

`check-multiecho-temporal.py` uses the existing stdlib byte decoder and two-pass
numerical oracle. It covers synthetic three-echo inputs, the acquired four-echo
excerpt, mixed NIfTI versions/byte orders/scaling/compression, and 2/8/16 echoes
at 4/64 volumes. It records all-voxel tolerances, runtime, RSS, input/output bytes,
and rejection of seven malformed/over-budget requests. NiBabel independently
observes both coded transforms; Scala tests compare all retained synthetic and
acquired image matrices against those observations. Geometry boundary tests also
cover unknown/nonfinite/singular forms, units, shear, handedness, rounding, all-pair
distance, and determinant underflow. Shared catalog tests cover a corrupt gzip
trailer and a malformed unselected echo.

The [numerical receipt](evidence/multiecho-temporal-qualification.json) currently
records nine positive cases, seven refused requests and every output voxel checked.
Final lifecycle/demo/build receipts and commands appear below.

## Final gate — 13 October 2026

All 16 work items and the epic are complete in mote. **485 tests passed**:
320 ordinary and 165 PostgreSQL, including 138 shared lifecycle cases on each
catalog. The full PostgreSQL gate passed actual crash/retry, closed restore,
current-policy reconciliation and subsequent revocation. A fresh isolated source
build passed all 320 ordinary tests with identical runtime/test/fixture sources.

The final multi-echo oracle checked **2,687,048 output voxels** across nine positive
cases, rejected seven malformed/over-budget requests, and observed maximum absolute
error **6.821210263296962e-13**, within absolute 1e-10 plus relative 1e-12 tolerance.
All 22 legacy numerical cases passed (1,129,616 output voxels). The independent
NiBabel affine comparison and 19 geometry tests passed. The 14 acquired-fixture and
provenance files reproduced byte-for-byte from pinned sources.

The standalone demo selected one echo and processed three, producing six maps and
13 total output files. All 12 input files and 13 output files independently match
their retained hashes. The stdlib checker verified all 48 map voxels with zero
observed error. Publication followed execution-workspace deletion; historical
correction and current private-input/publication revocation passed. Accepted
derivative custody remains independent.

Measured child-process RSS in the final numerical corpus ranged from 38.5 to
68.8 MiB; the 16-echo/64-volume synthetic case processed 67,114,496 logical bytes in
0.674 seconds with 45.2 MiB peak RSS. These are local fixture observations, not
production capacity guarantees. The supported maxima remain explicitly unqualified.

- [Final verification and exact source inventory](evidence/multiecho-temporal-verification.json)
- [Demo receipt and independent file/numerical verification](evidence/multiecho-temporal-demo.json)
- [Multi-echo numerical/resource receipt](evidence/multiecho-temporal-qualification.json)
- [Legacy numerical compatibility receipt](evidence/multiecho-temporal-legacy-qualification.json)
- [Mote IDs](evidence/multiecho-temporal-motes.json)

Run logs: `target/multiecho-postgres-final.log`, `target/multiecho-clean-source.log`,
`target/multiecho-numerical-final.log`, `target/multiecho-legacy-numerical.log`, and
`target/multiecho-demo-final.log`. Recovery evidence:
`target/postgres-check.RqGaRe`; isolated build: `target/clean-source.KVSUJj`.
The upstream source pin is unchanged. CI now runs both independent numerical
harnesses; hosted execution awaits a future push. Source changes remain uncommitted.
