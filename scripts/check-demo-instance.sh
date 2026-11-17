#!/usr/bin/env bash
# Start a demo instance from clean, prove it with real requests, and remove it with its volumes.
# Uses its own Compose project, state directory and free port, so a running demo is not touched.
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
mkdir -p target
task_workspace="$(mktemp -d "$task_root/target/demo-check.XXXXXX")"
export SANCTITY_DEMO_PROJECT="sanctity-demo-check-$$"
export SANCTITY_DEMO_HOME="$task_workspace/instance"
SANCTITY_DEMO_PORT="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
export SANCTITY_DEMO_PORT
task_demo=(python3 scripts/demo-instance.py)
task_removed=""
cleanup() {
  if [[ -z "$task_removed" ]]; then
    "${task_demo[@]}" compose logs --no-color --no-log-prefix sanctity > "$task_workspace/service.log" 2>&1 || true
    "${task_demo[@]}" down --volumes >/dev/null 2>&1 || true
  fi
  # Generated passwords and credentials never outlive the check, whatever happened above.
  rm -rf "$SANCTITY_DEMO_HOME"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
printf 'Demo instance evidence: %s\n' "$task_workspace"

# `up` prints the demo credentials. Show its output without them, and require that it printed them.
task_up() {
  "${task_demo[@]}" up | python3 /dev/fd/3 "$SANCTITY_DEMO_HOME/instance.json" 3<<'PY'
import json, re, sys
printed = sys.stdin.read()
print(re.sub(r"sct_[A-Za-z0-9_-]+", "sct_[redacted]", printed), end="")
summary = json.load(open(sys.argv[1]))
assert summary["url"] in printed, "the console address was not printed"
assert "DEMO CREDENTIALS" in printed, "the credentials were not marked as demo credentials"
assert len(summary["people"]) >= 2 and all(p["token"] in printed for p in summary["people"]), "a credential was not printed"
PY
}

# Phase "first" proves the instance; later phases require that nothing changed since.
task_check() {
  python3 /dev/fd/3 "$task_root" "$task_workspace" "$1" 3<<'PY'
import hashlib, json, os, subprocess, sys, urllib.error, urllib.parse, urllib.request
from pathlib import Path

root, workspace, phase = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
home = Path(os.environ["SANCTITY_DEMO_HOME"])
summary = json.loads((home / "instance.json").read_text())
base = summary["url"].rstrip("/")
tokens = {p["key"]: p["token"] for p in summary["people"]}
passed = []


def call(path, token=None, host=None):
    headers = {"Authorization": "Bearer " + token} if token else {}
    if host:
        headers["Host"] = host
    try:
        with urllib.request.urlopen(urllib.request.Request(base + path, headers=headers), timeout=30) as response:
            return response.status, response.read(), dict(response.headers)
    except urllib.error.HTTPError as error:
        return error.code, error.read(), dict(error.headers)


def read(path, token):
    status, body, _ = call(path, token)
    assert status == 200, f"GET {path} returned HTTP {status}"
    return json.loads(body)


def compose(*arguments, env=None):
    return subprocess.run([sys.executable, str(root / "scripts/demo-instance.py"), "compose", *arguments],
                          env=os.environ | (env or {}), capture_output=True, text=True)


def ok(name, detail):
    passed.append({"check": name, "detail": detail})
    print(f"PASS {name}: {detail}")


def state():
    """What a repeated start or a restart must leave exactly as it was. Token digests, never tokens."""
    counts = compose("exec", "-T", "postgres", "psql", "-X", "-At", "-U", "postgres", "-d", "sanctity", "-c",
                     "SELECT (SELECT count(*) FROM sanctity.dataset), (SELECT count(*) FROM sanctity.release), "
                     "(SELECT count(*) FROM sanctity.organization_lab), (SELECT count(*) FROM sanctity.organization_project), "
                     "(SELECT count(*) FROM sanctity.principal), (SELECT count(*) FROM sanctity.access_grant), "
                     "(SELECT count(*) FROM sanctity.api_credential), (SELECT count(*) FROM sanctity.organization_event)").stdout.strip()
    assert counts.count("|") == 7, "the catalog could not be counted"
    return {"rows": counts, "labs": summary["labs"],
            "credentials": sorted(hashlib.sha256(t.encode()).hexdigest() for t in tokens.values())}


if phase != "first":
    before = json.loads((workspace / "state-first.json").read_text())
    now = state()
    assert now["rows"] == before["rows"], f"the catalog changed: {before['rows']} -> {now['rows']}"
    assert now["labs"] == before["labs"], "labs, projects, datasets or heads changed"
    assert now["credentials"] == before["credentials"], "credentials were reissued"
    for person in summary["people"]:
        assert read("/v1/me", person["token"])["principal"] == person["principal"]
    for lab in summary["labs"]:
        for dataset in lab["datasets"]:
            assert read("/v1/datasets/" + dataset["id"], tokens[lab["lead"]])["head"] == dataset["head"]
    print(f"PASS {phase}: every catalog row, identifier, accepted head and credential is unchanged")
    sys.exit(0)

# (a) The console is served by the real service on the printed address, under that address only.
status, page, headers = call("/")
assert status == 200 and b"<html" in page.lower(), "the console page was not served"
assert "script-src 'self'" in headers.get("Content-Security-Policy", ""), "the console lacks its content security policy"
assert call("/app.js")[0] == 200, "the console script was not served"
assert call("/", host="localhost:" + base.rsplit(":", 1)[1])[0] == 400, "another Host was accepted"
ok("console", f"GET {base}/ serves the console; another Host is refused")

# (b) Every printed credential authenticates as its own principal; no credential, no access.
assert call("/v1/me")[0] == 401, "an anonymous request was accepted"
for person in summary["people"]:
    assert read("/v1/me", person["token"])["principal"] == person["principal"], person["name"] + " is someone else"
assert len({p["lab"] for p in summary["people"]}) >= 2, "credentials must cover two labs"
ok("credentials", f"GET /v1/me identifies {len(summary['people'])} people in {len({p['lab'] for p in summary['people']})} labs; anonymous is 401")

# (c) The directories list two labs, each with its project and datasets, to the people who belong to them.
assert len(summary["labs"]) == 2, "the seed must hold two labs"
for lab in summary["labs"]:
    lead, project = tokens[lab["lead"]], lab["project"]["id"]
    labs = read("/v1/labs", lead)["items"]
    assert [(l["id"], l["name"]) for l in labs] == [(lab["id"], lab["name"])], f"lab directory of {lab['lead']}: {labs}"
    projects = read("/v1/projects?lab=" + lab["id"], lead)["items"]
    assert [(p["id"], p["name"]) for p in projects] == [(project, lab["project"]["name"])], f"projects of {lab['name']}: {projects}"
    listed = read(f"/v1/projects/{project}/datasets", lead)["items"]
    assert sorted((d["id"], d["label"]) for d in listed) == sorted((d["id"], d["label"]) for d in lab["datasets"]), f"datasets of {lab['name']}: {listed}"
    roster = read("/v1/labs/" + lab["id"], lead)["roster"]
    active = sorted(m["role"] for m in roster if m["status"] == "active")
    assert active == ["admin"] + ["member"] * len(lab["members"]), f"roster of {lab['name']}: {roster}"
    for member in lab["members"]:
        assert [l["id"] for l in read("/v1/labs", tokens[member])["items"]] == [lab["id"]], member + " does not see the lab"
        assert [d["id"] for d in read(f"/v1/projects/{project}/datasets", tokens[member])["items"]] == [lab["datasets"][0]["id"]], member + " sees other datasets"
assert len({lab["id"] for lab in summary["labs"]}) == 2 and len({lab["project"]["id"] for lab in summary["labs"]}) == 2
ok("directories", "; ".join(f"{lab['name']} > {lab['project']['name']} ({len(lab['datasets'])} datasets)" for lab in summary["labs"]))

# (d) Each seeded dataset has an accepted head release with a non-empty inventory and the fixture's exact bytes.
described, findings = [], []
for lab in summary["labs"]:
    lead = tokens[lab["lead"]]
    for dataset in lab["datasets"]:
        head = read("/v1/datasets/" + dataset["id"], lead)["head"]
        assert head and head == dataset["head"], dataset["label"] + " has no accepted head"
        release = read(f"/v1/datasets/{dataset['id']}/releases/{head}", lead)
        files = [e["path"] for e in release["entries"] if e["kind"] == "file"]
        fixture = root / "modules/app/src/test/resources" / dataset["fixture"]
        expected = sorted(p.relative_to(fixture).as_posix() for p in fixture.rglob("*") if p.is_file())
        assert files and sorted(files) == expected, dataset["label"] + " inventory differs from its fixture"
        assert release["assessment"]["admissible"] and release["acceptedAt"], dataset["label"] + " was not accepted"
        for path in ("dataset_description.json", files[-1]):
            status, content, headers = call(f"/v1/datasets/{dataset['id']}/releases/{head}/file?path=" + urllib.parse.quote(path), lead)
            digest = hashlib.sha256((fixture / path).read_bytes()).hexdigest()
            assert status == 200 and hashlib.sha256(content).hexdigest() == digest == headers["X-Sanctity-SHA256"], f"{dataset['label']}: {path} bytes differ"
        findings.append(len(release["assessment"]["findings"]))
        described.append(f"{dataset['label']}: {len(files)} files, {findings[-1]} findings")
assert sum(1 for n in findings if n > 0) >= 2, "the seed should show findings in at least two datasets"
ok("releases", "; ".join(described))

# (e) A person from one lab cannot read the other lab's private dataset, project or lab.
first, second = summary["labs"]
for person, other in ((first["lead"], second), (first["members"][0], second), (second["lead"], first), (second["members"][0], first)):
    private = other["datasets"][-1]
    for path in (f"/v1/datasets/{private['id']}", f"/v1/datasets/{private['id']}/releases/{private['head']}",
                 f"/v1/datasets/{private['id']}/releases/{private['head']}/file?path=dataset_description.json",
                 f"/v1/datasets/{private['id']}/placement", f"/v1/projects/{other['project']['id']}/datasets",
                 f"/v1/labs/{other['id']}"):
        status = call(path, tokens[person])[0]
        assert status == 404, f"{person} got HTTP {status} for {path}"
# A lab member is not a steward: the lab's second dataset stays closed to its own research assistant.
assert call("/v1/datasets/" + first["datasets"][1]["id"], tokens[first["members"][0]])[0] == 404
# The one cross-lab share is exactly one release: readable, without the dataset's current head.
shared = first["datasets"][0]
assert call(f"/v1/datasets/{shared['id']}/releases/{shared['head']}", tokens[second["lead"]])[0] == 200
assert call("/v1/datasets/" + shared["id"], tokens[second["lead"]])[0] == 404
ok("isolation", f"{first['lead']} and {first['members'][0]} are denied {second['name']}'s dataset, and the reverse; "
   f"{second['lead']} reads only the one shared release")

# The serving login is not the schema owner: it cannot run operator commands, and PostgreSQL is not published.
settings = dict(line.split("=", 1) for line in (home / "instance.env").read_text().splitlines())


def as_login(role, secret, *command):
    """An operator command under a chosen database login; the password travels in the environment."""
    return compose("run", "--rm", "-T", "--no-deps", "-e", "SANCTITY_DB_USER=" + role, "-e", "SANCTITY_DB_PASSWORD",
                   "operator", *command, env={"SANCTITY_DB_PASSWORD": settings[secret]})


# The same read-only operator command succeeds for the schema owner, so a refusal below is the role check
# and not a login that never reached the database.
needs = ("organization", "recovery-needs", summary["operator"])
assert as_login("sanctity_migrator", "SANCTITY_DEMO_MIGRATOR_PASSWORD", *needs).returncode == 0, "the schema owner was refused"
assert as_login("sanctity_runtime", "SANCTITY_DEMO_RUNTIME_PASSWORD", *needs).returncode != 0, "the runtime login ran an operator command"
assert as_login("sanctity_runtime", "SANCTITY_DEMO_RUNTIME_PASSWORD", "repository", "issue-credential",
                summary["people"][0]["principal"], "60", "refused", "/tmp/refused.json").returncode != 0, "the runtime login issued a credential"
roles = compose("exec", "-T", "postgres", "psql", "-X", "-At", "-U", "postgres", "-d", "sanctity", "-c",
                "SELECT rolname || ':' || (rolsuper OR rolcreatedb OR rolcreaterole)::text || ':' || "
                "(SELECT count(*) FROM pg_namespace n WHERE n.nspowner = r.oid AND n.nspname = 'sanctity')::text "
                "FROM pg_roles r WHERE rolname LIKE 'sanctity_%' ORDER BY 1").stdout.split()
assert roles == ["sanctity_migrator:false:1", "sanctity_runtime:false:0"], roles
ok("roles", "the runtime login owns nothing and is refused operator commands; a separate login owns the schema")

# Only the console is published, and only on the host's loopback interface.
def published(service):
    container = compose("ps", "-q", service).stdout.strip()
    return json.loads(subprocess.run(["docker", "inspect", "--format", "{{json .HostConfig.PortBindings}}", container],
                                     capture_output=True, text=True, check=True).stdout)
port = base.rsplit(":", 1)[1]
assert published("postgres") == {}, "PostgreSQL is published on the host"
assert published("sanctity") == {port + "/tcp": [{"HostIp": "127.0.0.1", "HostPort": port}]}, published("sanctity")
ok("exposure", f"the service is published on 127.0.0.1:{port} only; PostgreSQL is not published")

saved = state()
(workspace / "state-first.json").write_text(json.dumps(saved, indent=2) + "\n")
(workspace / "checks.json").write_text(json.dumps(passed, indent=2) + "\n")
print("Catalog rows (datasets|releases|labs|projects|principals|grants|credentials|events): " + saved["rows"])
PY
}

"${task_demo[@]}" down --volumes
task_up
task_check first

# Starting an instance that is already running changes nothing.
task_up >/dev/null
task_check repeat

# Stopping keeps the data; starting again serves the same accepted releases to the same credentials.
"${task_demo[@]}" down
test -z "$("${task_demo[@]}" compose ps -aq)"
test "$(docker volume ls -q --filter "label=com.docker.compose.project=$SANCTITY_DEMO_PROJECT" | wc -l | tr -d ' ')" = 2
task_up >/dev/null
task_check restart

# Explicit removal deletes the containers, both volumes and the local state.
"${task_demo[@]}" compose logs --no-color --no-log-prefix sanctity > "$task_workspace/service.log" 2>&1
"${task_demo[@]}" down --volumes
task_removed=1
test -z "$("${task_demo[@]}" compose ps -aq)"
test -z "$(docker volume ls -q --filter "label=com.docker.compose.project=$SANCTITY_DEMO_PROJECT")"
test ! -e "$SANCTITY_DEMO_HOME"
if grep -rq 'sct_' "$task_workspace"; then
  echo "A credential reached the retained evidence." >&2
  exit 1
fi
echo "PASS removal: no container, volume or local state remains"
printf 'Demo instance check passed. Evidence: %s\n' "$task_workspace"
