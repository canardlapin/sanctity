# Synthetic phase-pair contract corpus

These small NIfTI-1 images are direct, deterministic byte constructions from
`scripts/create-phase-pair-fixture.py`, dedicated to CC0. They contain four spatial
voxels, with two volumes for each BOLD image. They are not acquired MRI, measured
phase maps, or evidence of geometric suitability or scanner accuracy.
`provenance.json` pins every dataset file and the generator. No external image
has been relabeled as a measured phase image.

The authoritative baseline is [BIDS 1.11.1, two phase maps and two magnitude
images](https://github.com/bids-standard/bids-specification/blob/v1.11.1/src/modality-specific-files/magnetic-resonance-imaging-data.md#case-2-two-phase-maps-and-two-magnitude-images).
The repository's retained schema has SHA-256
`9bac1441212012d1971982053545dba8160652d73d8d0faed71709b4401ad300`.
Its `rules.sidecars.fmap.MRIFieldmapTwoPhase` requires `EchoTime` for each phase
image. The bounded family contract also requires all four unambiguous members,
increasing phase echo times and agreement with any supplied magnitude echo time.
Those repository checks are separate from the official validator's findings.

`contract.json` records expectations authored before resolver implementation.
Family pairing uses the same directory and dataset boundary and matching sub,
ses, acq and run entities. Run padding is numeric; subject/session labels remain
lexical. Chunked families are unsupported. Mixed phasediff and separate-phase
representations with identical entities are ambiguous in this profile; separate
acquisition labels can distinguish them. Existing phasediff and EPI profiles keep
their own semantics.

The valid family lies in `ses-cal`, while two explicit consumers lie in
`ses-task`. Both phases declare the same B0 group and both consumer references.
Selecting run-1 must retain the four family members and remove run-2 references.
No acquisition proximity is used to choose a consumer. Every selected family
member closes over the other members and preserves echo-time metadata origins.

Regenerate into a new directory and compare dataset files and provenance:

```sh
python3 scripts/create-phase-pair-fixture.py target/phase-pair-regenerated
```

Numerical phase differences, phase unwrapping, conversion to Hz, distortion
correction, registration and clinical/scanner qualification are outside this
contract. F2–F5 implement and qualify this family.

## Reviewed official-validator differences

`official-results.json` records 16 runs of validator 3.0.2 against schema 1.2.2
(BIDS 1.11.1), with NIfTI header checks enabled and no ignored issues. Full reports,
including recommended-field warnings, are in
`target/phase-pair-conformance-pa7zck69/`.

| Mutation | Official error signature | Repository family policy |
| --- | --- | --- |
| Complete family, padded run, separate phasediff or EPI | None | Accept family |
| Missing any member, with its sidecar retained | `SIDECAR_WITHOUT_DATAFILE` | Reject missing family member |
| Missing phase EchoTime | `SIDECAR_KEY_REQUIRED / EchoTime` | Reject missing phase timing |
| Duplicate image encoding or mismatched acquisition | None | Reject ambiguous or incomplete family |
| Reversed echo times or supplied magnitude-time mismatch | None | Reject inconsistent family timing |
| Dangling IntendedFor target | None | Reject missing explicit target |
| Phasediff and phase pair with identical family entities | None | Reject mixed representation |

These differences are deliberate repository constraints, not claims of official
validator equivalence. The missing-image validator outcome is caused by the
retained orphan sidecar; it does not independently establish a four-member rule.
The complete synthetic fixture still emits recommended-metadata warnings, which
remain visible in its full report.

Recheck the reviewed signatures without overwriting them:

```sh
python3 scripts/check-phase-pair-conformance.py --deno target/conformance-tools/deno
```
