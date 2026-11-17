# SBRef and calibration-aware functional bundles

Epic: `bd-01M4VWBJBBAC5HNMBGVWECDCH5`. Authorized 13 October 2026. Mote is the live status record.

## Acceptance gate

Selecting a BOLD acquisition retains its qualified echo family, applicable functional SBRef, events/stimuli and explicitly declared fieldmaps. A versioned opt-in readiness policy reports missing or ambiguous reference/calibration evidence. Complete bundles roundtrip, execute the existing per-echo temporal-statistics recipe and support retained-output publication, frozen history and current source authority.

## Work items

| Key | Mote | Work | Depends on |
| --- | --- | --- | --- |
| S1 | `bd-01M4VWBJD5AR50PCCPRCXD7A9E` | Define bounded functional SBRef associations | — |
| S2 | `bd-01M4VWBJEZPVJ6FMP619C6E36N` | Assess SBRef metadata and single-volume headers | S1 |
| S3 | `bd-01M4VWBJH6YM33EV5DDB8VW23Q` | Retain exact SBRef relationship evidence | S1, S2 |
| S4 | `bd-01M4VWBJKS7QBJ0Z4NSQ4NN6BF` | Include SBRef in functional subset views | S3 |
| S5 | `bd-01M4VWBJNTWHZV9DDFY9QWBC8A` | Curate BOLD and SBRef associations coherently | S3 |
| C1 | `bd-01M4VWBJQVV76KH50NTJRX9M31` | Declare calibrated functional readiness contracts | S4 |
| C2 | `bd-01M4VWBJT2N147XDKSRXDCHTG1` | Check calibration direction and readout metadata | C1 |
| C3 | `bd-01M4VWBJW5VZ90XD6QGCQAK91X` | Bind reference and calibration geometry evidence | C1 |
| C4 | `bd-01M4VWBJYBWCENCA2K3HPHDVPP` | Report missing and ambiguous calibration clearly | C2, C3 |
| Q1 | `bd-01M4VWBK0TEM134RKBGHNHQD08` | Retain licensed acquired reference/calibration fixtures | — |
| Q2 | `bd-01M4VWBK2RWX1T2FRS2GZSHSC1` | Qualify malformed calibration boundaries | S5, C4 |
| Q3 | `bd-01M4VWBK55HS6HP0C22Z2CT41P` | Compare validator and export re-import evidence | S4, Q1 |
| L1 | `bd-01M4VWBK7EWGGF4CW10TSKZCXQ` | Freeze complete calibrated bundles on both catalogs | C4 |
| L2 | `bd-01M4VWBK9CVFVZY8JVXCCN4R21` | Execute existing recipes on qualified bundles | L1 |
| L3 | `bd-01M4VWBKBGZ4FBAWJ44QA505A6` | Qualify calibration publication history and authority | L2 |
| L4 | `bd-01M4VWBKDPFZYQHWH8BQPVJYCV` | Retain executable calibration demonstration and full gate | L3, Q2, Q3 |

## S1: Define bounded functional SBRef associations

Exact participant/session/directory and normalized acquisition entities; shared unindexed or complete indexed references, explicit ambiguity; document policy separately from BIDS validity.

## S2: Assess SBRef metadata and single-volume headers

Bound header observations and relevant effective metadata origins; reject malformed rank or metadata without requiring BOLD timing semantics.

## S3: Retain exact SBRef relationship evidence

Version semantic profile and graph; deterministic companion-selection evidence, ambiguity diagnostics and exact closure.

## S4: Include SBRef in functional subset views

Retain bytes, flatten approved metadata, preserve fieldmap dependencies, roundtrip complete reference closure.

## S5: Curate BOLD and SBRef associations coherently

Atomic image family moves/removal, preserve indexed/shared association, reject partial edits; metadata correction remains independent.

## C1: Declare calibrated functional readiness contracts

Opt-in versioned requirements for optional/required reference and explicit EPI pair calibration, separate from existing processing algorithm.

## C2: Check calibration direction and readout metadata

Require explicit source families, valid axis/sign and positive readout times; opposite directions under bounded same-grid policy.

## C3: Bind reference and calibration geometry evidence

Opt-in complete payload and coded geometry on companions with exact pairwise image/metadata binding; matching declarations are not registration.

## C4: Report missing and ambiguous calibration clearly

Deterministic readiness evidence and actionable missing/ambiguous/unsupported/disallowed requirements, no proximity guessing.

## Q1: Retain licensed acquired reference/calibration fixtures

Pin primary source, license, source hashes and honest bounded transformations; exercise actual BOLD/SBRef/EPI declarations.

## Q2: Qualify malformed calibration boundaries

