#!/usr/bin/env python3
"""Fetch a checksum-pinned official test converter into target/, without a global install."""
import hashlib, io, json, pathlib, platform, urllib.request, zipfile

release = "v1.0.20260724"
system, machine = platform.system(), platform.machine()
if system == "Darwin" and machine == "arm64":
    asset, expected = "dcm2niix_mac.zip", "d393006dd1edfba4013bc4b8eccc875b23d611ecbc53fda5a5542f629591008f"
elif system == "Linux" and machine in ("x86_64", "AMD64"):
    asset, expected = "dcm2niix_lnx.zip", "2916a7bc64511eb20fb976a994799183cc1bfdd03395bf5d8edef54a58dee06f"
else:
    raise SystemExit("Supply your own verified executable pin for this platform; fixture downloader supports macOS arm64 and Linux x86_64.")
url = f"https://github.com/rordenlab/dcm2niix/releases/download/{release}/{asset}"
with urllib.request.urlopen(url, timeout=30) as response:
    data = response.read(10 * 1024 * 1024 + 1)
if len(data) > 10 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != expected:
    raise RuntimeError("release archive checksum mismatch")
archive = zipfile.ZipFile(io.BytesIO(data))
members = [m for m in archive.infolist() if pathlib.PurePosixPath(m.filename).name == "dcm2niix"]
if len(members) != 1 or members[0].file_size > 100 * 1024 * 1024:
    raise RuntimeError("unexpected converter archive")
binary = archive.read(members[0])
root = pathlib.Path(__file__).resolve().parents[1] / "target" / "tools" / ("dcm2niix-" + release)
root.mkdir(parents=True, exist_ok=True)
executable = root / "dcm2niix"
if executable.exists() and executable.read_bytes() != binary:
    raise RuntimeError("refusing to replace an existing different executable")
if not executable.exists():
    executable.write_bytes(binary)
executable.chmod(0o755)
pin = {"release": release, "source": url, "archiveSha256": expected,
       "executable": str(executable), "sha256": hashlib.sha256(binary).hexdigest()}
(root / "pin.json").write_text(json.dumps(pin, indent=2) + "\n")
print(json.dumps(pin, indent=2))
