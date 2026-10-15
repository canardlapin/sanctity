import collections,datetime,hashlib,json,pathlib,re,subprocess
from concurrent.futures import ThreadPoolExecutor
root=pathlib.Path(__file__).resolve().parents[3]
def read(p):return json.loads((root/p).read_text())
def digest(p):return hashlib.sha256((root/p).read_bytes()).hexdigest()
base=['mote','--actor','codex-core-build','--json']
def call(*args):return json.loads(subprocess.check_output(base+list(args),cwd=root))
b=read('docs/institutional-readiness-backlog.json');m=read('docs/evidence/institutional-readiness-motes.json');baseline=read('docs/evidence/core-hardening-baseline/manifest.json');oldmap=read('docs/evidence/core-hardening-baseline/docs/evidence/institutional-readiness-motes.json');oldprogress=read('docs/evidence/core-hardening-baseline/docs/evidence/institutional-pilot-progress.json');changes=read('docs/evidence/core-hardening-mote-changes.json')
ids={'PROGRAM':m['epic'],**m['epics'],**m['items'],**m['tasks']};oldids={'PROGRAM':oldmap['epic'],**oldmap['epics'],**oldmap['items'],**oldmap['tasks']};nodes={n['key']:n for group in ['epics','items','tasks'] for n in b[group]}
assert len(ids)==len(set(ids.values()))==len(nodes)+1==116
assert all(ids[k]==v for k,v in oldids.items())
assert set(ids)-set(oldids)==set(changes['created'])
assert len(changes['created'])==16 and len(changes['amendedExisting'])==25
assert len(nodes)==sum(len(b[g]) for g in ['epics','items','tasks'])
with ThreadPoolExecutor(max_workers=8) as pool:
 states=dict(pool.map(lambda kv:(kv[0],call('show',kv[1])),ids.items()))
for key,n in nodes.items():
 s=states[key]
 assert s['title']==key+': '+n['title'],(key,'title')
 assert s['priority']==n['priority'],(key,'priority')
 assert {d['parent'] for d in s['deps'] if d['kind']=='blocks'}=={ids[d] for d in n['dependsOn']},(key,'deps')
 parent='PROGRAM' if key in m['epics'] else n['epic'] if key in m['items'] else n['gate']
 assert {r['parent'] for r in s['relations'] if r['kind']=='parent'}=={ids[parent]},(key,'parent')
 for a in n['acceptance']:assert a in s['body'],(key,'criterion')
 for ref in n.get('references',[]):
  assert (root/ref.split('#')[0]).exists(),(key,'reference',ref)
  assert ref in s['body'],(key,'body reference',ref)
 assert n.get('ownerRole'),(key,'owner')
 if key in m['tasks']:
  assert len(n['acceptance'])>=3 and n.get('verification') and n.get('evidence'),(key,'task specification')
 assert s['status'] in ['open','closed'],(key,'status')
program=states['PROGRAM'];assert program['status']=='open'
assert {d['parent'] for d in program['deps'] if d['kind']=='blocks'}=={ids[k] for k in b['program']['required']}
visited=set();active=set()
def walk(k):
 assert k not in active,('cycle',k)
 if k in visited:return
 active.add(k)
 for d in nodes[k]['dependsOn']:walk(d)
 active.remove(k);visited.add(k)
for k in nodes:walk(k)
def closure(k):
 out=set()
 def visit(x):
  if x in out:return
  out.add(x)
  for d in nodes[x]['dependsOn']:visit(d)
 visit(k);return out
core=closure('EP9');local=closure('L01')
for path,keys in [('core',core),('local',local)]:
 assert not [k for k in keys if nodes[k].get('kind') in ['institutional','mixed'] or nodes[k].get('externalInputs')],(path,'external blocker')
required=set().union(*(closure(k) for k in b['program']['required']))
assert all(t['key'] in required for t in b['tasks'] if t['required'])
assert all(t['key'] not in required for t in b['tasks'] if not t['required'])
assert all(states[k]['status']=='open' for k,n in nodes.items() if n.get('kind') in ['institutional','mixed'])
completed=sorted(k for k,s in states.items() if s['status']=='closed');assert completed==sorted(set(oldprogress['completed'])|{'O01.2','F02.1','O01.3','H06.2','H04.1','O03.1','F02.2'}),completed
assert all(states[k]['status']=='open' for k in changes['created'] if k not in {'H06.2','H04.1'})
tranches=b['coreHardening']['tranches'];scheduled=[k for w in tranches for k in w['orderedWork']]
assert len(scheduled)==len(set(scheduled))
assert not {k for k in core if k in m['tasks'] and k not in completed and k not in scheduled}
for risk in b['coreHardening']['risks']:
 assert set(risk['work'])<=closure(risk['owner']),(risk['key'],'area coverage')
 assert set(risk.get('integrationWork',[]))<=closure('H00')
