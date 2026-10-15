"""Audit F03.2 completion, immutable evidence, mote graph and the next ready work."""
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import zipfile
from concurrent.futures import ThreadPoolExecutor

root = pathlib.Path(__file__).resolve().parents[3]
out = root / 'docs/evidence/core-authority-f032'


def read(path):
    return json.loads((root / path).read_text())


def sha(path):
    return hashlib.sha256((root / path).read_bytes()).hexdigest()


def write(path, value):
    (root / path).write_text(json.dumps(value, indent=2) + '\n')


def mote(*args):
    return json.loads(subprocess.check_output(['mote', '--actor', 'codex-core-build', '--json', *args], cwd=root))


b = read('docs/institutional-readiness-backlog.json')
m = read('docs/evidence/institutional-readiness-motes.json')
before = read('docs/evidence/core-authority-f031/mote-states.json')
ids = {'PROGRAM': m['epic'], **m['epics'], **m['items'], **m['tasks']}
nodes = {n['key']: n for group in ['epics', 'items', 'tasks'] for n in b[group]}
with zipfile.ZipFile(out / 'prior-documents.zip') as z:
    previous = json.loads(z.read('docs/evidence/institutional-pilot-progress.json'))
    for p in read('docs/evidence/core-authority-f032/prior-documents.json')['files']:
        assert hashlib.sha256(z.read(p['path'])).hexdigest() == p['sha256']
assert len(ids) == len(set(ids.values())) == len(nodes) + 1 == 119
with ThreadPoolExecutor(max_workers=8) as pool:
    states = dict(pool.map(lambda kv: (kv[0], mote('show', kv[1])), ids.items()))
for key, n in nodes.items():
    s = states[key]
    assert s['id'] == before[key]['id'] and s['title'] == key + ': ' + n['title']
    assert s['priority'] == n['priority']
    assert s['deps'] == before[key]['deps'] and s['relations'] == before[key]['relations']
    assert {d['parent'] for d in s['deps'] if d['kind'] == 'blocks'} == {ids[d] for d in n['dependsOn']}
    parent = 'PROGRAM' if key in m['epics'] else n['epic'] if key in m['items'] else n['gate']
    assert {r['parent'] for r in s['relations'] if r['kind'] == 'parent'} == {ids[parent]}
    for criterion in n['acceptance']:
        assert criterion in s['body'], (key, criterion)
    for ref in n.get('references', []):
        assert ref in s['body'] and (root / ref.split('#')[0]).exists(), (key, ref)
    assert s['status'] == ('closed' if key == 'F03.2' else before[key]['status']), key
assert states['PROGRAM']['status'] == 'open'
assert states['PROGRAM']['deps'] == before['PROGRAM']['deps']
completed = sorted(k for k, s in states.items() if s['status'] == 'closed')
assert completed == sorted(previous['completed'] + ['F03.2'])
visited, active = set(), set()


def walk(k):
    assert k not in active, ('cycle', k)
    if k not in visited:
        active.add(k)
        for d in nodes[k]['dependsOn']:
            walk(d)
        active.remove(k)
        visited.add(k)


for key in nodes:
    walk(key)
ready_ids = {s['id'] for s in mote('ready')}
ready = sorted(k for k, i in ids.items() if i in ready_ids)
assert ready == ['F02.3', 'F03.3', 'F03.4', 'F04.1', 'H03.1', 'O01.4', 'O02.1', 'O02.3', 'O03.6']
for key, s in states.items():
    deps = b['program']['required'] if key == 'PROGRAM' else nodes[key]['dependsOn']
    assert s['ready'] == (s['status'] == 'open' and all(states[d]['status'] == 'closed' for d in deps))

archive_index = set()
archives = list((root / 'docs/evidence/institutional-pilot').glob('*/*snapshot.zip'))
archives += list((root / 'docs/evidence').glob('core-*/prior-*.zip'))
archives += [root / 'local-artifacts/compatibility/pre-core-20261014/source.zip']
for archive in archives:
    with zipfile.ZipFile(archive) as z:
        assert len(z.namelist()) == len(set(z.namelist()))
        archive_index.update((name, hashlib.sha256(z.read(name)).hexdigest()) for name in z.namelist())
for p in read('docs/evidence/core-hardening-baseline/manifest.json')['files']:
    assert sha(p['snapshot']) == p['sha256']
    archive_index.add((p['original'], p['sha256']))


def resolves(path, digest):
    return ((root / path).is_file() and sha(path) == digest) or (path, digest) in archive_index


checks_count = 0
for key in completed:
    receipt = read(f'docs/evidence/institutional-pilot/{key}/receipt.json')
    assert receipt['status'] == 'passed'
    for path, digest in receipt['inputs'].items():
        assert resolves(path, digest), (key, path)
        checks_count += 1
for prior in ['core-hardening-t1', 'core-hardening-t2', 'core-recovery-planning', 'core-authority-f031']:
    for path, digest in read(f'docs/evidence/{prior}/receipt.json')['inputs'].items():
        assert resolves(path, digest), (prior, path)
        checks_count += 1
