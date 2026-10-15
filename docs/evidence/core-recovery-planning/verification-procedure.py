"""Validate the recovery-priority plan and retain a planning-only receipt.

Run from any directory. This reads mote, checks exact criteria/dependencies and
historical provenance, and refreshes this amendment's evidence/current progress.
It does not execute service tests or change implementation-task status.
"""
import collections
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import zipfile
from concurrent.futures import ThreadPoolExecutor

root = pathlib.Path(__file__).resolve().parents[3]
evidence = root / 'docs/evidence/core-recovery-planning'


def read(path):
    return json.loads((root / path).read_text())


def sha(path):
    return hashlib.sha256((root / path).read_bytes()).hexdigest()


def write(path, value):
    (root / path).write_text(json.dumps(value, indent=2) + '\n')


def mote(*args):
    return json.loads(subprocess.check_output(
        ['mote', '--actor', 'codex-core-planner', '--json', *args], cwd=root))


b = read('docs/institutional-readiness-backlog.json')
m = read('docs/evidence/institutional-readiness-motes.json')
changes = read('docs/evidence/core-recovery-planning/changes.json')
before = read('docs/evidence/core-recovery-planning/prior-mote-states.json')
with zipfile.ZipFile(evidence / 'prior-documents.zip') as z:
    old = json.loads(z.read('docs/institutional-readiness-backlog.json'))
    oldmap = json.loads(z.read('docs/evidence/institutional-readiness-motes.json'))
    oldprogress = json.loads(z.read('docs/evidence/institutional-pilot-progress.json'))
    for item in read('docs/evidence/core-recovery-planning/prior-documents.json')['files']:
        assert hashlib.sha256(z.read(item['path'])).hexdigest() == item['sha256']

ids = {'PROGRAM': m['epic'], **m['epics'], **m['items'], **m['tasks']}
oldids = {'PROGRAM': oldmap['epic'], **oldmap['epics'], **oldmap['items'], **oldmap['tasks']}
nodes = {n['key']: n for group in ['epics', 'items', 'tasks'] for n in b[group]}
oldnodes = {n['key']: n for group in ['epics', 'items', 'tasks'] for n in old[group]}
assert len(ids) == len(set(ids.values())) == len(nodes) + 1 == 119
assert all(ids[k] == v for k, v in oldids.items())
assert set(ids) - set(oldids) == set(changes['created']) == {'O03.6', 'O03.7', 'O03.8'}
assert all(ids[k] == v for k, v in changes['created'].items())
for key, n in oldnodes.items():
    assert set(n['acceptance']) <= set(nodes[key]['acceptance']), (key, 'lost original criterion')
    assert set(n['dependsOn']) <= set(nodes[key]['dependsOn']), (key, 'lost original dependency')

with ThreadPoolExecutor(max_workers=8) as pool:
    states = dict(pool.map(lambda kv: (kv[0], mote('show', kv[1])), ids.items()))
for key, n in nodes.items():
    state = states[key]
    assert state['title'] == key + ': ' + n['title'], (key, 'title')
    assert state['priority'] == n['priority'], (key, 'priority')
    assert {d['parent'] for d in state['deps'] if d['kind'] == 'blocks'} == {
        ids[d] for d in n['dependsOn']}, (key, 'dependencies')
    parent = 'PROGRAM' if key in m['epics'] else n['epic'] if key in m['items'] else n['gate']
    assert {r['parent'] for r in state['relations'] if r['kind'] == 'parent'} == {ids[parent]}, (key, 'parent')
    for criterion in n['acceptance']:
        assert criterion in state['body'], (key, 'criterion missing in mote')
    for ref in n.get('references', []):
        assert (root / ref.split('#')[0]).exists(), (key, 'missing reference', ref)
        assert ref in state['body'], (key, 'reference missing in mote', ref)
    assert n.get('ownerRole'), (key, 'owner')
    if key in m['tasks']:
        assert len(n['acceptance']) >= 3 and n.get('verification') and n.get('evidence'), (key, 'specification')
    if key in before:
        assert state['status'] == before[key]['status'], (key, 'planning changed status')
    else:
        assert state['status'] == 'open' and state['priority'] == 0, (key, 'new task not open P0')
