#!/usr/bin/env python3
"""Retain acquired CC0 SBRef/EPI samples from two separate OpenNeuro studies.

Original images and metadata are byte-for-byte; no constructed cross-study associations.
Git-annex size/MD5 and retained SHA-256 bind every original download.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import urllib.request

SAMPLES = [
    ("ds001734", "0ad017e7f83ecda943fb85cc93bb3a8a122b2e60", [
        "sub-001/func/sub-001_task-MGT_run-01_sbref.nii.gz"], ["task-MGT_sbref.json"]),
    ("ds004146", "8aaf9b5ddfd7290a264ff6a41ff52d7df8b33102", [
        "sub-0001/ses-02/fmap/sub-0001_ses-02_dir-AP_epi.nii.gz",
        "sub-0001/ses-02/fmap/sub-0001_ses-02_dir-PA_epi.nii.gz"], [
        "sub-0001/ses-02/fmap/sub-0001_ses-02_dir-AP_epi.json",
        "sub-0001/ses-02/fmap/sub-0001_ses-02_dir-PA_epi.json"]),
]


def fetch(url):
    with urllib.request.urlopen(url, timeout=90) as response:
        return response.read()


def create(destination, cache):
    destination.mkdir(parents=True, exist_ok=False)
    cache.mkdir(parents=True, exist_ok=True)
    sources = []
    for dataset, commit, images, metadata in SAMPLES:
        raw = f"https://raw.githubusercontent.com/OpenNeuroDatasets/{dataset}/{commit}/"
        description = fetch(raw + "dataset_description.json")
        desc = json.loads(description)
        assert desc["License"] == "CC0"
        folder = destination / dataset
        folder.mkdir()
        (folder / "dataset_description.json").write_bytes(description)
        for name in metadata:
            out = folder / name
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_bytes(fetch(raw + name))
        for name in images:
            pointer = fetch(raw + name).decode()
            size, md5 = re.search(r"MD5E-s(\d+)--([a-f0-9]{32})", pointer).groups()
            url = f"https://s3.amazonaws.com/openneuro.org/{dataset}/{name}"
            original = cache / (dataset + "-" + Path(name).name)
            if not original.exists():
                original.write_bytes(fetch(url))
            data = original.read_bytes()
            assert len(data) == int(size) and hashlib.md5(data).hexdigest() == md5
            out = folder / name
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_bytes(data)
            sources.append({"dataset": dataset, "commit": commit, "datasetDOI": desc["DatasetDOI"],
                            "license": "CC0-1.0", "url": url, "pointerUrl": raw + name, "path": str(out.relative_to(destination)),
                            "size": len(data), "md5": md5, "sha256": hashlib.sha256(data).hexdigest(),
                            "transform": "None; original compressed bytes retained."})
    (destination / "README").write_text(
        "Three CC0 acquired images, retained byte-for-byte with original metadata.\n"
        "NARPS (ds001734): one single-band reference. QTAB (ds004146): AP/PA EPI images.\n"
        "These are separate studies and never form a combined processing bundle.\n"
        "Original descriptions retain attribution and citation guidance.\n"
        "Software fixture, not acquisition quality or registration validation.\n")
    files = {str(p.relative_to(destination)): {"size": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
             for p in sorted(destination.rglob("*")) if p.is_file()}
    (destination / "provenance.json").write_text(json.dumps({"kind": "acquired-independent-calibration-samples", "sources": sources,
        "generatorSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), "files": files}, indent=2) + "\n")
    print(json.dumps({"destination": str(destination), "acquiredImages": len(sources), "bytes": sum(s["size"] for s in sources)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--cache", type=Path, default=Path("target/acquired-calibration-downloads"))
    args = parser.parse_args()
    create(args.destination, args.cache)
