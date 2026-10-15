#!/usr/bin/env python3
"""Qualify a distribution using only its files, Java and disposable PostgreSQL."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile

PG_IMAGE = "postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    artifact = args.artifact.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    checks = []
    receipt = {"schemaVersion": 1, "artifact": artifact.name, "artifactSha256": sha(artifact.read_bytes()),
               "postgresImage": PG_IMAGE, "checks": checks, "status": "failed",
               "scope": "Synthetic single-host Java-only packaged runtime; not institutional qualification"}
    container = None
    server = None
    secret = None
    server_log = None

    def command(argv, **kwargs):
        return subprocess.run(argv, check=True, capture_output=True, text=True, timeout=120, **kwargs).stdout.strip()

    try:
        with tempfile.TemporaryDirectory(prefix="sanctity packaged runtime ") as directory:
            runtime = Path(directory)
            os.chmod(runtime, 0o700)
            with zipfile.ZipFile(artifact) as z:
                names = z.namelist()
                check(len(names) == len(set(names)), "duplicate archive entry")
                roots = {PurePosixPath(n).parts[0] for n in names}
                check(len(roots) == 1, "multiple distribution roots")
                prefix = next(iter(roots))
                check(prefix.startswith("sanctity-"), "invalid distribution root")
                manifest = json.loads(z.read(prefix + "/manifest.json"))
                check(set(names) == {prefix + "/" + p for p in manifest["files"]} | {prefix + "/manifest.json"},
                      "manifest does not describe exact archive contents")
                for name in names:
                    parts = PurePosixPath(name)
                    check(not parts.is_absolute() and ".." not in parts.parts and "\\" not in name,
                          "unsafe archive path")
                    check(z.getinfo(name).external_attr >> 16 & 0o170000 == 0o100000, "non-regular archive member")
                    data = z.read(name)
                    relative = name[len(prefix) + 1:]
                    if relative != "manifest.json":
                        expected = manifest["files"][relative]
                        check(sha(data) == expected["sha256"] and len(data) == expected["bytes"], "payload mismatch: " + relative)
                    destination = runtime / name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(data)
                    os.chmod(destination, 0o755 if relative == "bin/sanctity" else 0o644)
                entries = set()
                for dependency in manifest["dependencies"]:
                    check(dependency["licenses"], "missing license inventory")
                    data = z.read(prefix + "/" + dependency["path"])
                    check(sha(data) == dependency["sha256"], "dependency digest mismatch")
                    with zipfile.ZipFile(io.BytesIO(data)) as jar:
                        entries.update(jar.namelist())
                for required in ("sanctity/app/ServiceMain.class", "sanctity/app/ApiServer.class",
                                 "sanctity/app/RepositoryCli.class", "sanctity/app/RecoveryCli.class",
                                 "sanctity/app/MigrationCli.class", "sanctity/service-build.properties"):
                    check(required in entries, "missing entry point/resource: " + required)
                check(any(p.endswith(".sql") for p in entries), "SQL migrations missing")
                check(any(p.endswith("index.html") for p in entries), "console missing")
                check(not any(p.startswith(("sanctity/processing/", "munit/")) or p == "sanctity/app/Demo.class"
                              or p.endswith(".py") for p in entries), "development/analysis payload in runtime")
                receipt["manifestSha256"] = sha(z.read(prefix + "/manifest.json"))
                receipt["sourceInventorySha256"] = manifest["sourceInventorySha256"]
                receipt["runtimeJarCount"] = len(manifest["dependencies"])
            checks.append("exact payload/dependency digests, runtime resources, licenses and module exclusion")
            binary = runtime / "runtime-bin"
            binary.mkdir()
            for name in ("java", "dirname"):
                resolved = shutil.which(name)
                check(resolved is not None, "missing prerequisite: " + name)
                (binary / name).symlink_to(Path(resolved).resolve())
            env = {"PATH": str(binary), "HOME": str(runtime), "TMPDIR": str(runtime), "LANG": "C.UTF-8"}
            launch = runtime / prefix / "bin/sanctity"

            def cli(*arguments):
                return command([str(launch), *arguments], cwd=runtime, env=env)

            receipt["versionOutput"] = cli("--version")
            check("serve" in cli("--help"), "help unavailable")
            bad = subprocess.run([str(launch), "demo"], cwd=runtime, env=env, capture_output=True, timeout=30)
            check(bad.returncode == 2, "unknown/demo dispatch must fail")
            checks.append("Java-only launcher with spaces, no source/build-tool PATH, version/help and invalid command")
            container = command(["docker", "run", "-d", "--label", "io.sanctity.purpose=package-smoke",
                                 "-e", "POSTGRES_USER=sanctity_test", "-e", "POSTGRES_PASSWORD=ephemeral_test_only",
                                 "-e", "POSTGRES_DB=sanctity_test", "-p", "127.0.0.1::5432", PG_IMAGE])
            deadline = time.monotonic() + 60
            while subprocess.run(["docker", "exec", container, "pg_isready", "-h", "127.0.0.1", "-U", "sanctity_test"],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
                check(time.monotonic() < deadline, "PostgreSQL startup timeout")
                time.sleep(0.25)
            pg_port = command(["docker", "port", container, "5432/tcp"]).rsplit(":", 1)[1]
            storage = runtime / "managed blobs"
            env.update(SANCTITY_JDBC_URL="jdbc:postgresql://127.0.0.1:" + pg_port + "/sanctity_test",
                       SANCTITY_DB_USER="sanctity_test", SANCTITY_DB_PASSWORD="ephemeral_test_only",
                       SANCTITY_STORAGE=str(storage))
            cli("migrate")
            cli("migrate")
            checks.append("packaged SQL migrations applied and repeated successfully")
            principal, custodian = str(uuid.uuid4()), str(uuid.uuid4())
            dataset = json.loads(cli("repository", "create", principal, custodian))["dataset"]
            credential_file = runtime / "credential.json"
            issuance = cli("repository", "issue-credential", principal, "600", "package-smoke", str(credential_file))
            credential = json.loads(credential_file.read_text())
            secret = credential["token"]
            check(secret not in issuance and credential_file.stat().st_mode & 0o777 == 0o600,
                  "credential privacy failure")
            source = runtime / "native source"
            source.mkdir()
            description = b'{"Name":"Package smoke fixture","BIDSVersion":"1.10.0","DatasetType":"raw"}\n'
            (source / "dataset_description.json").write_bytes(description)
            (source / "README").write_text("Synthetic packaging fixture; no human data.\n")
            cli("repository", "import", principal, dataset, str(source), str(storage), "package-smoke-import")
            checks.append("trusted create, private credential issuance and native import from packaged resources")
            with socket.socket() as port_socket:
                port_socket.bind(("127.0.0.1", 0))
                env["SANCTITY_HTTP_PORT"] = str(port_socket.getsockname()[1])
            server_log = (output / "service.log").open("w")
            server = subprocess.Popen([str(launch), "serve"], cwd=runtime, env=env, stdout=server_log, stderr=subprocess.STDOUT)
            base = "http://127.0.0.1:" + env["SANCTITY_HTTP_PORT"]

            def request(path, token=None):
                req = urllib.request.Request(base + path, headers={"Authorization": "Bearer " + token} if token else {})
                try:
                    with urllib.request.urlopen(req, timeout=3) as response:
                        return response.status, response.read(), dict(response.headers)
                except urllib.error.HTTPError as error:
                    return error.code, error.read(), dict(error.headers)

            deadline = time.monotonic() + 30
            while True:
                check(server.poll() is None, "service exited before serving")
                try:
                    status, html, _ = request("/")
                    if status == 200:
                        break
                except urllib.error.URLError:
                    pass
                check(time.monotonic() < deadline, "API startup timeout")
                time.sleep(0.2)
            check(b"<html" in html.lower(), "console asset not served")
            check(request("/v1/me")[0] == 401, "anonymous API unexpectedly authorized")
            status, me, _ = request("/v1/me", secret)
            check(status == 200 and json.loads(me)["principal"] == principal, "credential authentication failed")
            status, state, _ = request("/v1/datasets/" + dataset, secret)
            check(status == 200 and json.loads(state)["head"], "import did not publish a release")
            release = json.loads(state)["head"]
            status, content, headers = request("/v1/datasets/" + dataset + "/releases/" + release + "/file?path=dataset_description.json", secret)
            check(status == 200 and content == description, "packaged byte retrieval differs from input")
            check(next(v for k, v in headers.items() if k.lower() == "x-sanctity-sha256") == sha(description), "delivery digest incorrect")
            checks.append("live console, anonymous denial, authenticated identity, accepted head and byte-exact HTTP delivery")
            policy = runtime / "policy.json"
            cli("recovery", "capture-policy", str(policy))
            check(policy.is_file() and len(Path(str(policy) + ".sha256").read_text()) == 64, "policy capture missing")
            cli("repository", "revoke-credential", credential["credential"])
            check(request("/v1/me", secret)[0] == 401, "revoked credential remained usable")
            checks.append("packaged policy capture and live credential revocation")
            receipt["postgresVersion"] = command(["docker", "exec", container, "postgres", "--version"])
            receipt["status"] = "passed"
    finally:
        if server is not None:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=10)
        if server_log is not None:
            server_log.close()
            log = output / "service.log"
            if secret:
                log.write_text(log.read_text().replace(secret, "<redacted>"))
        if container:
            subprocess.run(["docker", "rm", "-fv", container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