assert states['PROGRAM']['status'] == before['PROGRAM']['status'] == 'open'
assert {d['parent'] for d in states['PROGRAM']['deps'] if d['kind'] == 'blocks'} == {
    ids[k] for k in b['program']['required']}

active, visited = set(), set()


def walk(key):
    assert key not in active, ('cycle', key)
    if key in visited:
        return
    active.add(key)
    for dep in nodes[key]['dependsOn']:
        walk(dep)
    active.remove(key)
    visited.add(key)


for key in nodes:
    walk(key)


def closure(key):
    out = set()

    def visit(k):
        if k in out:
            return
        out.add(k)
        for dep in nodes[k]['dependsOn']:
            visit(dep)

    visit(key)
    return out


early = closure('O03.8')
core, local = closure('EP9'), closure('L01')
assert not any(k.startswith(('F04', 'F05', 'F06')) for k in early), 'Early recovery still waits for browser identity/workspace'
assert {'F03.1', 'F03.2', 'F02.3', 'F03.3', 'F03.4', 'H03.1', 'O03.6', 'O03.7'} <= early
for label, keys in [('early recovery', early), ('core', core), ('local demo', local)]:
    assert not [k for k in keys if nodes[k].get('kind') in ['institutional', 'mixed']
                or nodes[k].get('externalInputs')], (label, 'external prerequisite')
assert {'F04.3', 'O03.2', 'O03.4', 'O03.5', 'O04.1', 'O04.3', 'O03.8'} <= closure('H03')
assert {'O03.3', 'O03.5', 'F04.3'} <= closure('O03')
assert 'Q03.1' in closure('EP6')
assert 'H03' in closure('H00') and 'H00' in closure('L01')
required = set().union(*(closure(k) for k in b['program']['required']))
assert all((t['key'] in required) == t['required'] for t in b['tasks'])
completed = sorted(k for k, s in states.items() if s['status'] == 'closed')
assert completed == sorted(oldprogress['completed'])
assert all(states[k]['status'] == 'open' for k, n in nodes.items()
           if n.get('kind') in ['institutional', 'mixed'] or k in b['coreHardening']['areaGates'] + ['H00'])
scheduled = [k for t in b['coreHardening']['tranches'] for k in t['orderedWork']]
assert len(scheduled) == len(set(scheduled)), 'Duplicate tranche scheduling'
assert all(k in scheduled for k in core if k in m['tasks'] and k not in completed)
assert set(b['coreHardening']['recoveryPriority']['orderedWork']) <= set(b['coreHardening']['tranches'][1]['orderedWork'])
for risk in b['coreHardening']['risks']:
    assert set(risk['work']) <= closure(risk['owner']), (risk['key'], 'area coverage')
    assert set(risk.get('integrationWork', [])) <= closure('H00')
ready_ids = {x['id'] for x in mote('ready')}
ready = sorted(k for k, i in ids.items() if i in ready_ids)
assert ready == oldprogress['nextReadyEngineering']
for k, state in states.items():
    deps = b['program']['required'] if k == 'PROGRAM' else nodes[k]['dependsOn']
    assert state['ready'] == (state['status'] == 'open' and all(states[d]['status'] == 'closed' for d in deps)), (k, 'readiness')

# Resolve prior receipt inputs against exact archives; never relabel old receipts.
archive_index = set()
archives = list((root / 'docs/evidence/institutional-pilot').glob('*/*snapshot.zip'))
archives += [root / p for p in [
    'docs/evidence/core-hardening-t1/prior-documents.zip', 'docs/evidence/core-hardening-t1/prior-tooling.zip',
    'docs/evidence/core-hardening-t2/prior-documents.zip', 'docs/evidence/core-recovery-planning/prior-documents.zip',
    'local-artifacts/compatibility/pre-core-20261014/source.zip']]
