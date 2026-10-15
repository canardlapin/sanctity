"""Audit integrated qualification and parent completion, unchanged criteria, graph and retained evidence."""
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile
from concurrent.futures import ThreadPoolExecutor

root=Path(__file__).resolve().parents[4]
out=Path(__file__).resolve().parent

def read(path): return json.loads((root/path).read_text())
def sha(path): return hashlib.sha256((root/path).read_bytes()).hexdigest()
def write(path,value): (root/path).write_text(json.dumps(value,indent=2)+'\n')
def mote(*args): return json.loads(subprocess.check_output(['mote','--actor','codex-core-build','--json',*args],cwd=root))

b=read('docs/institutional-readiness-backlog.json'); m=read('docs/evidence/institutional-readiness-motes.json')
before=read('docs/evidence/institutional-pilot/O03.6.2/mote-states.json')
with zipfile.ZipFile(out/'before.zip') as z:
    previous=json.loads(z.read('docs/evidence/institutional-pilot-progress.json'))
    oldbacklog=json.loads(z.read('docs/institutional-readiness-backlog.json'))
ids={'PROGRAM':m['epic'],**m['epics'],**m['items'],**m['tasks']}
nodes={n['key']:n for group in ['epics','items','tasks'] for n in b[group]}
assert len(ids)==len(set(ids.values()))==len(nodes)+1==122
with ThreadPoolExecutor(max_workers=8) as pool:
    states=dict(pool.map(lambda kv:(kv[0],mote('show',kv[1])),ids.items()))
for key,n in nodes.items():
    s=states[key]
    assert s['title']==key+': '+n['title'] and s['priority']==n['priority']
    assert {d['parent'] for d in s['deps'] if d['kind']=='blocks'}=={ids[d] for d in n['dependsOn']},key
    parent='PROGRAM' if key in m['epics'] else n['epic'] if key in m['items'] else n.get('parentTask',n.get('gate'))
    assert {r['parent'] for r in s['relations'] if r['kind']=='parent'}=={ids[parent]},key
    for criterion in n['acceptance']: assert criterion in s['body'],(key,criterion)
    for ref in n.get('references',[]): assert ref in s['body'] and (root/ref.split('#')[0]).exists(),(key,ref)
    if key in before:
        assert s['id']==before[key]['id'],key
        if key not in {'O03.6.3','O03.6'}: assert s['status']==before[key]['status'],key
        assert s['relations']==before[key]['relations'],key
        assert s['deps']==before[key]['deps'],key
assert states['PROGRAM']['status']=='open' and states['PROGRAM']['deps']==before['PROGRAM']['deps']
oldparent=next(t for t in oldbacklog['tasks'] if t['key']=='O03.6')
assert nodes['O03.6']['acceptance']==oldparent['acceptance']
assert nodes['O03.6']['dependsOn']==oldparent['dependsOn']
completed=sorted(k for k,s in states.items() if s['status']=='closed')
assert completed==sorted(previous['completed']+['O03.6.3','O03.6'])
visited=set(); active=set()
def walk(key):
    assert key not in active,('cycle',key)
    if key not in visited:
        active.add(key)
        for dep in nodes[key]['dependsOn']: walk(dep)
        active.remove(key); visited.add(key)
for key in nodes: walk(key)
ready_ids={s['id'] for s in mote('ready')}
ready=sorted(k for k,i in ids.items() if i in ready_ids)
assert ready==['F02.3','F03.3','F03.4','F04.1','H03.1','O01.4','O02.1','O02.3']
for key,s in states.items():
    deps=b['program']['required'] if key=='PROGRAM' else nodes[key]['dependsOn']
    assert s['ready']==(s['status']=='open' and all(states[d]['status']=='closed' for d in deps)),key

archive_index=set()
archives=list((root/'docs/evidence/institutional-pilot').glob('*/*snapshot.zip'))
archives+=list((root/'docs/evidence').glob('core-*/prior-*.zip'))
archives+=list((root/'docs/evidence/institutional-pilot/O03.6').glob('pre-split-*.zip'))
archives+=list((root/'docs/evidence/institutional-pilot').glob('*/before*.zip'))
archives+=[root/'local-artifacts/compatibility/pre-core-20261014/source.zip']
for archive in archives:
    with zipfile.ZipFile(archive) as z:
        assert len(z.namelist())==len(set(z.namelist()))
        archive_index.update((name,hashlib.sha256(z.read(name)).hexdigest()) for name in z.namelist())
for p in read('docs/evidence/core-hardening-baseline/manifest.json')['files']:
    assert sha(p['snapshot'])==p['sha256']; archive_index.add((p['original'],p['sha256']))
def resolves(path,digest): return ((root/path).is_file() and sha(path)==digest) or (path,digest) in archive_index
count=0
for key in completed:
    receipt=read(f'docs/evidence/institutional-pilot/{key}/receipt.json'); assert receipt['status']=='passed'
    for path,digest in receipt['inputs'].items(): assert resolves(path,digest),(key,path); count+=1
for prior in ['core-hardening-t1','core-hardening-t2','core-recovery-planning','core-authority-f031','core-authority-f032']:
    for path,digest in read(f'docs/evidence/{prior}/receipt.json')['inputs'].items(): assert resolves(path,digest),(prior,path); count+=1
for path,expected in read('docs/evidence/institutional-pilot/F03.2/historical-source-before.json').items():
    p=root/path
    assert sha(path)==expected['sha256'] and p.stat().st_ino==expected['inode'] and p.stat().st_mtime_ns==expected['mtimeNs'],path