Authored cases cover incomplete/mixed references, metadata, payload, geometry, evidence substitution, cross-session and family edits.

## Q3: Compare validator and export re-import evidence

Pinned official validator observations distinct from recipe policy; independent geometry/bytes and complete export re-import comparisons.

## L1: Freeze complete calibrated bundles on both catalogs

One BOLD selects complete echo/reference/events/stimuli/declared fieldmap closure; private bundle source evidence immutable.

## L2: Execute existing recipes on qualified bundles

Use existing temporal-statistics maps with exact input contract and bundle provenance; calibration companions do not change algorithm.

## L3: Qualify calibration publication history and authority

Workspace-loss publication/retry; corrections preserve history; current source policy gates private access/new publication.

## L4: Retain executable calibration demonstration and full gate

Run ordinary/PostgreSQL/crash/restore and isolated source checks; retain demo, independent numerical evidence and final source receipt.

## Scope and preservation

Functional SBRef association is a conservative Sanctity policy with exact acquisition matching; no nearest-scan inference or cross-run/session borrowing. Calibration qualification uses an explicitly bounded same-grid opposite-phase EPI pair policy. Other BIDS-valid calibration schemes remain supported by the existing subset profile where already covered, but cannot satisfy this new recipe policy. Coded geometry describes declarations, not registration. Existing mean/population-SD processing does not perform distortion or motion correction. Historical contracts remain available. The source checkpoint is `target/calibration-input-checkpoint`; preserve previous multi-echo and console work. No Git commits or remote publication are part of this tranche.

