#!/usr/bin/env python3
"""Re-run pinned official-validator comparisons. Normal Scala tests remain offline.
Requires Deno 2.5.4 (--deno); never installs tools globally.
"""
import argparse
import gzip
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "modules/app/src/test/resources/conformance"


def load(name):
    return json.loads((RESOURCES / name).read_text())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--deno", default="deno")
    args = parser.parse_args()
    deno = str(pathlib.Path(args.deno).resolve()) if "/" in args.deno else args.deno
    version = subprocess.check_output([deno, "--version"], text=True).splitlines()[0]
    if version.split()[:2] != ["deno", "2.5.4"]:
        raise SystemExit("Use pinned Deno 2.5.4; found " + version)
    source, validator = load("source.json"), load("validator.json")
    archive = RESOURCES / "bids-examples.zip"
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == source["archiveSha256"]
    schema = gzip.decompress((RESOURCES / "schema-1.11.1.json.gz").read_bytes())
    assert hashlib.sha256(schema).hexdigest() == validator["schemaSha256"]
    out = pathlib.Path(tempfile.mkdtemp(prefix="conformance-", dir=ROOT / "target"))
    schema_file = out / "schema.json"
    schema_file.write_bytes(schema)
    with zipfile.ZipFile(archive) as files:
        assert set(files.namelist()) == set(source["files"])
        for name in files.namelist():
            assert not pathlib.PurePosixPath(name).is_absolute() and ".." not in pathlib.PurePosixPath(name).parts
            data = files.read(name)
            assert hashlib.sha256(data).hexdigest() == source["files"][name]
            dest = out / "originals" / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
    results = []
    for case in load("cases.json"):
        dataset = out / case["id"]
        shutil.copytree(out / "originals" / case["dataset"], dataset)
        mutation = case["mutation"]
        if mutation:
            path = dataset / mutation["file"]
            document = json.loads(path.read_text())
            if mutation.get("remove"):
                del document[mutation["field"]]
            else:
                document[mutation["field"]] = mutation["value"]
            path.write_text(json.dumps(document) + "\n")
        report = out / (case["id"] + ".json")
        cmd = [deno, "run", "-ERWN", "--lock=" + str(RESOURCES / "deno.lock"), "--frozen",
               validator["validator"], str(dataset), "--schema", schema_file.as_uri(),
               "--ignoreNiftiHeaders", "--max-rows", "-1", "--config", str(RESOURCES / "validator-config.json"),
               "--format", "json", "--outfile", str(report)]
        run = subprocess.run(cmd, capture_output=True, text=True, env={**os.environ, "BIDS_SCHEMA": schema_file.as_uri()})
        (out / (case["id"] + ".log")).write_text(run.stdout + run.stderr)
        if not report.exists():
            raise RuntimeError("Validator did not produce a report: " + run.stderr)
        result = json.loads(report.read_text())
        errors = sorted({(i["code"], i.get("subCode", "")) for i in result["issues"]["issues"] if i["severity"] == "error"})
        assert errors == [tuple(e) for e in case["officialErrors"]], (case["id"], errors)
        assert run.returncode == (16 if errors else 0), (case["id"], run.returncode)
        assert result["summary"]["schemaVersion"] == "1.2.2"
        results.append({"case": case["id"], "errors": errors, "rawReportSha256": hashlib.sha256(report.read_bytes()).hexdigest()})
        print(case["id"] + ": expected errors agree", flush=True)
    (out / "comparison.json").write_text(json.dumps(results, indent=2) + "\n")
    print("Reports: " + str(out))


if __name__ == "__main__":
    main()
