# Independent MRI evidence

This corpus distinguishes unchanged external evidence from assembled and mutated
software fixtures. It does not qualify a scanner, image registration, phase
unwrapping, anatomical orientation, distortion correction or a processing pipeline.

## Retained sources

- **NiBabel 5.3.2**, commit `7b5060e4bb105ed0c08c203db3dc5cf536d9c2aa`:
  `anatomical.nii`, `example4d.nii.gz`, and `example_nifti2.nii.gz` are copied
  unchanged from `nibabel/tests/data`. `source.json` records their URLs, byte counts
  and SHA-256 digests. Full upstream `COPYING` is retained; these examples are
  covered by the project's MIT license. The anatomical image is big-endian
  NIfTI-1; the other examples exercise little-endian NIfTI-1 and NIfTI-2.
  [Pinned source](https://github.com/nipy/nibabel/tree/7b5060e4bb105ed0c08c203db3dc5cf536d9c2aa/nibabel/tests/data).
- **OpenNeuro ds000117**, commit `74fb8db3352f0cf425f11ebeba6c7a2aa1974c0e`:
  the three sub-01/ses-mri/acq-func phase/magnitude images and their sidecars,
  nine functional sidecars, and dataset description are copied unchanged.
  `ds000117/provenance.json` records digests and an exact scoped inventory.
  Functional image paths are inventory evidence only; their payloads are not
  included. The retained dataset description declares CC0. Attribution:
  Wakeman, Apšvalka and Henson, *Multisubject, multimodal face processing*,
  [dataset](https://doi.org/10.18112/openneuro.ds000117.v2.0.0),
  [paper](https://www.ncbi.nlm.nih.gov/pubmed/25977808/),
  [pinned source](https://github.com/OpenNeuroDatasets/ds000117/tree/74fb8db3352f0cf425f11ebeba6c7a2aa1974c0e).

No source images were trimmed or rewritten. Neither dataset is represented as a
complete acquired study in this repository.

## Independent header oracle

`header-oracle.json` contains observations from **NiBabel 5.3.2 / NumPy 2.2.6**,
using `from_fileobj(check=False)` on the exact original header. This avoids
load-time normalization of offsets or scaling. Offline Scala tests compare all
six images' dimensions, datatype, offset, pixel dimensions, unit and transform
codes, raw quaternion/offset parameters, and sform rows against these values.
Portable tests separately exercise both byte orders for both NIfTI versions.

The two NiBabel functional examples contain pixdim[4] = 2000 with a **seconds**
unit code. The observed spacing is therefore 2000 seconds. No heuristic turns it
into milliseconds. This is deliberately retained evidence; it is not a claim
that those images represent plausible BIDS acquisitions.

Reproduce in an isolated environment:

```sh
python3 -m venv target/mri-oracle
target/mri-oracle/bin/python -m pip install nibabel==5.3.2 numpy==2.2.6
target/mri-oracle/bin/python scripts/check-mri-headers.py
```

Normal checking verifies all source digests and the recorded observations, with
no network or writes to the corpus. `--record` is only for intentional review.

## Relationship expectations and controlled mutations

Offline tests establish that the exact ds000117 family contains one phasediff,
required magnitude1 and optional magnitude2. Its phase sidecar declares all nine
retained functional image paths. Legacy participant-relative IntendedFor warnings
are preserved. The echo times are 0.00519 and 0.00765 seconds.

An explicitly labeled mutation moves the calibration inventory/sidecar paths to
another session while retaining the consumer references. Further mutations remove
magnitude1, corrupt spatial spacing and change authored TR. Existing ds001/ds114
fixtures continue to establish inherited events and gradients independently.

The shared lifecycle fixture assembles the retained image bytes under new BIDS
paths, supplies a small inherited event table and metadata, and declares B0 and
IntendedFor links across calibration/task sessions. It is synthetic assembly,
not an externally acquired multi-image collection. Export/re-import tests prove
its dependency and provenance contracts without claiming geometric alignment.

## Official validator comparison

```sh
python3 scripts/check-mri-conformance.py --deno /path/to/deno
```

Uses the existing pinned Deno 2.5.4, validator 3.0.2, frozen dependencies and
vendored BIDS 1.11.1 schema. **NIfTI headers are enabled; no issues are ignored.**
The runner assembles images from the corpus with explicitly supplied metadata.
It preserves the functional image's actual 2000-second header value in its
baseline JSON. All reports and warnings remain in `target/mri-conformance-*`.

| Case | Official error signature | Sanctity admission policy |
| --- | --- | --- |
| Complete family | None | Accept |
| Optional magnitude2 omitted | None | Accept |
| Required magnitude1 omitted | None in this pinned validator | Reject missing dependency |
| EchoTime2 omitted | SIDECAR_KEY_REQUIRED / EchoTime2; ECHOTIME1_2_DIFFERENCE_UNREASONABLE | Reject invalid echo times |
| Spatial voxel size zero | None in this pinned validator | Reject malformed spatial spacing |
| JSON TR = 2, original header = 2000 seconds | REPETITION_TIME_MISMATCH | Warning retained; explicit correction may be accepted |

`official-results.json` records these reviewed signatures. They establish the
precise overlap and policy differences, **not equivalence to the validator**.
In particular, accepting a metadata/header discrepancy is not a BIDS-conformance
claim. Re-recording expectations requires deliberate review via `--record`.

## Phase1/phase2 corpus

[phase-pair/README.md](phase-pair/README.md) describes the CC0 synthetic four-member
family, cross-session targets, sixteen independently specified mutations and
pinned official-validator differences. Its six images are included in the shared
NiBabel header oracle (twelve images total). Run the comparison without changing
expectations:

```sh
python3 scripts/check-phase-pair-conformance.py --deno target/conformance-tools/deno
target/mri-python/bin/python scripts/check-mri-headers.py
```
