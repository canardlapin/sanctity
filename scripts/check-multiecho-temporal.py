#!/usr/bin/env python3
"""Independent all-voxel multi-echo oracle and measured producer resource evidence.

The numerical verifier is the stdlib direct-byte/two-pass checker. NiBabel is
used separately to record independent coded-affine observations, never expected
temporal map values. Run with target/mri-python/bin/python.
"""
import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("oracle", ROOT / "scripts/check-temporal-statistics.py")
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)


def geometry_record(path):
    import nibabel as nib
    image = nib.load(path)
    factor = {"meter": 1000, "mm": 1, "micron": .001}[image.header.get_xyzt_units()[0]]
    result = {"dimensions": image.shape, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    for form in ("qform", "sform"):
        matrix, code = getattr(image, "get_" + form)(coded=True)
        result[form + "Code"] = int(code)
        result[form + "Millimetres"] = None if matrix is None else (matrix[:3] * factor).tolist()
    return result


def qualify(destination, contract="2"):
    destination.mkdir(parents=True, exist_ok=False)
    cases = []
    corpus = ROOT / "modules/app/src/test/resources/mri-conformance"
    for folder in ("analysis-inputs", "acquired-multiecho"):
        base = corpus / folder / "dataset"
        cases.append((folder, [(str(p.relative_to(base)), p.read_bytes()) for p in sorted(base.rglob("*.nii"))]))
    mixed = []
    for echo, (version, endian, datatype, compressed) in enumerate(((1, "<", 4, False), (1, ">", 64, True), (2, ">", 16, False)), 1):
        data = bytearray(oracle.fixture(version, endian, datatype))
        struct.pack_into(endian + ("2f" if version == 1 else "2d"), data, 112 if version == 1 else 176, 2.5 + echo, -7 * echo)
        mixed.append((f"sub-01_task-mixed_echo-{echo}_bold.nii" + (".gz" if compressed else ""),
                      gzip.compress(data, mtime=0) if compressed else bytes(data)))
    cases.append(("mixed-versions-orders-scaling-compression", mixed))
    for echoes in (2, 8, 16):
        for volumes in (4, 64):
            data = oracle.fixture(1, "<", 16, (32, 32, 16, volumes))
            cases.append((f"memory-{echoes}-echoes-{volumes}-volumes",
                          [(f"sub-01_task-memory_echo-{i}_bold.nii", data) for i in range(1, echoes + 1)]))
    report = []
    geometries = {}
    for name, images in cases:
        work = destination / name
        (work / "input").mkdir(parents=True)
        rows = []
        for index, (path, data) in enumerate(images):
            target = work / "input" / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            rows.append({"path": path, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(), "outputs": [
                {"statistic": statistic, "path": f"echo-{index}-{statistic}.nii", "sidecarPath": f"echo-{index}-{statistic}.json",
                 "sidecar": {"Statistic": statistic, "Sources": ["bids:input:" + path]}}
                for statistic in ("mean", "stddev")]})
            if not name.startswith("memory"):
                geometries[name + "/" + path] = geometry_record(target)
        request = {"contract": contract, "description": {"Name": "Independent multi-echo numerical qualification"},
                   "parameters": oracle.PARAMETERS, "rows": rows}
        (work / "request.json").write_text(json.dumps(request, indent=2) + "\n")
        result = subprocess.run([sys.executable, "-I", str(oracle.RECIPE), str(work / "request.json"),
                                 str(work / "input"), str(work / "output")], check=True, capture_output=True, text=True, timeout=300)
        evidence = {"case": name, "echoes": len(images), "inputBytes": sum(len(data) for _, data in images),
                    "inputs": [{"path": r["path"], "sha256": r["sha256"], "size": r["size"]} for r in rows],
                    "outputBytes": sum(p.stat().st_size for p in (work / "output").rglob("*") if p.is_file()),
                    **oracle.verify(work), **json.loads(result.stdout)}
        report.append(evidence)
        print(json.dumps({k: v for k, v in evidence.items() if k != "inputs"}), flush=True)
    # Invalid requests must be refused before input access/allocation.
    base = json.loads((destination / "analysis-inputs/request.json").read_text())
    bad = []
    for name in ("empty", "over-limit", "duplicate-source", "duplicate-output", "unknown-version", "unknown-statistic", "over-byte-budget"):
        request = json.loads(json.dumps(base))
        if name == "empty":
            request["rows"] = []
        elif name == "over-limit":
            request["rows"] *= 6
        elif name == "duplicate-source":
            request["rows"][1]["path"] = request["rows"][0]["path"]
        elif name == "duplicate-output":
            request["rows"][1]["outputs"][0]["path"] = request["rows"][0]["outputs"][0]["path"]
        elif name == "unknown-version":
            request["contract"] = "unknown"
        elif name == "unknown-statistic":
            request["rows"][0]["outputs"][1]["statistic"] = "variance"
        else:
            request["rows"][0]["size"] = 8 * 1024**3 + 1
        work = destination / name
        work.mkdir()
        (work / "request.json").write_text(json.dumps(request))
        result = subprocess.run([sys.executable, "-I", str(oracle.RECIPE), str(work / "request.json"),
                                 str(work / "nonexistent-input"), str(work / "output")], capture_output=True, text=True, timeout=30)
        assert result.returncode != 0 and not (work / "output").exists(), name
        bad.append({"case": name, "exitCode": result.returncode, "reason": result.stderr.strip().splitlines()[-1]})
    receipt = {"contract": contract, "scope": "Producer numerical qualification; companion readiness is assessed separately by Scala", "producerSha256": hashlib.sha256(oracle.RECIPE.read_bytes()).hexdigest(),
               "oracleSha256": hashlib.sha256(Path(oracle.__file__).read_bytes()).hexdigest(),
               "driverSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               "cases": report, "rejectedRequests": bad, "geometryObserver": "NiBabel 5.3.2", "geometry": geometries}
    (destination / "report.json").write_text(json.dumps(receipt, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--retain", type=Path)
    parser.add_argument("--contract", choices=["2", "3"], default="2")
    args = parser.parse_args()
    qualify(args.destination.resolve(), args.contract)
    if args.retain:
        shutil.copyfile(args.destination / "report.json", args.retain)
