#!/usr/bin/env python3
"""Retain the first four volumes of a CC0 acquired four-echo BOLD series.

Original compressed images are checked against pinned git-annex size/MD5 and
hashed with SHA-256. Only dim[4] and the array extent change; raw spatial headers,
scaling, and retained voxel bytes are preserved. No resampling or anonymization.
"""
import argparse
import concurrent.futures
import gzip
import hashlib
import json
from pathlib import Path
import re
import struct
import urllib.request

COMMIT = "0f274e6244a246ba7588cab117326fd90aa75fa1"
RAW = f"https://raw.githubusercontent.com/OpenNeuroDatasets/ds000258/{COMMIT}/"
S3 = "https://s3.amazonaws.com/openneuro.org/ds000258/"
VOLUMES = 4


def fetch(url):
    with urllib.request.urlopen(url, timeout=90) as response:
        return response.read()


def create(destination, cache):
    destination.mkdir(parents=True, exist_ok=False)
    dataset = destination / "dataset"
    dataset.mkdir()
    cache.mkdir(parents=True, exist_ok=True)
    description = fetch(RAW + "dataset_description.json")
    readme = fetch(RAW + "README")
    (destination / "original-dataset-description.json").write_bytes(description)
    (destination / "original-README").write_bytes(readme)
    desc = json.loads(description)
    assert desc["License"] == "CC0"
    desc["Name"] += " — first four volumes of sub-04570, software qualification excerpt"
    desc["DatasetType"] = "raw"
    (dataset / "dataset_description.json").write_text(json.dumps(desc, indent=2) + "\n")
    (dataset / "LICENSE").write_text("CC0 1.0 Universal: https://creativecommons.org/publicdomain/zero/1.0/\n")
    (dataset / "README").write_text(
        "Acquired OpenNeuro ds000258 sub-04570, first four volumes of each of four echoes.\n"
        "Retained voxel bytes and spatial/scaling headers are unchanged. Temporal dim[4] is 4.\n"
        "Small software qualification excerpt; not a complete run or scanner/clinical validation.\n")

    def echo(index):
        name = f"sub-04570/func/sub-04570_task-rest_echo-{index}_bold.nii.gz"
        link = fetch(RAW + name).decode()
        size, expected_md5 = re.search(r"MD5E-s(\d+)--([a-f0-9]{32})", link).groups()
        original = cache / Path(name).name
        if not original.exists():
            original.write_bytes(fetch(S3 + name))
        encoded = original.read_bytes()
        assert len(encoded) == int(size)
        assert hashlib.md5(encoded).hexdigest() == expected_md5
        source = gzip.decompress(encoded)
        endian = "<" if struct.unpack_from("<i", source)[0] == 348 else ">"
        assert struct.unpack_from(endian + "i", source)[0] == 348 and source[344:348] == b"n+1\0"
        dimensions = struct.unpack_from(endian + "8h", source, 40)
        assert dimensions[0] == 4 and dimensions[4] >= VOLUMES
        offset = int(struct.unpack_from(endian + "f", source, 108)[0])
        width = struct.unpack_from(endian + "h", source, 72)[0] // 8
        volume_bytes = dimensions[1] * dimensions[2] * dimensions[3] * width
        assert len(source) == offset + volume_bytes * dimensions[4]
        excerpt = bytearray(source[:offset + volume_bytes * VOLUMES])
        struct.pack_into(endian + "h", excerpt, 48, VOLUMES)
        out = dataset / name.removesuffix(".gz")
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(excerpt)
        metadata_name = name.removesuffix(".nii.gz") + ".json"
        metadata = fetch(RAW + metadata_name)
        (dataset / metadata_name).write_bytes(metadata)
        return {"echo": index, "originalPath": name, "sourceUrl": S3 + name,
                "gitAnnexPointerUrl": RAW + name, "size": len(encoded), "md5": expected_md5,
                "sha256": hashlib.sha256(encoded).hexdigest(), "originalDimensions": dimensions[1:5],
                "metadataSha256": hashlib.sha256(metadata).hexdigest(), "retainedVolumes": [0, VOLUMES], "endian": endian,
                "outputPath": str(out.relative_to(dataset)), "outputSha256": hashlib.sha256(excerpt).hexdigest(),
                "retainedVoxelSha256": hashlib.sha256(excerpt[offset:]).hexdigest()}

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        sources = list(pool.map(echo, range(1, 5)))
    record = {"kind": "acquired-temporal-excerpt", "license": "CC0-1.0", "repository": "OpenNeuroDatasets/ds000258",
              "commit": COMMIT, "datasetDOI": "10.18112/openneuro.ds000258.v1.0.1",
              "generatorSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "transform": "Retain original header/extension bytes and first four volume arrays; replace dim[4] with 4 in original byte order; remove gzip wrapping.",
              "sources": sources,
              "files": {str(p.relative_to(dataset)): {"size": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                        for p in sorted(dataset.rglob("*")) if p.is_file()}}
    (destination / "provenance.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps({"destination": str(destination), "sourceBytes": sum(s["size"] for s in sources),
                      "retainedBytes": sum(f["size"] for f in record["files"].values())}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--cache", type=Path, default=Path("target/acquired-multiecho-downloads"))
    args = parser.parse_args()
    create(args.destination, args.cache)
