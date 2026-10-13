#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
uv venv --python 3.13 "$task_root/target/mri-python"
uv pip install --python "$task_root/target/mri-python/bin/python" -r "$task_root/modules/processing-mri/src/main/resources/temporal-requirements.txt"
printf 'export SANCTITY_MRI_PYTHON=%q\n' "$task_root/target/mri-python/bin/python"
