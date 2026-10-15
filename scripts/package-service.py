#!/usr/bin/env python3
"""Build a closed Java runtime distribution; no source checkout is needed to run it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("kernel", "application", "bids", "objects", "postgres", "http", "service")
BUILD_COMMAND = ["sbt", *(module + "/clean" for module in MODULES), "service/inputs"]
LAUNCHER = b'''#!/bin/sh
set -eu
umask 077
service_home=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -n "${JAVA_HOME:-}" ]; then
  java_command="$JAVA_HOME/bin/java"
else
  java_command=java
fi
exec "$java_command" -cp "$service_home/lib/*" sanctity.app.ServiceMain "$@"
'''


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def sources():
    files = [ROOT / "build.sbt", ROOT / "vendor/bids4s-source.zip", ROOT / "vendor/bids4s-source.json",
             ROOT / "scripts/package-service.py", ROOT / "docs/service-distribution.md"]
    files += [p for p in (ROOT / "project").iterdir() if p.is_file()]
    files += [p for p in (ROOT / "docs/deployment").glob("*.example") if p.is_file()]
    # processing-mri contributes to the existing semantic BuildIdentity, but is not a runtime dependency.
    for module in (*MODULES, "processing-mri"):
        files += [p for p in (ROOT / "modules" / module / "src/main").rglob("*") if p.is_file()]
    return {p.relative_to(ROOT).as_posix(): digest(p.read_bytes()) for p in sorted(set(files))}


def verify_upstream(expected):
    archive = ROOT / "vendor/bids4s-source.zip"
    if digest(archive.read_bytes()) != expected:
        raise ValueError("bids4s archive does not match the build pin")
    extracted = ROOT / "target/source-dependencies" / ("bids4s-" + expected)
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            if not entry.is_dir() and (extracted / entry.filename).read_bytes() != z.read(entry):
                raise ValueError("extracted bids4s source differs from pin: " + entry.filename)
        expected_sources = {n for n in z.namelist() if "/src/" in n and not n.endswith("/")}
        actual_sources = {p.relative_to(extracted).as_posix() for p in (extracted / "modules").rglob("*")
                          if p.is_file() and "/src/" in p.relative_to(extracted).as_posix()
                          and "target" not in p.relative_to(extracted).parts}
        if expected_sources != actual_sources:
            raise ValueError("untracked or missing bids4s source files in extraction")
        return z.read("LICENSE")


def assemble(inputs, source_inventory):
    tree = ET.parse(inputs).getroot()
    version = tree.attrib["version"]
    if not re.fullmatch(r"[A-Za-z0-9._-]+", version):
        raise ValueError("unsafe application version")
    contents = {"bin/sanctity": LAUNCHER, "README.md": (ROOT / "docs/service-distribution.md").read_bytes(),
                "metadata/source.json": json_bytes(source_inventory),
                "metadata/bids4s-source.json": (ROOT / "vendor/bids4s-source.json").read_bytes(),
                "licenses/bids4s-LICENSE": verify_upstream(tree.attrib["upstreamSha256"])}
    for example in sorted((ROOT / "docs/deployment").glob("*.example")):
        contents["deployment/" + example.name] = example.read_bytes()
    dependencies = []
    internal_modules = set()
    for jar in tree.findall("jar"):
        path = Path(jar.attrib["path"])
        data = path.read_bytes()
        name = "lib/" + path.name
        if name in contents:
            raise ValueError("duplicate runtime jar: " + path.name)
        contents[name] = data
        own = jar.attrib["internal"] == "true" and jar.attrib["upstream"] != "true"
        if own:
            internal_modules.add(path.name.split("_3-")[0])
        licenses = [dict(x.attrib) for x in jar.findall("license")]
        if jar.attrib["upstream"] == "true":
            licenses = [{"name": "Apache-2.0", "file": "licenses/bids4s-LICENSE"}]
        if own:
            licenses = [{"name": "NOASSERTION", "reason": "Sanctity has no repository license declaration"}]
        embedded = []
        with zipfile.ZipFile(path) as z:
            for member in sorted(z.namelist()):
                base = Path(member).name.lower()
                if not member.endswith("/") and (base.startswith("license") or base.startswith("notice")):
                    # Encode the original path into a safe unique filename; retain provenance separately.
                    destination = "licenses/embedded/" + path.name + "/" + digest(member.encode())[:16] + ".txt"
                    contents[destination] = z.read(member)
                    embedded.append({"jarEntry": member, "file": destination})
        dependencies.append({"path": name, "module": jar.attrib["module"], "sha256": digest(data),
                             "licenses": licenses or [{"name": "NOASSERTION", "reason": "not reported by dependency metadata"}],
                             "embeddedNotices": embedded})
    if internal_modules != set(MODULES):
        raise ValueError("unexpected service module closure: " + repr(internal_modules))
    manifest = {"schemaVersion": 1, "application": "Sanctity", "version": version,
                "scalaVersion": tree.attrib["scala"], "sbtVersion": tree.attrib["sbt"],
                "javaMinimum": int(tree.attrib["javaMinimum"]), "bids4sSourceSha256": tree.attrib["upstreamSha256"],
                "sourceInventorySha256": digest(contents["metadata/source.json"]),
                "dependencies": dependencies,
                "files": {p: {"sha256": digest(data), "bytes": len(data), "mode": "0755" if p == "bin/sanctity" else "0644"}
                          for p, data in sorted(contents.items())},
                "limits": ["Internal development distribution; Sanctity license remains unspecified.",
                           "Identical inputs produce identical ZIP bytes; independent compiler reproducibility is not claimed.",
                           "Install/upgrade configuration and least-privilege runtime qualification remain O01.2-O01.4."]}
    contents["manifest.json"] = json_bytes(manifest)
    return version, contents, manifest


def write_zip(path, prefix, contents):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, data in sorted(contents.items()):
            entry = zipfile.ZipInfo(prefix + "/" + name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = (0o100755 if name == "bin/sanctity" else 0o100644) << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(entry, data, compresslevel=9)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="new output directory (never overwritten)")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        parser.error("output already exists; choose a new directory")
    before = sources()
    # Clear first-party build outputs; the vendored dependency is independently pinned and checked below.
    subprocess.run(BUILD_COMMAND, cwd=ROOT, check=True)
    if sources() != before:
        raise ValueError("source changed during build; repeat from a stable working tree")
    version, contents, manifest = assemble(ROOT / "modules/service/target/distribution-inputs.xml", before)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".sanctity-dist-", dir=output.parent) as temporary:
        stage = Path(temporary)
        filename = "sanctity-" + version + ".zip"
        artifact = stage / filename
        write_zip(artifact, "sanctity-" + version, contents)
        repeat = stage / "repeat.zip"
        write_zip(repeat, "sanctity-" + version, contents)
        if artifact.read_bytes() != repeat.read_bytes():
            raise ValueError("identical package inputs produced different ZIP bytes")
        repeat.unlink()
        sha = digest(artifact.read_bytes())
        (stage / (filename + ".sha256")).write_text(sha + "  " + filename + "\n")
        (stage / "manifest.json").write_bytes(json_bytes(manifest))
        (stage / "build-receipt.json").write_bytes(json_bytes({"schemaVersion": 1, "artifact": filename,
            "sha256": sha, "bytes": artifact.stat().st_size, "manifestSha256": digest(json_bytes(manifest)),
            "sourceInventorySha256": manifest["sourceInventorySha256"], "sameInputZipRepeatability": "pass",
            "buildCommand": BUILD_COMMAND,
            "java": subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr.strip()}))
        # mkdir is the no-clobber boundary, including a concurrent writer choosing the same name.
        output.mkdir()
        for file in stage.iterdir():
            os.replace(file, output / file.name)
    print(json.dumps({"artifact": str(output / filename), "sha256": sha, "dependencies": len(manifest["dependencies"])}))


if __name__ == "__main__":
    main()
