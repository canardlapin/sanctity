#!/usr/bin/env python3
"""Start, seed and remove a local Sanctity demonstration instance.

  python3 scripts/demo-instance.py up               start or update the instance; print its address and demo credentials
  python3 scripts/demo-instance.py down             stop and remove the containers; the data volumes are kept
  python3 scripts/demo-instance.py down --volumes   also delete the database, the stored files and the local state
  python3 scripts/demo-instance.py compose ps       any docker compose command, addressed to this instance

`up` is safe to repeat: every step reads the instance's current state and acts only where it differs.
Settings (environment): SANCTITY_DEMO_PORT (default 8080), SANCTITY_DEMO_PROJECT (Compose project,
default sanctity-demo), SANCTITY_DEMO_HOME (local state directory) and SANCTITY_DEMO_RELEASE (an
existing scripts/package-service.py output directory to run instead of building one).
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
PACKAGER = ROOT / "scripts/package-service.py"
FIXTURES = ROOT / "modules/app/src/test/resources"  # mounted read-only at /seed in the operator container
STORAGE = "/var/lib/sanctity/blobs"
MARKER = ".sanctity-demo-instance"
SECRETS = ("SANCTITY_DEMO_SUPERUSER_PASSWORD", "SANCTITY_DEMO_MIGRATOR_PASSWORD", "SANCTITY_DEMO_RUNTIME_PASSWORD")
CREDENTIAL_SECONDS = 30 * 24 * 3600  # the longest lifetime the operator command issues

# Identifiers are derived from fixed names so that a repeated `up` addresses the same records without
# remembering anything. They identify fictional people; only database passwords and tokens are secret.
NAMESPACE = uuid.UUID("5a9c7d1e-2b64-4f0a-9c3e-d01f5a6e7b80")


def ident(name):
    return str(uuid.uuid5(NAMESPACE, name))


OPERATOR = ident("principal/operator")
CUSTODIAN = ident("custodian/institution")
# The catalog stores no display name for a principal and the console shows identifiers, so these names
# exist only in this script's output and in credential labels.
PEOPLE = {
    "alice": ("Alice Tremblay", "lab lead"),
    "carol": ("Carol Nguyen", "research assistant"),
    "bob": ("Bob Okafor", "lab lead"),
    "dave": ("Dave Singh", "research assistant"),
}
READ = ["Discover", "ReadMetadata", "ReadContent"]
LABS = [
    {"name": "Cognitive Neuroimaging Lab",
     "description": "Fictional demonstration lab. Its datasets are synthetic software fixtures, not acquired MRI.",
     "lead": "alice",
     "project": {"name": "Cue reactivity fMRI study",
                 "description": "Demonstration project: a multi-echo task pilot and its fieldmap calibration."},
     "datasets": [
         {"label": "Cue task multi-echo BOLD pilot", "fixture": "mri-conformance/analysis-inputs/dataset"},
         {"label": "Fieldmap and resting-state calibration", "fixture": "mri-conformance/phase-pair/dataset"}],
     # Each member is a lab member and project contributor with an explicit share of the first dataset.
     "members": {"carol": READ + ["Propose"]}},
    {"name": "Memory and Perception Lab",
     "description": "Fictional demonstration lab. Its dataset is a CC0 excerpt of published behavioural responses.",
     "lead": "bob",
     "project": {"name": "Face recognition study",
                 "description": "Demonstration project: behavioural responses from a face recognition task."},
     "datasets": [
         {"label": "Face recognition behavioural responses",
          "fixture": "multimodal-conformance/face-behavioural/dataset"}],
     "members": {"dave": READ}},
]


class Failure(Exception):
    pass


def say(text):
    print("==> " + text, flush=True)


def run(argv, *, env=None, text_input=None, check=True, what=None):
    done = subprocess.run([str(a) for a in argv], env=env, input=text_input, capture_output=True, text=True)
    if check and done.returncode != 0:
        detail = "\n".join((done.stdout + done.stderr).strip().splitlines()[-25:])
        raise Failure((what or " ".join(str(a) for a in argv[:4])) + " failed:\n" + detail)
    return done


class Instance:
    def __init__(self):
        self.project = os.environ.get("SANCTITY_DEMO_PROJECT", "sanctity-demo")
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", self.project):
            raise Failure("SANCTITY_DEMO_PROJECT must be a lowercase Compose project name")
        home = os.environ.get("SANCTITY_DEMO_HOME")
        self.home = Path(home).resolve() if home else ROOT / "target/demo-instance" / self.project
        self.settings = {}
        file = self.home / "instance.env"
        if file.is_file():
            self.settings = dict(line.split("=", 1) for line in file.read_text().splitlines() if "=" in line)

    # ----- local state -----
    def prepare(self, image):
        """Create the private state directory and the generated settings Compose needs."""
        self.home.mkdir(parents=True, exist_ok=True)
        os.chmod(self.home, 0o700)
        marker = self.home / MARKER
        if not marker.exists():
            if any(self.home.iterdir()):
                raise Failure(f"{self.home} is not empty and is not a demo instance directory")
            marker.write_text(self.project + "\n")
        elif marker.read_text().strip() != self.project:
            raise Failure(f"{self.home} belongs to another demo instance")
        port = os.environ.get("SANCTITY_DEMO_PORT") or self.settings.get("SANCTITY_DEMO_PORT") or "8080"
        if not port.isdigit() or not 1024 <= int(port) <= 65535:
            raise Failure("SANCTITY_DEMO_PORT must be a port from 1024 to 65535")
        for name in SECRETS:
            self.settings.setdefault(name, secrets.token_hex(24))
        self.settings.update(SANCTITY_DEMO_IMAGE=image, SANCTITY_DEMO_PORT=port)
        file = self.home / "instance.env"
        descriptor = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w") as out:
            out.write("".join(f"{k}={v}\n" for k, v in sorted(self.settings.items())))
        (self.home / "credentials").mkdir(mode=0o700, exist_ok=True)

    @property
    def port(self):
        return int(self.settings["SANCTITY_DEMO_PORT"])

    @property
    def url(self):
        return f"http://127.0.0.1:{self.port}"

    # ----- containers -----
    def compose_argv(self, *args):
        return ["docker", "compose", "-p", self.project, "--project-directory", ROOT, "-f", ROOT / "compose.yaml", *args]

    def compose_env(self):
        # Removal and inspection need no real values, only syntactically complete configuration.
        placeholders = {name: "unset" for name in SECRETS} | {"SANCTITY_DEMO_IMAGE": "sanctity-demo:unset",
                                                              "SANCTITY_DEMO_PORT": "8080"}
        return os.environ | placeholders | self.settings

    def compose(self, *args, text_input=None, check=True, what=None):
        return run(self.compose_argv(*args), env=self.compose_env(), text_input=text_input, check=check, what=what)

    def sql(self, text):
        """Superuser SQL over the database container's local socket: role provisioning and state reads only."""
        return self.compose("exec", "-T", "postgres", "psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1",
                            "-U", "postgres", "-d", "sanctity", text_input=text, what="PostgreSQL statement").stdout.strip()

    def operator(self, *args, check=True):
        """One trusted operator command of the packaged distribution, with the schema owner's login."""
        return self.compose("run", "--rm", "-T", "--no-deps", "operator", *args, check=check,
                            what="sanctity " + " ".join(args[:2]))

    def operator_file(self, *args):
        """An operator command whose last argument is a new private file; returns (stdout, file text).

        The file exists only inside the one-shot container. Its text comes back on a pipe, never an argument.
        """
        script = ('set -eu; f=$(mktemp -u); /opt/sanctity/bin/sanctity "$@" "$f" >&2; printf "\\n@@file@@\\n" >&2; cat "$f"')
        done = self.compose("run", "--rm", "-T", "--no-deps", "--entrypoint", "sh", "operator", "-c", script, "sh", *args,
                            what="sanctity " + " ".join(args[:2]))
        return done.stderr.split("@@file@@")[0], done.stdout

    def service(self):
        """(image, health) of the serving container, or (None, None) when there is none."""
        found = self.compose("ps", "-q", "sanctity", check=False).stdout.split()
        if not found:
            return None, None
        state = run(["docker", "inspect", "--format", "{{.Config.Image}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}",
                     found[0]], check=False).stdout.split()
        return (state[0], state[2]) if len(state) == 3 and state[1] == "running" else (None, None)

    # ----- HTTP API -----
    def api(self, token, method, path, body=None, key=None):
        headers = {"Authorization": "Bearer " + token} if token else {}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(self.url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        try:
            return status, json.loads(raw)
        except ValueError:
            return status, None

    def get(self, token, path):
        status, body = self.api(token, "GET", path)
        if status != 200:
            raise Failure(f"GET {path} returned HTTP {status}")
        return body

    def listing(self, token, path):
        items, cursor = [], None
        while True:
            page = self.get(token, path + (("&" if "?" in path else "?") + "cursor=" + urllib.parse.quote(cursor) if cursor else ""))
            items += page["items"]
            cursor = page["next"]
            if not cursor:
                return items

    def change(self, token, what, path, body, expected):
        """One governed organization change. The key names the generations it was decided against, so a
        repeat after a lost response is the same request and a later, different change is a new one."""
        key = "demo-seed:" + what + ":" + ",".join(f"{k}={v}" for k, v in sorted(expected.items()))
        status, out = self.api(token, "POST", path, body | {"expected": expected}, hashlib.sha256(key.encode()).hexdigest())
        if status not in (200, 201):
            raise Failure(f"{what}: POST {path} returned HTTP {status} {out}")
        return out


# ---------- artifact and image ----------

def need(*tools):
    missing = [t for t in tools if shutil.which(t) is None]
    if missing:
        raise Failure("required on this computer: " + ", ".join(missing))


def release():
    """The packaged distribution to run: SANCTITY_DEMO_RELEASE, or one built from the current sources."""
    chosen = os.environ.get("SANCTITY_DEMO_RELEASE")
    if chosen:
        directory = Path(chosen).resolve()
    else:
        inventory = run([sys.executable, PACKAGER, "--source-inventory"]).stdout.strip()
        directory = ROOT / "target/demo-release" / inventory[:16]
        if not (directory / "build-receipt.json").is_file():
            need("java", "sbt")
            say("Building the service distribution with scripts/package-service.py (a few minutes)")
            directory.parent.mkdir(parents=True, exist_ok=True)
            shutil.rmtree(directory, ignore_errors=True)  # only ever a build this script started and did not finish
            log = directory.parent / "build.log"
            with log.open("w") as out:
                built = subprocess.run([sys.executable, str(PACKAGER), "--output", str(directory)], stdout=out, stderr=subprocess.STDOUT)
            if built.returncode != 0:
                raise Failure(f"the distribution build failed; see {log}:\n" + "\n".join(log.read_text().splitlines()[-25:]))
            for old in directory.parent.iterdir():  # earlier builds of other source states
                if old.is_dir() and old != directory:
                    shutil.rmtree(old, ignore_errors=True)
    archives = sorted(directory.glob("sanctity-*.zip"))
    if len(archives) != 1 or not Path(str(archives[0]) + ".sha256").is_file():
        raise Failure(f"{directory} is not a scripts/package-service.py output directory")
    recorded = Path(str(archives[0]) + ".sha256").read_text().split()[0]
    if hashlib.sha256(archives[0].read_bytes()).hexdigest() != recorded:
        raise Failure(f"{archives[0].name} does not match its recorded SHA-256")
    return directory, recorded


def image(directory, archive_sha):
    """An image of exactly this archive and Dockerfile; the tag changes when either does."""
    recipe = (ROOT / "Dockerfile").read_bytes()
    tag = "sanctity-demo:" + hashlib.sha256(archive_sha.encode() + recipe).hexdigest()[:12]
    if run(["docker", "image", "inspect", tag], check=False).returncode != 0:
        say("Building the container image " + tag)
        run(["docker", "build", "-f", ROOT / "Dockerfile", "-t", tag, "--label", "io.sanctity.artifact-sha256=" + archive_sha,
             directory], what="docker build")
    return tag


# ---------- database, migration and organization bootstrap ----------

def database(inst):
    say("Checking PostgreSQL and its two Sanctity roles")
    inst.compose("up", "-d", "--wait", "postgres", what="PostgreSQL start")
    roles = int(inst.sql("SELECT count(*) FROM pg_roles WHERE rolname IN ('sanctity_migrator','sanctity_runtime')"))
    if roles == 0:
        # The reviewed template of the distribution: a schema owner and an unprivileged runtime login.
        inst.sql((ROOT / "docs/deployment/database-roles.sql.example").read_text())
    elif roles != 2:
        raise Failure("the database has only one of the two Sanctity roles; remove the instance with down --volumes")
    # Applied on every start, so regenerated local settings never strand an existing database volume.
    inst.sql("".join(f"ALTER ROLE {role} PASSWORD '{inst.settings[name]}';" for role, name in
                     (("sanctity_migrator", SECRETS[1]), ("sanctity_runtime", SECRETS[2]))))


def migrate(inst, tag):
    current, health = inst.service()
    if current == tag and health == "healthy":
        return
    if current is not None:
        say("Stopping the service before migration")
        inst.compose("stop", "sanctity")
    say("Applying catalog migrations and the runtime role profile")
    inst.operator("migrate", "--runtime-role", "sanctity_runtime")


def bootstrap(inst):
    if inst.sql("SELECT initialized FROM sanctity.organization_policy WHERE singleton") == "t":
        return
    alice = ident("principal/alice")
    # A new installation recognizes no custodian and no principal. The operator's staging command
    # introduces both with one empty dataset, which the bootstrap places in a private project.
    if not inst.sql(f"SELECT id FROM sanctity.dataset WHERE curator='{alice}'"):
        say("Staging the first dataset for its curator")
        inst.operator("repository", "create", alice, CUSTODIAN)
    say("Bootstrapping the organization model")
    printed, preview = inst.operator_file("migrate", "--organization-preview", OPERATOR)
    digest = re.search(r"Organization preview digest: ([0-9a-f]{64})", printed)
    if not digest:
        raise Failure("the organization preview printed no digest")
    # An installation owner reviews this mapping before accepting it; the demo records it instead.
    (inst.home / "organization-preview.json").write_text(preview)
    inst.operator("migrate", "--organization-bootstrap", OPERATOR, digest.group(1))


def serve(inst, tag):
    current, health = inst.service()
    if current != tag or health != "healthy":
        say("Starting the Sanctity service")
    started = inst.compose("up", "-d", "--wait", "sanctity", check=False)
    if started.returncode != 0:
        output = started.stdout + started.stderr
        # Docker reports a taken host port in several wordings; a bind probe here would also trip on a
        # port Docker itself has not yet released after `down`.
        if any(text in output for text in ("address already in use", "port is already allocated", "Ports are not available")):
            raise Failure(f"port {inst.port} is in use; choose another with SANCTITY_DEMO_PORT=NNNN")
        logs = inst.compose("logs", "--no-log-prefix", "--tail", "20", "sanctity", check=False).stdout.strip()
        raise Failure("the service did not become healthy:\n" + "\n".join(output.strip().splitlines()[-10:]) + "\n" + logs)


# ---------- seed ----------

class Seed:
    def __init__(self, inst):
        self.inst = inst
        self.tokens = {}
        self.people = []

    def credential(self, key, principal=None, seconds=CREDENTIAL_SECONDS, keep=True):
        """A working bearer credential for a person; reuses the stored one while the service accepts it."""
        inst, principal = self.inst, principal or ident("principal/" + key)
        file = inst.home / "credentials" / (key + ".json")
        if keep and file.is_file():
            stored = json.loads(file.read_text())
            status, me = inst.api(stored["token"], "GET", "/v1/me")
            if status == 200 and me["principal"] == principal:
                return stored
        label = "Demo credential: " + PEOPLE[key][0] if key in PEOPLE else "Demo seeding; revoked after use"
        _, document = inst.operator_file("repository", "issue-credential", principal, str(seconds), label)
        issued = json.loads(document)
        if keep:
            descriptor = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(descriptor, "w") as out:
                out.write(document)
        return issued

    def person(self, key, lab):
        if key not in self.tokens:
            issued = self.credential(key)
            self.tokens[key] = issued["token"]
            self.people.append({"key": key, "name": PEOPLE[key][0], "role": PEOPLE[key][1], "lab": lab,
                                "principal": issued["principal"], "token": issued["token"], "expiresAt": issued["expiresAt"]})
        return self.tokens[key]

    def lab(self, spec, first):
        inst, lead = self.inst, ident("principal/" + spec["lead"])
        token = self.person(spec["lead"], spec["name"])
        labs = inst.listing(token, "/v1/labs")
        if not labs and first:
            self.hand_over(lead)
        elif not labs:
            say("Creating " + spec["name"])
            inst.operator("organization", "create-lab", OPERATOR, lead, spec["name"], "demo-seed:" + spec["name"])
        labs = labs or inst.listing(token, "/v1/labs")
        if len(labs) != 1:
            raise Failure(f"{PEOPLE[spec['lead']][0]} should belong to exactly one lab, not {len(labs)}")
        lab = inst.get(token, "/v1/labs/" + labs[0]["id"])
        ref = "lab:" + lab["id"]
        if (lab["name"], lab["description"]) != (spec["name"], spec["description"]):
            inst.change(token, "name lab", f"/v1/labs/{lab['id']}/rename",
                        {"name": spec["name"], "description": spec["description"]}, {ref: lab["generation"]})
            lab = inst.get(token, "/v1/labs/" + lab["id"])
        # The bootstrap made the operator this lab's administrator; its lead is one now, as in a created lab.
        for member in lab["roster"]:
            if member["principal"] == OPERATOR and member["status"] == "active":
                inst.change(token, "end operator membership", f"/v1/memberships/{member['id']}/end", {},
                            {"membership:" + member["id"]: member["generation"], ref: lab["generation"]})
        return lab["id"]

    def hand_over(self, lead):
        """The bootstrap lab has one administrator, the operator, who appoints the lab's lead."""
        inst = self.inst
        say("Appointing the first lab's lead")
        operator = self.credential("operator", OPERATOR, seconds=900, keep=False)
        try:
            labs = inst.listing(operator["token"], "/v1/labs")
            if len(labs) != 1:
                raise Failure("the operator should administer exactly the bootstrap lab")
            lab = inst.get(operator["token"], "/v1/labs/" + labs[0]["id"])
            inst.change(operator["token"], "appoint lab lead", f"/v1/labs/{lab['id']}/members",
                        {"principal": lead, "role": "admin"}, {"lab:" + lab["id"]: lab["generation"]})
        finally:
            inst.operator("repository", "revoke-credential", operator["credential"])

    def project(self, spec, lab):
        inst, token = self.inst, self.tokens[spec["lead"]]
        wanted = spec["project"]
        projects = inst.listing(token, "/v1/projects?lab=" + lab)
        if not projects:
            current = inst.get(token, "/v1/labs/" + lab)
            return inst.change(token, "create project", f"/v1/labs/{lab}/projects", wanted,
                               {"lab:" + lab: current["generation"]})["created"]["project"]
        if len(projects) != 1:
            raise Failure(f"{spec['name']} should hold one project, not {len(projects)}")
        project = inst.get(token, "/v1/projects/" + projects[0]["id"])
        if (project["name"], project["description"]) != (wanted["name"], wanted["description"]):
            inst.change(token, "name project", f"/v1/projects/{project['id']}/rename", wanted,
                        {"project:" + project["id"]: project["generation"]})
        return project["id"]

    def dataset(self, spec, project, wanted):
        inst, token = self.inst, self.tokens[spec["lead"]]
        if not (FIXTURES / wanted["fixture"] / "dataset_description.json").is_file():
            raise Failure("seed fixture is missing: " + wanted["fixture"])
        entries = inst.listing(token, f"/v1/projects/{project}/datasets")
        named = [e["id"] for e in entries if e["label"] == wanted["label"]]
        staged = [e["id"] for e in entries if e["label"] is None]
        if named:
            dataset = named[0]
        elif staged:  # the dataset the operator staged before bootstrap
            dataset = staged[0]
            placement = inst.get(token, f"/v1/datasets/{dataset}/placement")
            inst.change(token, "label dataset", f"/v1/datasets/{dataset}/rename", {"label": wanted["label"]},
                        {"placement:" + dataset: placement["generation"]})
        else:
            current = inst.get(token, "/v1/projects/" + project)
            dataset = inst.change(token, "create dataset " + wanted["label"], f"/v1/projects/{project}/datasets",
                                  {"custodian": CUSTODIAN, "label": wanted["label"]},
                                  {"project:" + project: current["generation"]})["created"]["dataset"]
        head = inst.get(token, "/v1/datasets/" + dataset)["head"]
        if head is None:
            say("Importing " + wanted["label"])
            # Trusted native intake: the operator reads a local directory as the dataset's steward. The key
            # makes a repeat after an interruption resume the same operation.
            inst.operator("repository", "import", ident("principal/" + spec["lead"]), dataset, "/seed/" + wanted["fixture"],
                          STORAGE, "demo-seed:import:" + dataset)
            head = inst.get(token, "/v1/datasets/" + dataset)["head"]
            if head is None:
                raise Failure("import published no release for " + wanted["label"])
        return {"id": dataset, "label": wanted["label"], "head": head, "fixture": wanted["fixture"]}

    def share(self, owner, dataset, recipient, actions, release=None):
        """An explicit share. It is also the only supported way to introduce a principal after bootstrap."""
        inst, token = self.inst, self.tokens[owner]
        grants = inst.get(token, f"/v1/datasets/{dataset}/grants")["grants"]
        if any(g["recipient"] == ident("principal/" + recipient) and g["state"] == "active" for g in grants):
            return
        status, out = inst.api(token, "POST", f"/v1/datasets/{dataset}/grants",
                               {"recipient": ident("principal/" + recipient), "release": release, "actions": actions})
        if status != 201:
            raise Failure(f"share with {PEOPLE[recipient][0]} returned HTTP {status} {out}")

    def member(self, spec, lab, project, key):
        inst, token, principal = self.inst, self.tokens[spec["lead"]], ident("principal/" + key)

        def active(roster):
            return next((r for r in roster if r["principal"] == principal and r["status"] == "active"), None)
        view = inst.get(token, "/v1/labs/" + lab)
        if not active(view["roster"]):
            inst.change(token, "add lab member " + key, f"/v1/labs/{lab}/members", {"principal": principal, "role": "member"},
                        {"lab:" + lab: view["generation"]})
        membership = active(inst.get(token, "/v1/labs/" + lab)["roster"])
        view = inst.get(token, "/v1/projects/" + project)
        if not active(view["roster"]):
            inst.change(token, "add project contributor " + key, f"/v1/projects/{project}/participants",
                        {"principal": principal, "role": "contributor", "membership": membership["id"]},
                        {"project:" + project: view["generation"], "membership:" + membership["id"]: membership["generation"]})

    def converge(self):
        labs, shares = [], []
        for index, spec in enumerate(LABS):
            if index > 0:
                # A lab's administrator must already be a principal. A read-only share of the first lab's
                # first accepted release introduces the next lab's lead, and is a cross-lab share to show.
                first = labs[0]
                self.share(LABS[0]["lead"], first["datasets"][0]["id"], spec["lead"], READ, release=first["datasets"][0]["head"])
                shares.append({"dataset": first["datasets"][0]["id"], "recipient": spec["lead"], "fixedRelease": True})
            lab = self.lab(spec, index == 0)
            project = self.project(spec, lab)
            datasets = [self.dataset(spec, project, wanted) for wanted in spec["datasets"]]
            for key, actions in spec["members"].items():
                self.share(spec["lead"], datasets[0]["id"], key, actions)
                shares.append({"dataset": datasets[0]["id"], "recipient": key, "fixedRelease": False})
                self.member(spec, lab, project, key)
                self.person(key, spec["name"])
            labs.append({"id": lab, "name": spec["name"], "lead": spec["lead"], "members": list(spec["members"]),
                         "project": {"id": project, "name": spec["project"]["name"]}, "datasets": datasets})
        return labs, shares


def report(inst, tag, labs, shares, people):
    summary = {"url": inst.url + "/", "project": inst.project, "image": tag, "operator": OPERATOR,
               "labs": labs, "shares": shares, "people": people}
    descriptor = os.open(inst.home / "instance.json", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as out:
        json.dump(summary, out, indent=2)
        out.write("\n")
    lines = ["", "Sanctity demo instance is ready.", "",
             f"  Console  {inst.url}/   (use this exact address; the service refuses \"localhost\")",
             f"  State    {inst.home}   (generated passwords and the credentials below)", ""]
    for lab in labs:
        lines.append(f"  {lab['name']}  >  {lab['project']['name']}")
        lines += [f"      {d['label']}" for d in lab["datasets"]]
    lines += ["", "  DEMO CREDENTIALS for this local instance only. They expire on " + min(p["expiresAt"] for p in people)[:10] + ".",
              "  Paste one access token into the console's sign-in form.", ""]
    width = max(len(p["name"]) for p in people)
    for p in people:
        lines.append(f"  {p['name']:<{width}}  {p['role']}, {p['lab']}")
        lines.append(f"  {'':<{width}}  {p['token']}")
    lines += ["", "  Stop    python3 scripts/demo-instance.py down",
              "  Remove  python3 scripts/demo-instance.py down --volumes", ""]
    print("\n".join(lines), flush=True)


def up(inst):
    need("docker")
    run(["docker", "compose", "version"], what="docker compose")
    directory, archive_sha = release()
    tag = image(directory, archive_sha)
    inst.prepare(tag)
    database(inst)
    migrate(inst, tag)
    bootstrap(inst)
    serve(inst, tag)
    say("Checking labs, projects, people and datasets")
    seed = Seed(inst)
    labs, shares = seed.converge()
    report(inst, tag, labs, shares, seed.people)


def down(inst, volumes):
    need("docker")
    inst.compose("down", "--remove-orphans", *(["--volumes"] if volumes else []), what="docker compose down")
    if volumes and inst.home.exists():
        # Only a directory this script marked for this instance is ever deleted.
        if (inst.home / MARKER).is_file() and (inst.home / MARKER).read_text().strip() == inst.project:
            shutil.rmtree(inst.home)
        else:
            raise Failure(f"left {inst.home} in place: it is not marked as this instance's state")
    print(f"Removed the containers of {inst.project}" + (", its data volumes and its local state." if volumes
          else ". Its data volumes remain; add --volumes to delete them."))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("up", help="start or update the instance and print its address and demo credentials")
    stop = commands.add_parser("down", help="stop and remove the containers")
    stop.add_argument("--volumes", action="store_true", help="also delete the data volumes and the local state directory")
    passthrough = commands.add_parser("compose", help="run a docker compose command for this instance")
    passthrough.add_argument("arguments", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    try:
        inst = Instance()
        if args.command == "up":
            up(inst)
        elif args.command == "down":
            down(inst, args.volumes)
        else:
            sys.exit(subprocess.run([str(a) for a in inst.compose_argv(*args.arguments)], env=inst.compose_env()).returncode)
    except Failure as failure:
        print("demo-instance: " + str(failure), file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
