#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
mkdir -p target
workspace="$(mktemp -d "$task_root/target/dicom-check.XXXXXX")"
executable="${SANCTITY_DCM2NIIX:?Set SANCTITY_DCM2NIIX to a pinned dcm2niix executable}"
digest="${SANCTITY_DCM2NIIX_SHA256:?Set the pinned executable SHA256}"
python3 scripts/dicom-fixture.py create "$workspace/dicom"
sbt "app/runMain sanctity.app.DicomDemo \"$executable\" $digest \"$workspace/dicom\" \"$workspace/repository\""
python3 scripts/dicom-fixture.py verify "$workspace/repository/export/sub-01/anat/sub-01_T1w.nii"
printf 'DICOM adapter evidence: %s\n' "$workspace"
