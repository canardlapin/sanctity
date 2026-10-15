#!/usr/bin/env python3
"""Freeze old state once, then compare disposable restores without rewriting the oracle.

No live database URL is accepted. All SQL is restricted to a newly created labelled
container. Synthetic secrets/dumps stay in the explicitly selected private fixture.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

PG = "postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24"
BASELINE = "2ae3efa92d344520dc9abae1ce1d6c879998a187cebe1897ce096b3ced6b432e"
PASSWORD = "disposable_compatibility_only"
IMMUTABLE = {
    "release": "id,dataset_id,parent,draft_id,candidate_id,accepted_by,accepted_at",
    "candidate": "id,draft_id,dataset_id,base,generation,manifest_id,interpretation_id,assessment_id,activity",
    "manifest": "id,payload,entry_count",
    "interpretation": "id,manifest_id,payload,artifact_count,field_count",
    "assessment": "id,manifest_id,interpretation_id,admissible,payload",
    "acceptance_receipt": "dataset_id,principal,idempotency_key,candidate_id,release_id",
    "audit": "sequence,dataset_id,release_id,actor,action,recorded_at",
    "outbox": "sequence,dataset_id,release_id,event_type,payload,recorded_at,delivered_at",
    "access_grant": "id,dataset_id,recipient,release_id,actions,revoked_at",
    "policy_audit": "sequence,dataset_id,actor,grant_id,action,recorded_at",
    "api_credential": "id,principal_id,token_digest,label,expires_at,revoked_at",
    "credential_audit": "sequence,credential_id,action,recorded_at",
    "import_operation": "id,dataset_id,owner,idempotency_key,source,inventory,candidate_id,receipt,lease_token,lease_until,created_at",
    "acquired_file": "operation_id,path,payload",
}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def run(args, **kwargs):
    result = subprocess.run(args, capture_output=True, timeout=90, **kwargs)
    # Do not echo arguments/environment/output on failure: they may include fixture secrets.
    detail = result.stderr.decode(errors="replace").replace(PASSWORD, "[fixture secret]").replace("disposable_runtime_only", "[fixture secret]")
    require(result.returncode == 0, "fixture subprocess failed: " + Path(args[0]).name + ": " + detail[:1500])
    return result.stdout


class Fixture:
    def __init__(self, artifact, root, log):
        self.artifact, self.root, self.log = artifact, root, log
        self.container = self.server = None
        self.logfile = log.open("wb")
        self.storage = root / "blobs"
        self.storage.mkdir(mode=0o700)
        with zipfile.ZipFile(artifact) as z:
            for n in z.namelist():
                require(not n.startswith("/") and ".." not in Path(n).parts, "unsafe package path")
            z.extractall(root / "package")
        self.launch = next((root / "package").glob("*/bin/sanctity"))
        self.launch.chmod(0o755)
        self.env = {"PATH": os.environ["PATH"], "HOME": str(root), "LANG": "C.UTF-8",
                    "SANCTITY_DB_USER": "compat", "SANCTITY_DB_SCHEMA": "sanctity",
                    "SANCTITY_DB_PASSWORD": PASSWORD, "SANCTITY_STORAGE": str(self.storage)}

    def __enter__(self):
        self.container = run(["docker", "run", "-d", "--label", "io.sanctity.purpose=upgrade-fixture",
                              "-e", "POSTGRES_USER=compat", "-e", "POSTGRES_PASSWORD=" + PASSWORD,
                              "-e", "POSTGRES_DB=compat", "-p", "127.0.0.1::5432", PG]).decode().strip()
        deadline = time.monotonic() + 60
        while subprocess.run(["docker", "exec", self.container, "pg_isready", "-h", "127.0.0.1", "-U", "compat"],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
            require(time.monotonic() < deadline, "database startup timeout")
            time.sleep(0.2)
        port = run(["docker", "port", self.container, "5432/tcp"]).decode().strip().rsplit(":", 1)[1]
        self.env["SANCTITY_JDBC_URL"] = "jdbc:postgresql://127.0.0.1:" + port + "/compat"
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            self.env["SANCTITY_HTTP_PORT"] = str(s.getsockname()[1])
        self.base = "http://127.0.0.1:" + self.env["SANCTITY_HTTP_PORT"]
        return self

    def __exit__(self, *args):
        self.stop()
        self.logfile.close()
        if self.container:
            subprocess.run(["docker", "rm", "-fv", self.container], capture_output=True, timeout=30)

    def sql(self, source):
        return run(["docker", "exec", "-i", self.container, "psql", "-X", "-qAt", "-U", "compat", "-d", "compat",
                    "-v", "ON_ERROR_STOP=1"], input=("SET search_path TO sanctity,pg_catalog;\n" + source).encode()).decode().strip()

    def rows(self, source):
        return json.loads(self.sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb) FROM (" + source + ") t;"))

    def cli(self, *args, fail=False):
        p = subprocess.run([str(self.launch), *args], cwd=self.root, env=self.env, capture_output=True, timeout=90)
        require((p.returncode != 0) if fail else (p.returncode == 0), "CLI outcome differs: " + args[0])
        if fail:
            return None
        text = p.stdout.decode()
        return json.loads(text[text.index("{"):]) if "{" in text else None

    def issue(self, principal, label):
        path = self.root / (str(uuid.uuid4()) + ".json")
        self.cli("repository", "issue-credential", principal, "2592000", label, str(path))
        return json.loads(path.read_text())

    def start(self, modern):
        env = dict(self.env)
        if modern:
            self.sql("CREATE ROLE compat_runtime LOGIN PASSWORD 'disposable_runtime_only'; "
                     "REVOKE CREATE,TEMPORARY ON DATABASE compat FROM PUBLIC; REVOKE CREATE ON SCHEMA public FROM PUBLIC;")
            self.cli("migrate", "--runtime-role", "compat_runtime")
            env.update(SANCTITY_DB_USER="compat_runtime", SANCTITY_DB_PASSWORD="disposable_runtime_only")
        self.server = subprocess.Popen([str(self.launch), "serve"], cwd=self.root, env=env,
                                       stdout=self.logfile, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 30
        while True:
            require(self.server.poll() is None, "server stopped before readiness")
            try:
                if self.http("/")[0] == 200:
                    break
            except (urllib.error.URLError, ConnectionError):
                pass
            require(time.monotonic() < deadline, "server readiness timeout")
            time.sleep(0.2)

    def stop(self):
        if self.server:
            self.server.terminate()
            try:
                self.server.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.server.kill()
                self.server.wait(timeout=10)
            self.server = None

    def http(self, path, token=None, body=None, method=None, key=None):
        headers = {"Authorization": "Bearer " + token} if token else {}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(self.base + path, data=canonical(body) if body is not None else None,
                                         headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=10) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def json(self, path, token, body=None, method=None, key=None, status=200):
        code, data = self.http(path, token, body, method, key)
        require(code == status, "HTTP outcome differs: " + path)
        return json.loads(data) if data else None

    def dump(self, path):
        path.write_bytes(run(["docker", "exec", self.container, "pg_dump", "-U", "compat", "-d", "compat", "-Fc"]))

    def restore(self, path):
        run(["docker", "exec", "-i", self.container, "pg_restore", "-U", "compat", "-d", "compat", "--exit-on-error"], input=path.read_bytes())


def historical(f):
    return {name: digest(canonical(f.rows("SELECT " + columns + " FROM " + name))) for name, columns in IMMUTABLE.items()}


def freeze(f, fixture):
    f.cli("migrate")
    principals = {name: str(uuid.uuid4()) for name in ("owner", "fixed", "metadata", "revoked")}
    dataset = f.cli("repository", "create", principals["owner"], str(uuid.uuid4()))["dataset"]
    source = fixture / "source"
    source.mkdir(mode=0o700)
    old = b'{"Name":"Historical fixture one","BIDSVersion":"1.10.0","DatasetType":"raw"}\n'
    new = b'{"Name":"Historical fixture two","BIDSVersion":"1.10.0","DatasetType":"raw"}\n'
    readme = b'Synthetic historical corpus; no human data.\n'
    for name, data in {"dataset_description.json": old, "README": readme}.items():
        (source / name).write_bytes(data)
    first = f.cli("repository", "import", principals["owner"], dataset, str(source), str(f.storage), "historical-import")
    tokens = {"owner": f.issue(principals["owner"], "baseline-owner")}
    f.start(False)
    root = "/v1/datasets/" + dataset
    preview = f.json(root + "/releases/" + first["release"] + "/corrections", tokens["owner"]["token"],
                     {"path": "dataset_description.json", "text": new.decode(), "reason": "Frozen second interpretation"}, status=201)
    accepted = f.json("/v1/candidates/" + preview["candidate"]["id"] + "/accept", tokens["owner"]["token"],
                      method="POST", key="historical-correction")
    second = accepted["ref"]["release"]
    grants = {}
    for name, release, actions in (("fixed", first["release"], ["ReadMetadata", "ReadContent"]),
                                   ("metadata", None, ["ReadMetadata"]), ("revoked", None, ["ReadMetadata", "ReadContent"])):
        grants[name] = f.json(root + "/grants", tokens["owner"]["token"],
                              {"recipient": principals[name], "release": release, "actions": actions}, status=201)
        tokens[name] = f.issue(principals[name], "baseline-" + name)
    f.json(root + "/grants/" + grants["revoked"]["id"], tokens["owner"]["token"], method="DELETE", status=204)
    revoked_token = f.issue(principals["owner"], "baseline-revoked")
    f.cli("repository", "revoke-credential", revoked_token["credential"])
    releases = []
    for release, description in ((first["release"], old), (second, new)):
        representation = f.json(root + "/releases/" + release, tokens["owner"]["token"])
        exports = {name: {"sha256": digest(data), "size": len(data)} for name, data in
                   {"dataset_description.json": description, "README": readme}.items()}
        releases.append({"id": release, "representation": representation, "files": exports,
                         "exportManifestSha256": digest(canonical(exports))})
    # Create genuinely interrupted work using the old binary. The failpoint holds the
    # candidate transaction after durable acquisition; it is removed before freezing.
    pending_dataset = f.cli("repository", "create", principals["owner"], str(uuid.uuid4()))["dataset"]
    f.sql("CREATE FUNCTION compat_pause_candidate() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
          "PERFORM pg_sleep(60); RETURN NEW; END $$; CREATE TRIGGER compat_pause BEFORE INSERT ON candidate "
          "FOR EACH ROW EXECUTE FUNCTION compat_pause_candidate();")
    pending = subprocess.Popen([str(f.launch), "repository", "import", principals["owner"], pending_dataset,
                                str(source), str(f.storage), "historical-pending"], cwd=f.root, env=f.env,
                               stdout=f.logfile, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 30
        while True:
            count = int(f.sql("SELECT count(*) FROM acquired_file a JOIN import_operation o ON o.id=a.operation_id "
                              "WHERE o.idempotency_key='historical-pending';"))
            if count == 2:
                break
            require(pending.poll() is None and time.monotonic() < deadline, "pending acquisition not reached")
            time.sleep(0.2)
        pending.kill()
        pending.wait(timeout=10)
        f.sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='compat' AND pid<>pg_backend_pid() "
              "AND query ILIKE '%INSERT INTO candidate%';")
    finally:
        if pending.poll() is None:
            pending.kill()
            pending.wait(timeout=10)
        f.sql("DROP TRIGGER compat_pause ON candidate; DROP FUNCTION compat_pause_candidate();")
    pending_row = f.rows("SELECT id,dataset_id,candidate_id,receipt,lease_token,lease_until FROM import_operation WHERE idempotency_key='historical-pending'")[0]
    require(pending_row["candidate_id"] is None and pending_row["receipt"] is None, "interrupted import falsely completed")
    oracle = {"version": 1, "baselineSha256": BASELINE, "principals": principals, "dataset": dataset,
              "head": second, "source": str(source), "releases": releases, "grants": grants,
              "completedImport": first, "pendingImport": pending_row,
              "pendingFiles": releases[0]["files"], "revokedCredential": revoked_token["credential"],
              "decisions": [["fixed", 0, "release", 200], ["fixed", 0, "file", 200],
                            ["fixed", 1, "release", 404], ["metadata", 0, "release", 200],
                            ["metadata", 1, "release", 200], ["metadata", 1, "file", 404],
                            ["revoked", 0, "release", 404], ["revoked", 1, "file", 404]],
              "schema": f.rows("SELECT version,checksum FROM schema_migration ORDER BY version"),
              "historical": historical(f), "pendingOutbox": f.rows("SELECT sequence,release_id,event_type FROM outbox WHERE delivered_at IS NULL")}
    for name, index, surface, expected in oracle["decisions"]:
        path = root + "/releases/" + releases[index]["id"]
        if surface == "file":
            path += "/file?path=dataset_description.json"
        require(f.http(path, tokens[name]["token"])[0] == expected, "old binary disagrees with predeclared permission oracle")
    f.stop()
    f.dump(fixture / "catalog.dump")
    shutil.copytree(f.storage, fixture / "blobs")
    write_json(fixture / "secrets.json", {"revoked": revoked_token})
    (fixture / "secrets.json").chmod(0o600)
    write_json(fixture / "oracle.json", oracle)
    files = {p.relative_to(fixture).as_posix(): digest(p.read_bytes()) for p in fixture.rglob("*") if p.is_file()}
    write_json(fixture / "manifest.json", {"schemaVersion": 1, "files": files, "postgresImage": PG,
                                          "baselineSha256": BASELINE, "immutable": "freeze refuses an existing output directory"})
    return {"frozen": True, "releases": 2, "pendingAcquiredFiles": 2, "historicalTables": len(IMMUTABLE)}


def verify(f, fixture, modern, organization=False):
    manifest = json.loads((fixture / "manifest.json").read_text())
    for name, expected in manifest["files"].items():
        require(digest((fixture / name).read_bytes()) == expected, "frozen fixture changed: " + name)
    oracle = json.loads((fixture / "oracle.json").read_text())
    f.restore(fixture / "catalog.dump")
    shutil.copytree(fixture / "blobs", f.storage, dirs_exist_ok=True)
    if organization:
        # Pause after V008's first ALTER has executed inside its transaction, then
        # terminate that backend. This is a real populated V007 -> V008 rollback.
        f.sql("CREATE FUNCTION compat_pause_upgrade() RETURNS event_trigger LANGUAGE plpgsql AS $$ "
              "BEGIN IF TG_TAG='ALTER TABLE' THEN PERFORM pg_sleep(60); END IF; END $$; "
              "CREATE EVENT TRIGGER compat_pause_upgrade ON ddl_command_end EXECUTE FUNCTION compat_pause_upgrade()")
        process = subprocess.Popen([str(f.launch), "migrate"], cwd=f.root, env=f.env,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + 30
            while True:
                backends = f.rows("SELECT pid FROM pg_stat_activity WHERE datname=current_database() "
                                  "AND pid<>pg_backend_pid() AND wait_event='PgSleep' "
                                  "AND query LIKE 'ALTER TABLE principal%'")
                if backends:
                    require(len(backends) == 1, "ambiguous migration backend")
                    f.sql("SELECT pg_terminate_backend(" + str(int(backends[0]["pid"])) + ")")
                    break
                require(process.poll() is None and time.monotonic() < deadline,
                        "V008 migration did not reach interrupted DDL boundary")
                time.sleep(0.1)
            require(process.wait(timeout=15) != 0, "terminated migration reported success")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=15)
            f.sql("DROP EVENT TRIGGER compat_pause_upgrade; DROP FUNCTION compat_pause_upgrade()")
        require(f.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema='sanctity' "
                      "AND table_name='principal' AND column_name='active'") == "0", "partial V008 column survived rollback")
        require(f.sql("SELECT count(*) FROM schema_migration WHERE version>7") == "0", "partial migration was recorded")
        require(historical(f) == oracle["historical"], "interrupted migration changed historical state")
    f.cli("migrate")
    if organization:
        operator = str(uuid.uuid4())
        preview = f.root / "organization-preview.json"
        f.cli("migrate", "--organization-preview", operator, str(preview))
        report = json.loads(preview.read_text())
        preview_digest = digest(canonical(report))
        f.cli("migrate", "--organization-bootstrap", operator, preview_digest)
        f.cli("migrate", "--organization-bootstrap", operator, preview_digest)
        require(f.sql("SELECT count(*) FROM organization_event") == "1", "bootstrap retry duplicated genesis")
        require(f.sql("SELECT count(*) FROM dataset d LEFT JOIN organization_placement p ON p.dataset_id=d.id WHERE p.policy IS DISTINCT FROM 'ExplicitOnly'") == "0", "legacy placement widened access")
        require(f.sql("SELECT count(*) FROM dataset d WHERE NOT EXISTS(SELECT 1 FROM organization_stewardship s WHERE s.dataset_id=d.id AND s.principal_id=d.curator AND s.status='Active')") == "0", "legacy curator lacks explicit stewardship")
        f.cli("recovery", "capture-policy", str(f.root / "forbidden-policy.json"), fail=True)
        old_artifact = fixture.parent / "sanctity-0.1.0-SNAPSHOT.zip"
        require(digest(old_artifact.read_bytes()) == BASELINE, "retained old binary changed")
        with zipfile.ZipFile(old_artifact) as z:
            require(all(not n.startswith("/") and ".." not in Path(n).parts for n in z.namelist()), "unsafe old artifact")
            z.extractall(f.root / "old-package")
        old_launch = next((f.root / "old-package").glob("*/bin/sanctity"))
        old_launch.chmod(0o755)
        old_attempt = subprocess.run([str(old_launch), "migrate"], cwd=f.root, env=f.env,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
        require(old_attempt.returncode != 0, "old binary accepted V008 catalog")
    require(historical(f) == oracle["historical"], "migration rewrote historical payload/state")
    require(f.rows("SELECT version,checksum FROM schema_migration WHERE version<=7 ORDER BY version") == oracle["schema"], "baseline migration history changed")
    require(f.rows("SELECT sequence,release_id,event_type FROM outbox WHERE delivered_at IS NULL") == oracle["pendingOutbox"], "pending outbox changed")
    # Fresh credentials avoid a fixture whose positive assertions silently expire.
    # The historical revoked row/digest is compared before any new credential writes.
    tokens = {n: f.issue(p, "verification-" + n)["token"] for n, p in oracle["principals"].items()}
    f.start(modern)
    root = "/v1/datasets/" + oracle["dataset"]
    require(f.json(root, tokens["owner"])["head"] == oracle["head"], "historical head changed")
    for release in oracle["releases"]:
        path = root + "/releases/" + release["id"]
        require(f.json(path, tokens["owner"]) == release["representation"], "historical interpretation/representation changed")
        for name, wanted in release["files"].items():
            status, data = f.http(path + "/file?path=" + urllib.parse.quote(name), tokens["owner"])
            require(status == 200 and digest(data) == wanted["sha256"] and len(data) == wanted["size"], "historical export differs")
    for principal, index, surface, expected in oracle["decisions"]:
        path = root + "/releases/" + oracle["releases"][index]["id"]
        if surface == "file":
            path += "/file?path=dataset_description.json"
        observed = f.http(path, tokens[principal])[0]
        require(observed == expected, "current allow/deny decision changed: " + str((principal, index, surface, expected, observed)))
    secret = json.loads((fixture / "secrets.json").read_text())["revoked"]["token"]
    require(f.http("/v1/me", secret)[0] == 401, "revoked credential accepted")
    replay = f.cli("repository", "import", oracle["principals"]["owner"], oracle["dataset"], oracle["source"], str(f.storage), "historical-import")
    require(replay == oracle["completedImport"], "completed import retry changed identity")
    require(f.json(root, tokens["owner"])["head"] == oracle["head"], "completed import retry moved newer head")
    # Wait for the real lease expiry; no clock/lease-row rewriting in the fixture.
    deadline = time.monotonic() + 70
    while f.sql("SELECT lease_until>clock_timestamp() FROM import_operation WHERE idempotency_key='historical-pending'") == "t":
        require(time.monotonic() < deadline, "pending lease did not expire")
        time.sleep(0.5)
    pending = f.cli("repository", "import", oracle["principals"]["owner"], oracle["pendingImport"]["dataset_id"], oracle["source"], str(f.storage), "historical-pending")
    require(pending["operation"] == oracle["pendingImport"]["id"], "pending retry changed operation identity")
    require(f.cli("repository", "import", oracle["principals"]["owner"], pending["dataset"], oracle["source"], str(f.storage), "historical-pending") == pending, "pending retry duplicated acceptance")
    for name, wanted in oracle["pendingFiles"].items():
        status, data = f.http("/v1/datasets/" + pending["dataset"] + "/releases/" + pending["release"] + "/file?path=" + urllib.parse.quote(name), tokens["owner"])
        require(status == 200 and digest(data) == wanted["sha256"], "resumed work changed bytes")
    require(int(f.sql("SELECT count(*) FROM acceptance_receipt WHERE idempotency_key='historical-pending'")) == 1, "pending operation not exactly once")
    f.stop()
    checksum = next(row["checksum"] for row in oracle["schema"] if row["version"] == 1)
    f.sql("UPDATE schema_migration SET checksum=repeat('0',64) WHERE version=1")
    f.cli("migrate", fail=True)
    require(f.sql("SELECT checksum FROM schema_migration WHERE version=1") == "0" * 64, "drift silently repaired")
    f.sql("UPDATE schema_migration SET checksum='" + checksum + "' WHERE version=1; INSERT INTO schema_migration(version,checksum) VALUES(999,repeat('f',64))")
    f.cli("migrate", fail=True)
    require(f.sql("SELECT count(*) FROM schema_migration WHERE version=999") == "1", "downgrade sentinel silently removed")
    return {"historicalTablesUnchanged": len(IMMUTABLE), "historicalReleasesAndInterpretations": 2,
            "exactHistoricalFiles": 4, "currentGrantDecisions": len(oracle["decisions"]),
            "revokedCredential": "denied and historical row unchanged", "completedRetry": "same identity; newer head preserved",
            "interruptedImport": "same operation; exact bytes; one acceptance across retries",
            "driftAndNewerSchema": "refused without repair", "runtimeProfile": "separate login" if modern else "legacy operator login",
            "organizationMigrationInterrupted": organization, "oldBinaryRefusesV008": organization,
            "organizationBootstrap": "explicit reviewed mapping; same genesis on retry; private placement; legacy recovery fenced" if organization else "not requested"}


def main():
    os.umask(0o077)
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("mode", choices=["freeze", "verify"])
    p.add_argument("--artifact", type=Path, required=True)
    p.add_argument("--fixture", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--organization", action="store_true", help="explicit reviewed V008 organization bootstrap before serving")
    p.add_argument("--modern", action="store_true", help="candidate requires explicit runtime-role profile")
    args = p.parse_args()
    artifact, fixture, output = args.artifact.resolve(), args.fixture.resolve(), args.output.resolve()
    require(not output.exists(), "output must be new")
    output.mkdir(parents=True, mode=0o700)
    if args.mode == "freeze":
        require(not args.modern and digest(artifact.read_bytes()) == BASELINE, "freeze requires the pinned old binary")
        require(not fixture.exists(), "refusing to rewrite an existing oracle")
        fixture.mkdir(parents=True, mode=0o700)
    receipt = {"status": "failed", "mode": args.mode, "artifactSha256": digest(artifact.read_bytes()),
               "fixture": str(fixture), "postgresImage": PG, "scope": "synthetic local upgrade; no source-loss/physical recovery claim"}
    try:
        with tempfile.TemporaryDirectory(prefix="sanctity-compat-") as tmp:
            with Fixture(artifact, Path(tmp).resolve(), output / "service.log") as f:
                receipt["observed"] = freeze(f, fixture) if args.mode == "freeze" else verify(f, fixture, args.modern, args.organization)
                receipt["postgresVersion"] = run(["docker", "exec", f.container, "postgres", "--version"]).decode().strip()
        require(PASSWORD not in (output / "service.log").read_text(), "database secret leaked in runtime log")
        receipt["oracleSha256"] = digest((fixture / "oracle.json").read_bytes())
        receipt["fixtureManifestSha256"] = digest((fixture / "manifest.json").read_bytes())
        receipt["status"] = "passed"
    finally:
        write_json(output / "receipt.json", receipt)
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
