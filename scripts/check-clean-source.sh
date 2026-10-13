#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
mkdir -p target
workspace="$(mktemp -d "$task_root/target/clean-source.XXXXXX")"
python3 - "$task_root" "$workspace" <<'PY'
import pathlib, shutil, subprocess, sys
root, output = map(pathlib.Path, sys.argv[1:])
names = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=root).decode().split('\0')
for name in sorted(set(names) - {''}):
    if name.split('/')[0] in {'.mote', '.omc'}:
        continue
    source = root / name
    if not source.exists():
        continue
    if source.is_symlink() or not source.is_file():
        raise SystemExit('Unsupported source entry: ' + name)
    destination = output / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
assert (output / 'vendor/bids4s-source.zip').is_file()
PY
cd "$workspace"
export SANCTITY_MRI_PYTHON="${SANCTITY_MRI_PYTHON:-$task_root/target/mri-python/bin/python}"
sbt check
printf 'Clean-source build passed: %s\n' "$workspace"