for archive in archives:
    with zipfile.ZipFile(archive) as z:
        assert len(z.namelist()) == len(set(z.namelist()))
        for name in z.namelist():
            archive_index.add((name, hashlib.sha256(z.read(name)).hexdigest()))
baseline = read('docs/evidence/core-hardening-baseline/manifest.json')
for item in baseline['files']:
    assert sha(item['snapshot']) == item['sha256']
    archive_index.add((item['original'], item['sha256']))


def resolve(name, digest):
    return ((root / name).is_file() and sha(name) == digest) or (name, digest) in archive_index


receipt_checks = 0
for key in completed:
    receipt = read(f'docs/evidence/institutional-pilot/{key}/receipt.json')
    assert receipt['status'] == 'passed'
    for name, digest in receipt['inputs'].items():
        assert resolve(name, digest), (key, 'unresolved historical receipt', name)
        receipt_checks += 1
for path in ['docs/evidence/core-hardening-t1/receipt.json', 'docs/evidence/core-hardening-t2/receipt.json']:
    for name, digest in read(path)['inputs'].items():
        assert resolve(name, digest), (path, 'unresolved tranche provenance', name)
        receipt_checks += 1
source_checks = 0
for key in ['O01.1', 'O01.2', 'O01.3', 'F02.2']:
    for name, digest in read(f'docs/evidence/institutional-pilot/{key}/source-inventory.json').items():
        assert resolve(name, digest), (key, 'source provenance', name)
        source_checks += 1
    artifact = read(f'docs/evidence/institutional-pilot/{key}/receipt.json')['artifact']
    assert sha(artifact['path']) == artifact['sha256']

guide = (root / 'docs/institutional-pilot-epics.md').read_text()
for task in b['tasks']:
    row = '| ' + task['key'] + ' | ' + task['title'] + ' | ' + (', '.join(task['dependsOn']) or 'None') + ' | ' + task['kind'] + ' |'
    assert guide.count(row) == 1, (task['key'], 'published task table')
for gate in b['items']:
    if gate['epic'] == 'EP9':
        assert '| ' + gate['key'] + ' | ' + gate['title'] + ' | ' + ', '.join(gate['dependsOn']) + ' |' in guide
paths = ['docs/core-hardening-plan.md', 'docs/institutional-pilot-epics.md', 'docs/institutional-readiness-plan.md',
         'docs/implementation-plan.md', 'docs/local-demo-plan.md']
links = 0
for path in paths:
    p = root / path
    for href in re.findall(r'\]\(([^)]+)\)', p.read_text()):
        if href.startswith(('https:', 'http:', '#', 'app:')):
            continue
        target = (p.parent / href.split('#')[0]).resolve()
        if target != evidence / 'receipt.json':
            assert target.exists(), (path, 'broken link', href)
        links += 1
health = mote('doctor')
assert health['ok'] and health['fsck_clean']
flight = mote('in-flight')
assert not any(flight[k] for k in ['claims', 'reservations', 'doing', 'orphaned_claims', 'orphaned_reservations'])
subprocess.run(['git', 'diff', '--check'], cwd=root, check=True, capture_output=True)
counts = {
    'requiredEpics': sum(e['required'] for e in b['epics']), 'conditionalEpics': sum(not e['required'] for e in b['epics']),
    'gates': len(b['items']), 'tasks': len(b['tasks']), 'requiredTasks': sum(t['required'] for t in b['tasks']),
    'conditionalTasks': sum(not t['required'] for t in b['tasks']), 'taskKinds': dict(collections.Counter(t['kind'] for t in b['tasks'])),
    'totalNodesIncludingProgram': len(ids), 'closedTasks': sum(k in m['tasks'] for k in completed),
    'closedGates': sum(k in m['items'] for k in completed), 'closedEpics': sum(k in m['epics'] for k in completed)}
