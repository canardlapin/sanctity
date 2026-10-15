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
    password_canary = "ephemeral_test_only"
    runtime_password = "ephemeral_runtime_only"
    unsafe_canary = "DO_NOT_PRINT_configuration_9c271"
    log_clean = True

    def command(argv, **kwargs):
        return subprocess.run(argv, check=True, capture_output=True, text=True, timeout=120, **kwargs).stdout.strip()

    try:
        with tempfile.TemporaryDirectory(prefix="sanctity packaged runtime ") as directory:
            runtime = Path(directory).resolve()
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
            storage.mkdir(mode=0o700)
            password_file = runtime / "database-password"
            password_file.write_text(password_canary + "\n")
            os.chmod(password_file, 0o600)
            env.update(SANCTITY_JDBC_URL="jdbc:postgresql://127.0.0.1:" + pg_port + "/sanctity_test",
                       SANCTITY_DB_USER="sanctity_test", SANCTITY_DB_PASSWORD_FILE=str(password_file),
                       SANCTITY_STORAGE=str(storage))
            runtime_file = runtime / "runtime-password"
            runtime_file.write_text(runtime_password + "\n")
            os.chmod(runtime_file, 0o600)
            command(["docker", "exec", container, "psql", "-U", "sanctity_test", "-d", "sanctity_test", "-v", "ON_ERROR_STOP=1", "-c",
                     "CREATE ROLE sanctity_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD 'ephemeral_runtime_only'; "
                     "CREATE ROLE sanctity_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD 'ephemeral_test_only'; "
                     "REVOKE CREATE,TEMPORARY ON DATABASE sanctity_test FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM PUBLIC; "
                     "GRANT CREATE ON DATABASE sanctity_test TO sanctity_migrator;"])
            env["SANCTITY_DB_USER"] = "sanctity_migrator"
            service_env = dict(env, SANCTITY_DB_USER="sanctity_runtime", SANCTITY_DB_PASSWORD_FILE=str(runtime_file))

            def rejected(overrides, unset=()):
                settings = dict(service_env)
                for key in unset:
                    settings.pop(key, None)
                settings.update(overrides)
                failed = subprocess.run([str(launch), "serve"], cwd=runtime, env=settings,
                                        capture_output=True, text=True, timeout=25)
                combined = failed.stdout + failed.stderr
                check(failed.returncode != 0, "invalid service settings were accepted")
                check(all(value not in combined for value in (password_canary, runtime_password, unsafe_canary, str(runtime))),
                      "configuration or exception diagnostics leaked protected values")
                check("Exception" not in combined and "at sanctity." not in combined, "raw startup exception escaped")

            rejected({}, ("SANCTITY_DB_PASSWORD_FILE",))
            rejected({"SANCTITY_DB_PASSWORD": password_canary})
            rejected({"SANCTITY_JDBC_URL": "jdbc:postgresql://127.0.0.1:" + pg_port + "/" + unsafe_canary})
            rejected({"SANCTITY_JDBC_URL": "jdbc:postgresql://127.0.0.1/test?password=" + unsafe_canary})
            rejected({"SANCTITY_HTTP_PORT": "65536"})
            rejected({"SANCTITY_PUBLIC_ORIGIN": "http://" + unsafe_canary + ".invalid"})
            rejected({"SANCTITY_STORAGE": str(runtime / unsafe_canary)})
            os.chmod(storage, 0o500)
            rejected({})
            os.chmod(storage, 0o700)
            os.chmod(runtime_file, 0o644)
            rejected({})
            os.chmod(runtime_file, 0o600)
            alias = runtime / "alias"
            alias.symlink_to(storage, target_is_directory=True)
            rejected({"SANCTITY_STORAGE": str(alias)})
            checks.append("invalid database/secret/origin/port/storage settings fail closed with canary-free diagnostics")
            cli("migrate", "--runtime-role", "sanctity_runtime")
            cli("migrate")
            checks.append("packaged SQL migrations applied and repeated successfully")
            rejected({"SANCTITY_DB_USER": "sanctity_migrator", "SANCTITY_DB_PASSWORD_FILE": str(password_file)})
            for arguments in (("migrate",), ("repository", "create", str(uuid.uuid4()), str(uuid.uuid4())),
                              ("repository", "issue-credential", str(uuid.uuid4()), "60", "forbidden", str(runtime / "forbidden-token")),
                              ("recovery", "capture-policy", str(runtime / "forbidden-policy")),
                              ("migrate", "--organization-preview", str(uuid.uuid4()), str(runtime / "forbidden-preview"))):
                attempt = subprocess.run([str(launch), *arguments], cwd=runtime, env=service_env,
                                         capture_output=True, text=True, timeout=25)
                check(attempt.returncode != 0, "runtime login reached a trusted operator command")
                check(all(s not in attempt.stdout + attempt.stderr for s in (password_canary, runtime_password, unsafe_canary)),
                      "privilege denial leaked configuration")
            check(not (runtime / "forbidden-token").exists() and not (runtime / "forbidden-policy").exists() and not (runtime / "forbidden-preview").exists(),
                  "privileged command left an unauthorized output")
            checks.append("runtime cannot migrate/bootstrap/issue credentials/capture policy; privileged login cannot serve")
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
                service_env["SANCTITY_HTTP_PORT"] = env["SANCTITY_HTTP_PORT"]
            rejected({})  # Same free-port configuration serves only after bootstrap.
            legacy_policy = runtime / "legacy-policy.json"
            cli("recovery", "capture-policy", str(legacy_policy))
            operator = str(uuid.uuid4())
            preview_file = runtime / "organization-preview.json"
            preview_output = cli("migrate", "--organization-preview", operator, str(preview_file))
            preview_digest = preview_output.strip().split()[-1]
            check(len(preview_digest) == 64 and preview_file.stat().st_mode & 0o777 == 0o600,
                  "preview must have a digest and private permissions")
            cli("migrate", "--organization-bootstrap", operator, preview_digest)
            cli("migrate", "--organization-bootstrap", operator, preview_digest)
            checks.append("unbootstrapped startup denied; private reviewed organization preview and idempotent bootstrap")
            server_log = (output / "service.log").open("w")
            server = subprocess.Popen([str(launch), "serve"], cwd=runtime, env=service_env, stdout=server_log, stderr=subprocess.STDOUT)
            base = "http://127.0.0.1:" + env["SANCTITY_HTTP_PORT"]

            def request(path, token=None, extra_headers=None):
                headers = {"Authorization": "Bearer " + token} if token else {}
                headers.update(extra_headers or {})
                req = urllib.request.Request(base + path, headers=headers)
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
            rejected({})  # The bound port must fail, without echoing driver/configuration context.
            check(request("/", extra_headers={"Host": unsafe_canary + ".invalid", "X-Forwarded-Host": "127.0.0.1"})[0] == 400,
                  "host bypass via proxy headers")
            check(request("/", extra_headers={"Origin": "https://" + unsafe_canary + ".invalid"})[0] == 403,
                  "cross-origin request accepted")
            check(request("/", extra_headers={"Forwarded": unsafe_canary, "X-Real-IP": unsafe_canary})[0] == 200,
                  "untrusted forwarding metadata affected local request")
            checks.append("live port collision and Host/Origin checks resist forwarded-header substitution")
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
            attempt = subprocess.run([str(launch), "recovery", "capture-policy", str(policy)],
                                     cwd=runtime, env=env, capture_output=True, timeout=30)
            check(attempt.returncode != 0 and not policy.exists(), "legacy recovery accepted organization state")
            cli("repository", "revoke-credential", credential["credential"])
            check(request("/v1/me", secret)[0] == 401, "revoked credential remained usable")
            checks.append("legacy policy capture refused after organization bootstrap; live credential revocation")
            # A real TERM and KILL of the packaged process must preserve accepted state
            # and live revocation. Stream drain/timeout is separately exercised by service tests.
            for force in (False, True):
                started_stop = time.monotonic()
                server.kill() if force else server.terminate()
                server.wait(timeout=15)
                check(time.monotonic() - started_stop < 15, "service did not stop within configured bound")
                server = subprocess.Popen([str(launch), "serve"], cwd=runtime, env=service_env,
                                          stdout=server_log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 30
                while True:
                    check(server.poll() is None, "service exited during restart")
                    try:
                        if request("/")[0] == 200:
                            break
                    except (urllib.error.URLError, ConnectionError):
                        pass
                    check(time.monotonic() < deadline, "restart timeout")
                    time.sleep(0.2)
                check(request("/v1/me", secret)[0] == 401, "restart restored revoked access")
                replacement_file = runtime / ("credential-restart-" + str(force) + ".json")
                cli("repository", "issue-credential", principal, "600", "restart", str(replacement_file))
                replacement = json.loads(replacement_file.read_text())["token"]
                status, restarted, _ = request("/v1/datasets/" + dataset, replacement)
                check(status == 200 and json.loads(restarted)["head"] == release, "accepted identity changed on restart")
                status, data, _ = request("/v1/datasets/" + dataset + "/releases/" + release + "/file?path=dataset_description.json", replacement)
                check(status == 200 and data == description, "accepted bytes changed on restart")
            checks.append("packaged SIGTERM/SIGKILL restart preserves exact accepted release/bytes and credential revocation")
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
            # Assert against raw output. Redacting it here would hide a service defect.
            log_clean = all(value not in log.read_text() for value in (secret or unsafe_canary, password_canary, runtime_password, unsafe_canary))
            if not log_clean:
                receipt["status"] = "failed"
        if container:
            subprocess.run(["docker", "rm", "-fv", container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    check(log_clean, "raw service log contains a credential or proxy canary")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