assert set(b['coreHardening']['areaGates'])=={'H01','H02','H03','H04','H05','H06'}
ready=call('ready');readykeys=sorted(k for k,i in ids.items() if any(x['id']==i for x in ready));assert readykeys==['F03.1','F04.1','H03.1','O01.4','O02.1','O02.3'],readykeys
for k,s in states.items():
 expected=s['status']=='open' and all(states[d]['status']=='closed' for d in (b['program']['required'] if k=='PROGRAM' else nodes[k]['dependsOn']))
 assert s['ready']==expected,(k,'readiness')
for snap in baseline['files']:assert digest(snap['snapshot'])==snap['sha256'],('snapshot',snap['original'])
import zipfile
archive_index={}
archives=list((root/'docs/evidence/institutional-pilot').glob('*/*snapshot.zip'))+[root/'docs/evidence/core-hardening-t1/prior-documents.zip',root/'docs/evidence/core-hardening-t1/prior-tooling.zip',root/'docs/evidence/core-hardening-t2/prior-documents.zip',root/'local-artifacts/compatibility/pre-core-20261014/source.zip']
for archive in archives:
 with zipfile.ZipFile(archive) as z:
  assert len(z.namelist())==len(set(z.namelist())),('duplicate archive member',str(archive))
  for name in z.namelist():archive_index.setdefault((name,hashlib.sha256(z.read(name)).hexdigest()),[]).append(str(archive.relative_to(root)))
archived=[];inputchecks=0
for key in completed:
 receipt=read(f'docs/evidence/institutional-pilot/{key}/receipt.json');assert receipt['status']=='passed'
 for name,sha in receipt['inputs'].items():
  inputchecks+=1
  if (root/name).is_file() and digest(name)==sha:continue
  matches=[s['snapshot'] for s in baseline['files'] if s['original']==name and s['sha256']==sha]
  matches+=archive_index.get((name,sha),[])
  assert matches,(key,'unresolved receipt provenance',name)
  archived.append({'task':key,'path':name,'sha256':sha,'archives':matches})
sourcechecks=0
for key in ['O01.1','O01.2','O01.3','F02.2']:
 source=read(f'docs/evidence/institutional-pilot/{key}/source-inventory.json')
 for name,sha in source.items():
  sourcechecks+=1
  assert ((root/name).is_file() and digest(name)==sha) or (name,sha) in archive_index,(key,'source provenance',name)
 artifact=read(f'docs/evidence/institutional-pilot/{key}/receipt.json')['artifact'];assert digest(artifact['path'])==artifact['sha256']
health=call('doctor');assert health['ok'] and health['fsck_clean']
flight=call('in-flight');assert not any(flight[k] for k in ['claims','reservations','doing','orphaned_claims','orphaned_reservations'])
paths=['README.md','docs/core-hardening-plan.md','docs/local-demo-plan.md','docs/institutional-pilot-epics.md','docs/institutional-readiness-plan.md','docs/implementation-plan.md','docs/service-distribution.md','docs/organization-reference.md','docs/upgrade-compatibility.md','docs/engineering-workload.md','docs/workloads/engineering-v1.json']
links=0
for name in paths:
 p=root/name
 for href in re.findall(r'\]\(([^)]+)\)',p.read_text()):
  if href.startswith(('https:','http:','#','app:')):continue
  target=(p.parent/href.split('#')[0]).resolve()
  if target==root/'docs/evidence/core-hardening-t2/receipt.json':continue
  assert target.exists(),(name,'broken link',href);links+=1
# Every published task table and core gate still uses the unmodified canonical graph.
guide=(root/'docs/institutional-pilot-epics.md').read_text()
for t in b['tasks']:
 assert '| '+t['key']+' | '+t['title']+' | '+(', '.join(t['dependsOn']) or 'None')+' | '+t['kind']+' |' in guide,(t['key'],'guide table')
for g in b['items']:
 if g['epic']=='EP9':assert '| '+g['key']+' | '+g['title']+' | '+', '.join(g['dependsOn'])+' |' in guide,(g['key'],'gate table')
