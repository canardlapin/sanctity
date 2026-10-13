#!/usr/bin/env python3
"""Independent byte decoder and two-pass numerical oracle; the checker uses only stdlib.

Run with the pinned recipe environment. --qualify creates a new evidence directory;
--workspace checks a retained execution directory from TemporalDemo.
"""
import argparse
import array
import gzip
import hashlib
import json
import math
from pathlib import Path
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
RECIPE = ROOT / "modules/processing-mri/src/main/resources/temporal_statistics.py"
PARAMETERS = {"volumes": "all", "ddof": "0", "nonfinite": "reject", "precision": "float64", "algorithm": "shifted-welford-v1"}
FORMATS = {2: "B", 4: "h", 8: "i", 16: "f", 64: "d", 256: "b", 512: "H", 768: "I"}


def decode(path):
    encoded = path.read_bytes()
    data = gzip.decompress(encoded) if path.name.endswith(".gz") else encoded
    endian = "<" if struct.unpack_from("<i", data)[0] in (348, 540) else ">"
    size = struct.unpack_from(endian + "i", data)[0]
    v1 = size == 348
    if size not in (348, 540):
        raise ValueError("unsupported header")
    def read(fmt, offset):
        return struct.unpack_from(endian + fmt, data, offset)
    dimensions = read("8h" if v1 else "8q", 40 if v1 else 16)
    shape = dimensions[1:dimensions[0] + 1]
    datatype = read("h", 70 if v1 else 12)[0]
    offset = int(read("f" if v1 else "q", 108 if v1 else 168)[0])
    slope, intercept = read("2f" if v1 else "2d", 112 if v1 else 176)
    if slope == 0 or math.isnan(slope):
        slope, intercept = 1.0, 0.0
    width = struct.calcsize(FORMATS[datatype])
    assert len(data) == offset + math.prod(shape) * width
    values = [v[0] * slope + intercept for v in struct.iter_unpack(endian + FORMATS[datatype], data[offset:])]
    assert all(math.isfinite(v) for v in values)
    return shape, values


def verify(workspace):
    request = json.loads((workspace / "request.json").read_text())
    worst = 0.0
    count = 0
    for row in request["rows"]:
        path = workspace / "input" / row["path"]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == row["sha256"]
        shape, values = decode(path)
        assert len(shape) == 4
        spatial = math.prod(shape[:3])
        means, deviations = [], []
        for voxel in range(spatial):
            series = [values[voxel + spatial * t] for t in range(shape[3])]
            shifted = [v - series[0] for v in series]
            shift_mean = math.fsum(shifted) / len(series)
            mean = series[0] + shift_mean
            deviation = math.sqrt(math.fsum((v - shift_mean)**2 for v in shifted) / len(series))
            means.append(mean)
            deviations.append(deviation)
        for spec in row["outputs"]:
            output_shape, actual = decode(workspace / "output" / spec["path"])
            assert output_shape == shape[:3]
            expected = means if spec["statistic"] == "mean" else deviations
            for a, b in zip(actual, expected, strict=True):
                error = abs(a - b)
                worst = max(worst, error)
                assert error <= 1e-10 + 1e-12 * abs(b), (spec["path"], a, b)
                count += 1
    return {"checkedOutputVoxels": count, "maximumAbsoluteError": worst, "absoluteTolerance": 1e-10, "relativeTolerance": 1e-12}