checks = {'all119MoteSpecificationsParentsAndDependencies': 'pass', 'acyclic': True,
          'originalIDsCriteriaDependenciesAndStatusesPreserved': True, 'newTasksOpenP0': 3,
          'earlyRecoveryHasNoBrowserIdentityOrInstitutionalPrerequisite': True,
          'fullIdentitySessionLifecycleAndOffHostAcceptanceStillRequired': True,
          'allRequiredTasksReachable': True, 'publishedTaskAndGateTablesMatch': True,
          'historicalReceiptInputsResolved': receipt_checks, 'packageSourceInputsResolved': source_checks,
          'fourPackageDigestsUnchanged': True, 'currentDocumentLinksResolved': links,
          'moteDoctorAndFsck': 'pass', 'activeClaimsAndReservations': 0, 'diffWhitespace': 'pass'}
now = datetime.datetime.now(datetime.timezone.utc).isoformat()
inputs = {p: sha(p) for p in paths + [
    'docs/institutional-readiness-backlog.json', 'docs/evidence/institutional-readiness-motes.json',
    'docs/evidence/core-recovery-planning/changes.json', 'docs/evidence/core-recovery-planning/prior-documents.zip',
    'docs/evidence/core-recovery-planning/prior-documents.json', 'docs/evidence/core-recovery-planning/prior-mote-states.json',
    'docs/evidence/core-recovery-planning/verification-procedure.py']}
limits = ['Planning/acceptance/dependency changes only; no recovery implementation or new service test execution.',
          'Organization-enabled recovery remains unavailable until the new path is implemented and qualified.',
          'Current-capability recovery does not close full identity/session/lifecycle, H03/H00, local-demo or institutional acceptance.',
          'Separate services on this computer do not prove survival of loss of the computer or disk.']
progress = {
    'schemaVersion': 7, 'at': now, 'program': m['epic'], 'immediateMilestone': oldprogress['immediateMilestone'],
    'engineeringCoordinator': oldprogress['engineeringCoordinator'], 'counts': counts, 'completed': completed,
    'completedThisTranche': [], 'updateKind': 'recovery-priority planning; no implementation completed',
    'nextReadyEngineering': ready, 'recommendedFirstWork': ['F03.1'], 'independentlyReadyRecoveryWork': ['H03.1'],
    'recoveryPriority': b['coreHardening']['recoveryPriority'], 'areaStatus': oldprogress['areaStatus'],
    'checks': checks, 'validationReceipt': 'docs/evidence/core-recovery-planning/receipt.json',
    'lastImplementationEvidence': {'receipt': 'docs/evidence/core-hardening-t2/receipt.json',
                                   'completed': oldprogress['completedThisTranche'],
                                   'taskReceipts': oldprogress['trancheReceipts'],
                                   'verification': oldprogress['verification'],
                                   'scope': 'Historical implementation evidence; not a test run for this planning amendment.'},
    'inputs': inputs, 'limitations': limits + oldprogress['limitations']}
write('docs/evidence/institutional-pilot-progress.json', progress)
write('docs/evidence/core-recovery-planning/mote-states.json', states)
receipt = {
    'schemaVersion': 1, 'at': now, 'kind': 'recovery-priority-planning', 'status': 'passed',
    'created': changes['created'], 'amended': changes['amended'], 'completedImplementationTasks': [],
    'counts': counts, 'checks': checks, 'nextReadyEngineering': ready,
    'recoveryPriority': b['coreHardening']['recoveryPriority'],
    'inputs': inputs | {'docs/evidence/institutional-pilot-progress.json': sha('docs/evidence/institutional-pilot-progress.json'),
                        'docs/evidence/core-recovery-planning/mote-states.json': sha('docs/evidence/core-recovery-planning/mote-states.json')},
    'limitations': limits}
write('docs/evidence/core-recovery-planning/receipt.json', receipt)
print(json.dumps({'counts': counts, 'checks': checks, 'nextReadyEngineering': ready}, indent=2))
