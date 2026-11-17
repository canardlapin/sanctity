#!/usr/bin/env python3
"""Independent bytes/geometry observations and pinned official-validator comparisons."""
import argparse
import gzip
import hashlib
import itertools
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "modules/app/src/test/resources/mri-conformance"
CORPUS = BASE / "calibration-inputs"
ACQUIRED = BASE / "acquired-calibration"
PINS = ROOT / "modules/app/src/test/resources/conformance"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", action="store_true")
    parser.add_argument("--roundtrip", type=Path)
    args = parser.parse_args()
    import nibabel as nib
    import numpy as np
    assert nib.__version__ == "5.3.2" and np.__version__ == "2.2.6"
    headers = {}
    for folder, dataset in [(CORPUS, CORPUS / "dataset"), (ACQUIRED, ACQUIRED)]:
        provenance = json.loads((folder / "provenance.json").read_text())
        for name, expected in provenance["files"].items():
            path = dataset / name
            data = path.read_bytes()
            assert len(data) == expected["size"] and hashlib.sha256(data).hexdigest() == expected["sha256"]
            if not (name.endswith(".nii") or name.endswith(".nii.gz")):
                continue
            im = nib.load(path)
            values = im.get_fdata()
            assert np.isfinite(values).all()
            q, qc = im.get_qform(coded=True)
            s, sc = im.get_sform(coded=True)
            assert im.header.get_xyzt_units()[0] == "mm" and q is not None and s is not None
            headers[str(path.relative_to(BASE))] = {"sha256": expected["sha256"], "dimensions": list(im.shape),
                "voxelsChecked": int(values.size), "minimum": float(values.min()), "maximum": float(values.max()),
                "qformCode": int(qc), "sformCode": int(sc), "qformMillimetres": q[:3].tolist(), "sformMillimetres": s[:3].tolist()}
    epi = [v for p, v in headers.items() if "ds004146" in p]
    corners = np.array([list(p) + [1] for p in itertools.product(*[(0, d - 1) for d in epi[0]["dimensions"][:3]])])
    distance = max(float(np.linalg.norm(corners @ (np.array(epi[0][k]) - np.array(epi[1][k])).T, axis=1).max())
                   for k in ("qformMillimetres", "sformMillimetres"))
    assert distance > 0.001  # An acquired pair may be BIDS-valid while outside our same-grid recipe policy.
    deno = str(ROOT / "target/conformance-tools/deno")
    assert subprocess.check_output([deno, "--version"], text=True).splitlines()[0].split()[:2] == ["deno", "2.5.4"]
    pin = json.loads((PINS / "validator.json").read_text())
    schema = gzip.decompress((PINS / "schema-1.11.1.json.gz").read_bytes())
    assert hashlib.sha256(schema).hexdigest() == pin["schemaSha256"]
    out = Path(tempfile.mkdtemp(prefix="calibration-conformance-", dir=ROOT / "target"))
    schema_file = out / "schema.json"
    schema_file.write_bytes(schema)

    def validate(name, dataset):
        report = out / (name + ".json")
        run = subprocess.run([deno, "run", "-ERWN", "--lock=" + str(PINS / "deno.lock"), "--frozen", pin["validator"], str(dataset),
                              "--schema", schema_file.as_uri(), "--max-rows", "-1", "--format", "json", "--outfile", str(report)],
                             capture_output=True, text=True, env={**os.environ, "BIDS_SCHEMA": schema_file.as_uri()}, timeout=180)
        (out / (name + ".log")).write_text(run.stdout + run.stderr)
        value = json.loads(report.read_text())
        errors = sorted({(i["code"], i.get("subCode", "")) for i in value["issues"]["issues"] if i["severity"] == "error"})
        warnings = sorted({(i["code"], i.get("subCode", "")) for i in value["issues"]["issues"] if i["severity"] == "warning"})
        assert run.returncode == (16 if errors else 0) and value["summary"]["schemaVersion"] == "1.2.2"
        print(name, errors, flush=True)
        return {"case": name, "officialErrors": errors, "officialWarnings": warnings}

    cases = []
    for case in json.loads((CORPUS / "contract.json").read_text())["cases"]:
        dataset = out / case["name"]
        shutil.copytree(CORPUS / "dataset", dataset)
        for name in case.get("delete", []):
            (dataset / name).unlink()
        for name, fields in case.get("jsonRemove", {}).items():
            path = dataset / name
            value = json.loads(path.read_text())
            for field in fields:
                del value[field]
            path.write_text(json.dumps(value) + "\n")
        for name, fields in case.get("jsonFields", {}).items():
            path = dataset / name
            value = json.loads(path.read_text())
            value.update(fields)
            path.write_text(json.dumps(value) + "\n")
        for name, patches in case.get("floatPatches", {}).items():
            path = dataset / name
            data = bytearray(path.read_bytes())
            for offset, value in patches.items():
                struct.pack_into("<f", data, int(offset), value)
            path.write_bytes(data)
        for src, dest in case.get("copy", {}).items():
            shutil.copyfile(dataset / src, dataset / dest)
        cases.append({**validate(case["name"], dataset), "calibratedReady": case["calibratedReady"]})
    result = json.loads(json.dumps({"validator": pin["validator"], "schemaSha256": pin["schemaSha256"], "niftiHeadersChecked": True,
        "ignoredIssues": [], "nibabel": nib.__version__, "numpy": np.__version__, "images": headers,
        "acquiredEpiMaximumCornerDistanceMm": distance, "acquiredEpiMeetsSameGridPolicy": False, "cases": cases}))
    (out / "comparison.json").write_text(json.dumps(result, indent=2) + "\n")
    expected = CORPUS / "independent-results.json"
    if args.record:
        expected.write_text(json.dumps(result, indent=2) + "\n")
    else:
        assert json.loads(expected.read_text()) == result
    if args.roundtrip:
        roundtrip = validate("roundtrip", args.roundtrip.resolve())
        assert not roundtrip["officialErrors"]
        (out / "roundtrip.json").write_text(json.dumps(roundtrip, indent=2) + "\n")
    print(out)


if __name__ == "__main__":
    main()