Primary specification: [BIDS 1.11.1 MRI](https://bids-specification.readthedocs.io/en/v1.11.1/modality-specific-files/magnetic-resonance-imaging-data.html). SBRef is optional in BIDS; this tranche makes reference/calibration requirements explicit recipe choices.

## Implemented policy

Native interpretation is `native-reference-v10` / `reference-admission-v9`, with
relationship graph `bids-mri-relationships-v7`. `bids.bold-sbref` selects functional
NIfTI images in the exact same directory with identical acquisition entities after
normalizing numeric run/echo spelling. One unindexed SBRef may serve the full BOLD
family, or every echo must have its own indexed reference. Mixed, duplicate and
incomplete representations fail explicitly. Functional part/chunk and other
unsupported collection entities are excluded. No implicit run/session borrowing.

SBRef content evidence binds the reference, its BOLD consumers and all effective
metadata documents. References must be 3D or have a singleton fourth dimension.
Authored TaskName agrees with BOLD; indexed references have positive EchoTime
matching their echo within 1e-9 s. Optional EchoTime/readout/direction values are
checked when present. Header-only admission does not read the full scalar array.
The view compiler is v9; the file-edit compiler is v5. Views retain exact reference
bytes and approved effective metadata. Image moves/removal preserve the complete
BOLD/reference acquisition and indexed/shared representation; metadata corrections
remain explicit independent changes. Diffusion/perfusion SBRef image edits
require another association profile.

`PayloadBidsInterpreter(store, qualifyCalibration = true)` opts into
`native-payload-v3` / `native-payload-admission-v3`. It reads bounded scalar BOLD,
SBRef and EPI payloads and qualifies each coded geometry using the existing rules.
Reference and EPI agreement checks bind every image and contributing metadata blob.
A grid disagreement is an admission warning and a v3 readiness failure, so valid
source data outside the recipe policy remain available for another consumer.

Temporal contract v3 has explicit parameters `reference=required` and
`calibration=same-grid-epi-pair`. V1/v2 retain their earlier optional-companion
requirements. V3 requires one reference per BOLD member and one declared group of
exactly two EPI fieldmaps in the same participant/session. The EPI sources oppose
on one voxel axis; BOLD uses that axis and SBRef matches its direction. All four
roles need positive authored TotalReadoutTime; values may differ. Filename `dir`
labels never substitute for authored PhaseEncodingDirection. Independent singleton
IntendedFor declarations do not establish a pair. Redundant IntendedFor links to
members of an explicit B0 group are collapsed, while separate named B0 groups
remain ambiguous even when their source sets overlap or are identical. Every echo declares the same
calibration group. Missing, ambiguous, unsupported and disallowed requirements
remain separate in the readiness report.

The strict geometry policy requires matching dimensions and separately coded
qform/sform coordinate systems, with every pair agreeing at all voxel-centre
corners within 0.001 mm. It does not establish registration. Temporal v3 uses the
same all-volume mean/population-SD algorithm as v2, still producing only BOLD maps.
Reference and fieldmap images remain frozen input dependencies. Contract, request,
producer and derivative profile identify v3; existing target/byte limits remain.

## Independent qualification

The synthetic byte-authored corpus combines three echoes, a shared SBRef, a B0
EPI pair, shared events and two local stimuli. The official BIDS validator 3.0.2
runs against pinned BIDS 1.11.1/schema 1.2.2 with NIfTI checks enabled and no ignored
issues. Its observations are distinct from stricter recipe expectations. The ten
cases and complete warning/error sets are retained in
[the independent receipt](../modules/app/src/test/resources/mri-conformance/calibration-inputs/independent-results.json).

Three acquired CC0 images are retained byte-for-byte with their original metadata:
a single-band reference from [NARPS, ds001734 v1.0.5](https://doi.org/10.18112/openneuro.ds001734.v1.0.5)
and AP/PA EPI images from [QTAB, ds004146 v1.0.0](https://doi.org/10.18112/openneuro.ds004146.v1.0.0).
They remain separate datasets; no cross-study association is constructed. Original
descriptions preserve attribution and citation guidance. Pinned commits,
git-annex size/MD5, SHA-256 and unmodified source paths are retained in
[provenance](../modules/app/src/test/resources/mri-conformance/acquired-calibration/provenance.json).
NiBabel 5.3.2 independently observes all nine synthetic/acquired images. The
acquired EPI images each have valid coded geometry but differ from one another,
providing an acquired example that does not satisfy the same-grid recipe policy.
Acquired BOLD numerical coverage continues to use the separate licensed four-echo
fixture from the previous tranche. These fixtures qualify software behavior, not
scanner quality, registration or a complete acquired preprocessing workflow.

## Reproduction

```sh
sbt 'app/testOnly sanctity.app.CalibrationInputSuite sanctity.app.AcquiredCalibrationSuite'
bash scripts/check-postgres.sh
bash scripts/check-clean-source.sh
target/mri-python/bin/python scripts/check-calibration-inputs.py
target/mri-python/bin/python scripts/check-multiecho-temporal.py target/calibration-numerical --contract 3
SANCTITY_MRI_PYTHON="$PWD/target/mri-python/bin/python" sbt 'app/runMain sanctity.app.CalibrationInputDemo'
```

The comparison script expects Deno 2.5.4 at `target/conformance-tools/deno`.
The numerical driver qualifies the producer algorithm separately from companion
readiness. CI runs the v1/v2/v3 numerical checks and offline acquired-data tests.

## Final gate — 13 October 2026

All 16 work items and the epic are complete in mote. **616 tests
passed**: 415 ordinary and 201 PostgreSQL, including 174 shared
lifecycle cases on each catalog. Actual database crash/retry, closed restore,
current-policy reconciliation and subsequent revocation passed. The final isolated
source build passed all 415 ordinary tests with identical runtime, test,
fixture, script and build inputs. The upstream source pin is unchanged.

The ten-case official-validator comparison passed with no ignored issues and
NIfTI checks enabled. Nine independent image observations cover **4,607,200 input
voxels**. The acquired EPI pair differs by up to **4.772346267546532 mm** at tested
corners, correctly placing it outside the strict same-grid policy. All ten
acquired-fixture/provenance files and 22 generated synthetic fixture/provenance
files reproduced byte-for-byte.

The producer oracle passed 22 v1 cases and nine cases each for v2 and v3,
checking **6,503,712 output voxels**. V2 and v3 each rejected seven malformed
requests. Maximum absolute error was **6.821210263296962e-13**, within absolute
1e-10 plus relative 1e-12 tolerance. These numerical checks qualify the producer
separately from companion readiness.

The final demo retained **18 input files**, including three BOLD images, one
SBRef, two EPI images, shared events and two stimuli. It produced six maps and
13 output files. All 31 input/output files matched retained hashes. The independent
checker verified all 48 map voxels with zero error; the exported input bundle had
no official-validator errors. Workspace-loss publication, idempotent retry,
historical SBRef correction and current input/publication revocation passed.

- [Final source and verification receipt](evidence/calibration-input-verification.json)
- [Demo, file hashes, numerical and official export verification](evidence/calibration-input-demo.json)
- [V1 numerical compatibility](evidence/calibration-numerical-v1.json)
- [V2 numerical compatibility](evidence/calibration-numerical-v2.json)
- [V3 numerical qualification](evidence/calibration-numerical-v3.json)
- [Mote IDs](evidence/calibration-input-motes.json)

Final logs: `target/calibration-postgres-complete.log`,
`target/calibration-clean-source-complete.log`, `target/calibration-demo-complete.log`
and `target/calibration-roundtrip-complete.log`. Source changes remain uncommitted.
Hosted CI execution awaits a future push. The implemented capability qualifies
complete input bundles; it does not perform distortion correction or registration.
