#!/usr/bin/env python3
"""Capture exact Git-visible sources, including uncommitted/untracked inputs."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import zipfile


def capture(root, output):
    names = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root
    ).decode().split("\0")
    records = []
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(set(names) - {""}):
            if name.split("/")[0] in {".mote", ".omc", "vendor"}:
                continue
            path = root / name
            if not path.exists():
                continue
            if path.is_symlink() or not path.is_file():
                raise ValueError(f"Unsupported source entry: {path}")
            data = path.read_bytes()
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if path.stat().st_mode & 0o111 else 0o100644) << 16
            archive.writestr(info, data)
            records.append({"path": name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    return {
        "baseRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "archive": output.name,
        "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "files": records,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["checkpoint", "pin"])
    parser.add_argument("--upstream", type=pathlib.Path, default=pathlib.Path("../bids4s"))
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parent.parent
    upstream = args.upstream.resolve()
    if args.mode == "checkpoint":
        folder = root / "target/analysis-input-checkpoint"
        if folder.exists():
            raise SystemExit("Checkpoint already exists; refusing to replace it")
        records = {name: capture(source, folder / (name + ".zip"))
                   for name, source in [("sanctity", root), ("bids4s", upstream)]}
        (folder / "manifest.json").write_text(json.dumps(records, indent=2) + "\n")
        print(folder)
    else:
        record = capture(upstream, root / "vendor/bids4s-source.zip")
        (root / "vendor/bids4s-source.json").write_text(json.dumps(record, indent=2) + "\n")
        (root / "project/bids4s-source.properties").write_text(
            "# Exact source snapshot; base revision alone excludes unpublished changes.\n"
            + "sha256=" + record["sha256"] + "\nbaseRevision=" + record["baseRevision"] + "\n")
        print(record["sha256"])


if __name__ == "__main__":
    main()
