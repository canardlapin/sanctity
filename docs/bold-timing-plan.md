# BOLD timing evidence slice

13 October 2026. Proceeding from the completed calibration-input tranche.

Every BOLD image needs timing assessment even when no events table is present.
The current header check already compares TR with NIfTI time units. This slice
adds volume-count and slice-axis consistency, exact metadata evidence and safe
projection of timing declarations. It does not perform slice-time correction.

## Work recorded in mote

| Item | Deliverable | Depends on |
| --- | --- | --- |
| T1 | Bounded NIfTI slice-dimension observation using the existing shared prefix read | — |
| T2 | Every-BOLD timing checks, regular/variable sampling and slice metadata diagnostics | T1 |
| T3 | Versioned interpretation and coherent timing disclosure/export | T2 |
| T4 | Adversarial and independent NIfTI timing fixtures | T2 |
| T5 | Correction, immutable history and export/re-import on both catalogs | T3, T4 |
| T6 | Full ordinary/PostgreSQL/recovery and isolated-source gate; retained evidence | T5 |

The epic depends on T6; child relationships express containment. IDs are retained
in `docs/evidence/bold-timing-motes.json`.

## Policy and bounds

- Reuse existing regular/variable event-timing rules for all BOLD images,
  including count agreement and required variable-acquisition context.
- Inspect `dim_info` from NIfTI-1/2, both byte orders and gzip prefixes. Use an
  authored SliceEncodingDirection or declared header slice dimension. If neither
  identifies the axis, record unavailable evidence without assuming the k axis.
- Validate authored SliceTiming as nonnegative finite seconds; preserve repeated
  multiband times and authored ordering. Check count against the identified axis
  and expose increasing-voxel-index order, reversing negative directions.
- Check authored delay/discard counts and bounded timing values. Sampling-window
  comparisons are explicit repository consistency policy. Missing optional slice
  timing remains supported; unknown final frame duration is not extrapolated.
- Every check binds the image and effective metadata documents with origins.
  Reuse the existing 540-byte / 64 KiB compressed-prefix budgets; timing arrays
  have at most 100,000 values and diagnostics are bounded.
- Views must preserve the direction of any retained SliceTiming and the authored
  acquisition-duration context needed by VolumeTiming. Historical releases retain
  their original interpretation and assessment; current planning requires explicit
  reinterpretation when its semantic context changes.

The primary rules are [BIDS 1.11.1 MRI timing](https://bids-specification.readthedocs.io/en/v1.11.1/modality-specific-files/magnetic-resonance-imaging-data.html#timing-parameters)
and the official [NIfTI-1](https://github.com/NIFTI-Imaging/nifti_clib/blob/master/niftilib/nifti1.h)
and [NIfTI-2](https://github.com/NIFTI-Imaging/nifti_clib/blob/master/nifti2/nifti2.h)
header definitions. This is bounded consistency assessment, not full BIDS or
scanner qualification. Existing TR discrepancy admission/readiness policy remains.

The source checkpoint is `target/bold-timing-checkpoint`. Preserve previous work;
no commit or remote publication is included. Qualification will retain commands,
results, source fingerprints and limitations below.

## Implemented contract

`BoldTimingChecks.Rule` is `sanctity-bold-timing-v1+bids-events-timing-v1+nifti-slice-dimension-v1`.
The interpreter is native-reference-v11 / reference-admission-v10, with the new
rule in its semantic context. No upstream source pin or kernel encoding changes.
Payload profiles inherit this new native context; previous accepted assessments
remain unchanged. MRI view v10 adds SliceEncodingDirection to structural fields.

Timing values use bounded decimals (128 digits, absolute scale at most 308 and
finite Double representation, consistent with the existing timing profile).
Every supplied array is nonempty and has at most 100,000 values. Supplied discard
counts must be nonnegative integers. They never offset event time or remove any
stored image volumes. Supplied DelayTime must be nonnegative and shorter than TR.
This profile rejects FrameAcquisitionDuration with RepetitionTime.

For consistency comparisons, frame duration may not exceed the shortest recorded
volume interval. Slice onsets must precede the earliest available acquisition
boundary: TR, minimum variable interval, explicit frame duration, or TR minus
authored delay. These are explicit repository policy checks. Missing optional
SliceTiming is allowed. If VolumeTiming has only SliceTiming as its acquisition
context, the final volume's end remains unknown. NIfTI slice-order codes and
durations never replace authored timing values.

Each BOLD check binds the exact image and all contributing metadata documents.
It records effective values/origins, volume count, time origin, declared slice
axis and its basis, and optional voxel-index timing. Current MRI input readiness
requires this exact evidence. Unknown optional slice mapping does not prevent
existing recipes from running; they do not perform slice-time correction.

## Qualification and reproduction

The retained [NiBabel receipt](../modules/app/src/test/resources/mri-conformance/bold-timing/independent-results.json)
contains eight byte-authored NIfTI-1/2 headers and four previously qualified CC0
acquired multi-echo excerpts. Fixtures exercise both byte orders, plain/gzip input,
all slice axes and unspecified axes. Acquisition provenance remains in the prior
corpus; no new transformation is applied to acquired images. The generator checks
all eight retained files byte-for-byte before comparing NiBabel 5.3.2 observations.
The official BIDS validator was not rerun for this slice; the receipt qualifies
header observations and the tests qualify the declared repository timing policy.

```sh
target/mri-python/bin/python scripts/check-bold-timing.py
sbt 'app/testOnly sanctity.app.BoldTimingSuite sanctity.app.BoldTimingLifecycleSuite'
bash scripts/check-postgres.sh
bash scripts/check-clean-source.sh
```

Nine shared catalog cases cover no-events admission, inherited origins, exact
export, correction/history, rejected corrections, disclosure masks, export/re-import,
missing/substituted evidence and existing regular-sampling recipe requirements.
The 36 focused cases cover numeric/array bounds, slice direction/order, repeated
multiband times, variable sampling, discard semantics and independent header
observations. Existing resource/cancellation tests verify shared bounded reads.


## Final gate — 13 October 2026

All six work items and the epic are complete in mote. **679 tests passed**:
469 ordinary and 210 PostgreSQL, including 183 shared lifecycle cases per catalog.
The isolated Git-visible source build independently passed all 469 ordinary tests;
its runtime, test, fixture, script, build and CI inputs match the working tree.
Database crash/retry, closed restore, trusted current-policy reconciliation and
subsequent revocation all passed. The upstream source pin is unchanged.

Twelve independent NiBabel header observations match, and all eight generated
fixtures reproduce byte-for-byte. Existing calibration and processing tests pass.
The numerical recipe implementation is unchanged and no new numerical-algorithm
qualification is claimed. Hosted CI awaits a future push.

- [Final verification and source snapshot receipt](evidence/bold-timing-verification.json)
- [Mote IDs](evidence/bold-timing-motes.json)
- Logs: `target/bold-timing-postgres.log`, `target/bold-timing-clean-source.log`,
  `target/bold-timing-independent.log`.

Changes remain uncommitted. This slice qualifies timing declarations and their
repository lifecycle, not slice-time correction or acquisition synchronization.