subprocess.run(['git','diff','--check'],cwd=root,check=True,capture_output=True)
counts={'requiredEpics':sum(e['required'] for e in b['epics']),'conditionalEpics':sum(not e['required'] for e in b['epics']),'gates':len(b['items']),'tasks':len(b['tasks']),'requiredTasks':sum(t['required'] for t in b['tasks']),'conditionalTasks':sum(not t['required'] for t in b['tasks']),'taskKinds':dict(collections.Counter(t['kind'] for t in b['tasks'])),'totalNodesIncludingProgram':len(ids),'closedTasks':sum(k in m['tasks'] for k in completed),'closedGates':sum(k in m['items'] for k in completed),'closedEpics':0}
checks={'acyclic':True,'all116MoteSpecificationsParentsAndDependencies':'pass','allClosedReceiptsResolvedAgainstCurrentOrExactArchivedInputs':True,'receiptInputHashesChecked':inputchecks,'packageSourceInventoryHashesChecked':sourcechecks,'fourPackageDigestsChecked':True,'coreDependencyNodes':len(core),'localDemoDependencyNodes':len(local),'institutionalMixedExternalEngineeringBlockers':[],'allInstitutionalMixedAndAreaReviewsRemainOpen':True,'publishedTaskAndGateTablesMatch':True,'currentDocumentLinksResolved':links,'moteDoctor':'pass','activeClaimsAndReservations':0,'diffWhitespace':'pass'}
now=datetime.datetime.now(datetime.timezone.utc).isoformat()
tasks=['O03.1','F02.2']
receipts={k:f'docs/evidence/institutional-pilot/{k}/receipt.json' for k in tasks}
inputs={p:digest(p) for p in paths+list(receipts.values())+['docs/institutional-readiness-backlog.json','docs/evidence/institutional-readiness-motes.json','docs/evidence/core-hardening-t1/prior-documents.zip','docs/evidence/core-hardening-t1/prior-documents.json','docs/evidence/core-hardening-t1/prior-tooling.zip','docs/evidence/core-hardening-t1/prior-tooling.json','docs/evidence/core-hardening-t2/prior-documents.zip','docs/evidence/core-hardening-t2/prior-documents.json','docs/evidence/core-hardening-t2/verification-procedure.py']}
limits=['No independent witness is implemented; legacy recovery cannot reopen organization-enabled catalogs.','All six core reviews and H00 remain open; no retained local demo or institutional qualification.','Organization persistence and explicit-steward/exact-grant enforcement are implemented; full role/action/provenance coverage remains F03.','Performance budgets/corpus are frozen targets; no service performance pass.','Historical import replay uses retained same-host source identity; no source-independent/physical recovery claim.','Changes remain local and uncommitted; no institution contacted.']
progress={'schemaVersion':6,'at':now,'program':m['epic'],'immediateMilestone':oldprogress['immediateMilestone'],'engineeringCoordinator':{'key':'EP9','mote':m['epics']['EP9'],'plan':'docs/core-hardening-plan.md'},'counts':counts,'completed':completed,'completedThisTranche':tasks,'nextReadyEngineering':readykeys,'recommendedFirstWork':['F03.1'],'areaStatus':{k:{'mote':ids[k],'status':states[k]['status']} for k in ['H01','H02','H03','H04','H05','H06','H00']},'checks':checks,'validationReceipt':'docs/evidence/core-hardening-t2/receipt.json','trancheReceipts':receipts,'verification':read('docs/evidence/institutional-pilot/F02.2/receipt.json')['verification'] | {'policyProtocol':read('docs/evidence/institutional-pilot/O03.1/receipt.json')['observed']},'inputs':inputs,'limitations':limits}
(root/'docs/evidence/institutional-pilot-progress.json').write_text(json.dumps(progress,indent=2)+'\n')
receipt={'schemaVersion':1,'at':now,'kind':'core-hardening-T2-organization-and-durability-contract','status':'passed','completed':tasks,'taskReceipts':receipts,'counts':counts,'checks':checks,'nextReadyEngineering':readykeys,'recommendedFirstWork':['F03.1'],'verification':progress['verification'],'archivedReceiptInputs':archived,'inputs':inputs|{'docs/evidence/institutional-pilot-progress.json':digest('docs/evidence/institutional-pilot-progress.json')},'limitations':limits}
(root/'docs/evidence/core-hardening-t2/receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
(root/'docs/evidence/core-hardening-t2/mote-states.json').write_text(json.dumps(states,indent=2)+'\n')
print(json.dumps({'counts':counts,'checks':checks,'nextReadyEngineering':readykeys,'archivedInputs':len(archived)},indent=2))