def fixture(version, endian, datatype=4, shape=(2, 2, 1, 3)):
    offset = 352 if version == 1 else 544
    b = bytearray(offset)
    v1 = version == 1
    def put(fmt, pos, *values):
        struct.pack_into(endian + fmt, b, pos, *values)
    put("i", 0, 348 if v1 else 540)
    b[344:348] = b"n+1\0" if v1 else b[344:348]
    if not v1:
        b[4:12] = b"n+2\0\r\n\x1a\n"
    put("8h" if v1 else "8q", 40 if v1 else 16, 4, *shape, 1, 1, 1)
    put("h", 70 if v1 else 12, datatype)
    put("h", 72 if v1 else 14, 8 * struct.calcsize(FORMATS[datatype]))
    put("8f" if v1 else "8d", 76 if v1 else 104, 1, 2, 3, 4, 1.5, 1, 1, 1)
    put("f" if v1 else "q", 108 if v1 else 168, offset)
    put("2f" if v1 else "2d", 112 if v1 else 176, 2.5, -7)
    put("B" if v1 else "i", 123 if v1 else 500, 10)
    put("2h" if v1 else "2i", 252 if v1 else 344, 1, 2)
    put("3f" if v1 else "3d", 268 if v1 else 376, 7, 0, 0)
    put("12f" if v1 else "12d", 280 if v1 else 400, 2, 0, 0, 11, 0, 3, 0, 13, 0, 0, 4, 17)
    data = array.array(FORMATS[datatype], (i % 997 - (200 if datatype != 2 else 0) for i in range(math.prod(shape))))
    if (sys.byteorder == "little") != (endian == "<"):
        data.byteswap()
    return bytes(b) + data.tobytes()


def qualify(destination):
    destination.mkdir(parents=True, exist_ok=False)
    cases = []
    for version in (1, 2):
        for endian in ("<", ">"):
            for datatype in (4, 64):
                name = f"nifti{version}-{'le' if endian == '<' else 'be'}-{datatype}"
                data = fixture(version, endian, datatype)
                cases.append((name, data, False))
                cases.append((name + "-gzip", gzip.compress(data, mtime=0), True))
    high_offset = bytearray(fixture(1, "<", 64))
    struct.pack_into("<2f", high_offset, 112, 1, 0)
    struct.pack_into("<12d", high_offset, 352, *(1e12 + t * .001 + voxel * .01 for t in range(3) for voxel in range(4)))
    cases.append(("large-offset-small-variance", bytes(high_offset), False))
    constant = bytearray(high_offset)
    struct.pack_into("<12d", constant, 352, *([7.0] * 12))
    cases.append(("constant-signal", bytes(constant), False))
    for name in ("example4d.nii.gz", "example_nifti2.nii.gz"):
        path = ROOT / "modules/app/src/test/resources/mri-conformance" / name
        cases.append((name.removesuffix(".nii.gz"), path.read_bytes(), True))
    for volumes in (4, 64):
        cases.append((f"memory-{volumes}-volumes", fixture(1, "<", 16, (64, 64, 32, volumes)), False))
    report = []
    for name, data, compressed in cases:
        work = destination / name
        (work / "input").mkdir(parents=True)
        source = "sub-01_task-rest_bold.nii" + (".gz" if compressed else "")
        (work / "input" / source).write_bytes(data)
        request = {"description": {"Name": "independent numerical qualification"}, "parameters": PARAMETERS,
            "rows": [{"path": source, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(), "outputs": [
                {"statistic": s, "path": s + ".nii", "sidecarPath": s + ".json", "sidecar": {"Statistic": s}} for s in ("mean", "stddev")]}]}
        (work / "request.json").write_text(json.dumps(request))
        result = subprocess.run([sys.executable, "-I", str(RECIPE), str(work / "request.json"), str(work / "input"), str(work / "output")],
            check=True, capture_output=True, text=True, timeout=300)
        metrics = json.loads(result.stdout)
        evidence = {"case": name, "inputSha256": hashlib.sha256(data).hexdigest(), **verify(work), **metrics}
        report.append(evidence)
        print(json.dumps(evidence), flush=True)
    (destination / "report.json").write_text(json.dumps({"producerSha256": hashlib.sha256(RECIPE.read_bytes()).hexdigest(),
        "oracleSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), "cases": report}, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--qualify", type=Path)
    group.add_argument("--workspace", type=Path)
    args = parser.parse_args()
    if args.qualify:
        qualify(args.qualify.resolve())
    else:
        print(json.dumps(verify(args.workspace.resolve())))