receipt_path='docs/evidence/institutional-pilot/O03.6.3/receipt.json'; task=read(receipt_path)
for path,digest in task['inputs'].items(): assert sha(path)==digest,('current qualification input changed',path)
assert all(a['status']=='passed' for a in task['acceptance'])
assert task['verification']['distinctTests']==250 and task['verification']['distinctProcessCases']==33
assert task['verification']['packagedWitnessChecks']==7 and task['verification']['packagedServiceChecks']==11
assert task['verification']['packagedCatalogChecks']==10
assert read(task['parentReview'])['status']=='passed'
assert states['O03.6']['status']=='closed' and all(states[k]['status']=='open' for k in ['O03.7','O03.8','H03.1','O03.2','O03.3','SITE01','H00'])
paths=['docs/policy-process-qualification.md','docs/policy-catalog.md','docs/policy-witness.md','docs/policy-transactions.md','docs/core-hardening-plan.md','docs/institutional-pilot-epics.md',
       'docs/implementation-plan.md','docs/local-demo-plan.md','docs/institutional-readiness-plan.md']
links=0
for name in paths:
    p=root/name
    for href in re.findall(r'\]\(([^)]+)\)',p.read_text()):
        if not href.startswith(('https:','http:','#','app:')):
            assert (p.parent/href.split('#')[0]).resolve().exists(),(name,href); links+=1
guide=(root/'docs/institutional-pilot-epics.md').read_text()
for t in b['tasks']: assert '| '+t['key']+' | '+t['title']+' | '+(', '.join(t['dependsOn']) or 'None')+' | '+t['kind']+' |' in guide,t['key']
health=mote('doctor'); assert health['ok'] and health['fsck_clean']
flight=mote('in-flight')
own=next(a['work'] for a in flight['actors'] if a['actor']=='codex-core-build')
assert not any(own[k] for k in ['active_claims','active_reservations','doing_beads','orphaned_claims','orphaned_reservations'])
assert not any(s['id'] in ids.values() for s in flight['doing'])
subprocess.run(['git','diff','--check'],cwd=root,check=True,capture_output=True)
counts=previous['counts']|{'tasks':85,'requiredTasks':79,'taskKinds':{'engineering':66,'mixed':7,'institutional':12},'totalNodesIncludingProgram':122,'closedTasks':18}
checks={'all122MoteCriteriaParentsDependenciesAndStatuses':'pass','parentAcceptanceUnchanged':True,'acyclic':True,
        'onlyO0363AndParentClosed':True,'readyQueueMatchesGraph':True,'preservedHistoricalReceiptInputs':count,
        'allCurrentQualificationInputHashesMatch':True,'historicalSourceIdentityPreserved':True,'currentDocumentLinksResolved':links,
        'publishedTaskTablesMatch':True,'moteDoctorAndFsck':'pass','activeCoreClaimsAndReservations':0,'unrelatedDoingWorkExcludedFromAudit':len(flight['doing']),'diffWhitespace':'pass'}
now=datetime.datetime.now(datetime.timezone.utc).isoformat()
inputs={p:sha(p) for p in paths+[receipt_path,'docs/institutional-readiness-backlog.json','docs/evidence/institutional-readiness-motes.json',
        'docs/evidence/institutional-pilot/O03.6.3/parent-acceptance-review.json','docs/evidence/institutional-pilot/O03.6/receipt.json','docs/evidence/institutional-pilot/O03.6.3/before.zip',
        'docs/evidence/institutional-pilot/O03.6.3/coordination-verification.py']}
progress=previous|{'schemaVersion':12,'at':now,'counts':counts,'completed':completed,'completedThisTranche':['O03.6.3','O03.6'],
    'updateKind':'Integrated process qualification and unchanged parent review complete; isolated backup automation is the next recovery prerequisite',
    'nextReadyEngineering':ready,'recommendedFirstWork':['H03.1'],'checks':checks,
    'validationReceipt':'docs/evidence/institutional-pilot/O03.6.3/coordination-verification.json',
    'lastImplementationEvidence':{'receipt':receipt_path,'completed':['O03.6.3','O03.6'],'verification':task['verification'],'scope':task['scope']},
    'previousImplementationEvidence':previous['lastImplementationEvidence'],
    'recoveryPriority':previous['recoveryPriority']|{'status':'Independent current-authority durability is qualified. H03.1 backup automation is next; O03.7 remains blocked by H03.1, and O03.8 remains open.','witnessSlices':['O03.6.1','O03.6.2','O03.6.3']},
    'inputs':inputs,'limitations':task['limitations']+['All six core reviews and H00 remain open; no complete demo or institutional acceptance.']}
write('docs/evidence/institutional-pilot-progress.json',progress)
write('docs/evidence/institutional-pilot/O03.6.3/mote-states.json',states)
write('docs/evidence/institutional-pilot/O03.6.3/coordination-verification.json',{'schemaVersion':1,'status':'passed','at':now,
    'kind':'integrated-policy-and-parent-completion','completed':['O03.6.3','O03.6'],'parent':'O03.6','parentStatus':'closed','counts':counts,'checks':checks,
    'nextReadyEngineering':ready,'recommendedFirstWork':['H03.1'],'inputs':inputs|{
        'docs/evidence/institutional-pilot-progress.json':sha('docs/evidence/institutional-pilot-progress.json'),
        'docs/evidence/institutional-pilot/O03.6.3/mote-states.json':sha('docs/evidence/institutional-pilot/O03.6.3/mote-states.json')},
    'limitations':progress['limitations']})
print(json.dumps({'counts':counts,'checks':checks,'nextReadyEngineering':ready},indent=2))