receipt_path = 'docs/evidence/institutional-pilot/F03.2/receipt.json'
task = read(receipt_path)
for path, digest in task['inputs'].items():
    assert sha(path) == digest, ('current F03.2 input changed', path)
assert all(a['status'] == 'passed' for a in task['acceptance'])
assert task['verification']['distinctTests'] == 351 and task['verification']['adrCases'] == 38
assert task['verification']['fixtureSha256'] == read('docs/evidence/institutional-pilot/F01.2/receipt.json')['inputs']['docs/decisions/institutional-authority-cases.json']

paths = ['docs/policy-transactions.md', 'docs/organization-reference.md', 'docs/core-hardening-plan.md', 'docs/institutional-pilot-epics.md',
         'docs/implementation-plan.md', 'docs/local-demo-plan.md', 'docs/institutional-readiness-plan.md']
links = 0
for name in paths:
    p = root / name
    for href in re.findall(r'\]\(([^)]+)\)', p.read_text()):
        if not href.startswith(('https:', 'http:', '#', 'app:')):
            assert (p.parent / href.split('#')[0]).resolve().exists(), (name, href)
            links += 1
guide = (root / 'docs/institutional-pilot-epics.md').read_text()
for t in b['tasks']:
    assert '| ' + t['key'] + ' | ' + t['title'] + ' | ' + (', '.join(t['dependsOn']) or 'None') + ' | ' + t['kind'] + ' |' in guide, t['key']
health = mote('doctor')
assert health['ok'] and health['fsck_clean']
flight = mote('in-flight')
assert not any(flight[k] for k in ['claims', 'reservations', 'doing', 'orphaned_claims', 'orphaned_reservations'])
subprocess.run(['git', 'diff', '--check'], cwd=root, check=True, capture_output=True)
counts = previous['counts'] | {'closedTasks': 14}
checks = {'all119MoteCriteriaParentsDependenciesAndStatuses': 'pass', 'acyclic': True,
          'onlyF032Closed': True, 'readyQueueMatchesGraph': True, 'preservedHistoricalReceiptInputs': checks_count,
          'allCurrentF032InputHashesMatch': True, 'adrFixtureUnchanged': True,
          'currentDocumentLinksResolved': links, 'publishedTaskTablesMatch': True,
          'moteDoctorAndFsck': 'pass', 'activeClaimsAndReservations': 0, 'diffWhitespace': 'pass'}
now = datetime.datetime.now(datetime.timezone.utc).isoformat()
inputs = {p: sha(p) for p in paths + [receipt_path, 'docs/institutional-readiness-backlog.json',
          'docs/evidence/institutional-readiness-motes.json', 'docs/evidence/core-authority-f032/prior-documents.zip',
          'docs/evidence/core-authority-f032/prior-documents.json', 'docs/evidence/core-authority-f032/verification-procedure.py']}
progress = previous | {
    'schemaVersion': 9, 'at': now, 'counts': counts, 'completed': completed, 'completedThisTranche': ['F03.2'],
    'updateKind': 'F03.2 transaction authority and unified local policy history completed; independent durability next',
    'nextReadyEngineering': ready, 'recommendedFirstWork': ['O03.6'], 'checks': checks,
    'validationReceipt': 'docs/evidence/core-authority-f032/receipt.json',
    'lastImplementationEvidence': {'receipt': receipt_path, 'completed': ['F03.2'],
                                   'verification': task['verification'], 'scope': 'Current PostgreSQL authority, unified local policy history, full regression and packaged compatibility; independent witness/recovery remains open.'},
    'previousImplementationEvidence': previous['lastImplementationEvidence'],
    'recoveryPriority': previous['recoveryPriority'] | {'status': 'Authority prerequisites complete; independent witness, backup and restore implementation remain open.'},
    'inputs': inputs, 'limitations': task['limitations'] + [
        'Organization-aware recovery is still unimplemented; O03.6-O03.8 remain open.',
        'All six core reviews and H00 remain open; no local-demo or institutional acceptance.']}
write('docs/evidence/institutional-pilot-progress.json', progress)
write('docs/evidence/core-authority-f032/mote-states.json', states)
write('docs/evidence/core-authority-f032/receipt.json', {
    'schemaVersion': 1, 'at': now, 'kind': 'transaction-authority-F03.2', 'status': 'passed', 'completed': ['F03.2'],
    'taskReceipt': receipt_path, 'counts': counts, 'checks': checks, 'verification': task['verification'],
    'nextReadyEngineering': ready, 'recommendedFirstWork': ['O03.6'],
    'inputs': inputs | {'docs/evidence/institutional-pilot-progress.json': sha('docs/evidence/institutional-pilot-progress.json'),
                        'docs/evidence/core-authority-f032/mote-states.json': sha('docs/evidence/core-authority-f032/mote-states.json')},
    'limitations': progress['limitations']})
print(json.dumps({'counts': counts, 'checks': checks, 'nextReadyEngineering': ready}, indent=2))
