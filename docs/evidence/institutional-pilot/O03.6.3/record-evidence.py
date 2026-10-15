"""Seal only observed passing evidence and the unchanged current-authority parent review."""
from pathlib import Path
import datetime,hashlib,io,json,platform,shutil,sys,zipfile
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[4];out=Path(__file__).resolve().parent
matrix=out/(sys.argv[1] if len(sys.argv)>1 else 'process-attempt-04')
assert not (out/'receipt.json').exists()
def read(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(p,x):p.write_text(json.dumps(x,indent=2)+'\n')
release=root/'target/service-release-o0363-final-20261015';build=read(release/'build-receipt.json');artifact=release/build['artifact']
assert sha(artifact)==build['sha256']
checks={name:read(out/'final-artifact'/name/'receipt.json') for name in ['package','witness-package','catalog-package','historical-upgrade']}
finalization=out/'finalization-attempt-03'
checks['matrix']=read(matrix/'receipt.json');checks['finalization']=read(finalization/'receipt.json')
for name,r in checks.items():assert r['status']=='passed' and r['artifactSha256']==build['sha256'],name
assert len(checks['matrix']['cases'])==32
procedure=out/(matrix.name+'-procedure.py')
assert sha(procedure)==sha(root/'scripts/check-policy-processes.py'),'matrix controller changed after execution'
harness=out/'qualification-harness.jar'
for r in [checks['matrix'],checks['finalization']]:assert r['harnessSha256']==sha(harness)
for path,digest in read(out/'harness-inputs.json').items():assert sha(root/path)==digest,path
assert len(checks['package']['checks'])==11 and len(checks['witness-package']['checks'])==7 and len(checks['catalog-package']['checks'])==10
cases={r['case']:r for receipt in [checks['matrix'],checks['finalization']] for r in receipt['cases']}
expected={'setup','exact-retry-and-adversarial-wire','pending-restriction-no-overtaking','lost-primary-unresolved-prepare',
 'acknowledged-primary-loss','barrier-running-service-and-permit-drain','empty-witness-refusal','altered-client-trust','packaged-http-barrier-fencing','bounded-finalization-drain'}
expected|={'primary-'+s for s in ['before-prepare','before-local-commit','after-local-commit','lost-local-response','before-receipt-insert','before-receipt-commit','after-receipt-commit','lost-receipt-response']}
expected|={'witness-'+k.lower()+'-'+s for k in ['ENROLL','PREPARE','COMMIT','ABORT','RECOVERY_BARRIER'] for s in ['record','head']}
expected|={'store-'+k for k in ['gap','reorder','payload','capability','foreign']}
assert set(cases)==expected and len(cases)==33
assert all(c['status']=='passed' for c in cases.values())
assert cases['barrier-running-service-and-permit-drain']['drainSeconds']<=5.25
assert cases['packaged-http-barrier-fencing']['afterStatus']==503
assert cases['bounded-finalization-drain']['drainSeconds']<=5.25
assert not cases['bounded-finalization-drain']['jdbcFaultWrapper']
assert checks['finalization']['procedureSha256']==sha(out/'finalization-procedure.py')
assert checks['finalization']['fixtureProcedureSha256']==sha(procedure)
summary=read(out/'test-summary.json');assert summary['total']==250
for name,count in summary['suites'].items():
 s=ET.parse(out/('TEST-'+name+'.xml')).getroot()
 assert int(s.attrib['tests'])==count and all(int(s.attrib.get(k,0))==0 for k in ['failures','errors','skipped'])
assert 'Tests unsuccessful' not in (out/'regression-final.log').read_text()
with zipfile.ZipFile(artifact) as z:
 source=next(n for n in z.namelist() if n.endswith('/metadata/source.json'))
 (out/'source-inventory.json').write_bytes(z.read(source))
 for n in z.namelist():
  if n.endswith('.jar'):
   with zipfile.ZipFile(io.BytesIO(z.read(n))) as jar:
    assert not any('PolicyProcessQualification' in p or 'PolicyProcessWitness' in p for p in jar.namelist()),n
inventory=read(out/'source-inventory.json')
for path,digest in inventory.items():assert sha(root/path)==digest,('artifact source changed',path)
for name in ['build-receipt.json','manifest.json']:
 shutil.copyfile(release/name,out/('artifact-manifest.json' if name=='manifest.json' else name))
before=read(out/'before.json');unchanged=[];changed=[]
for path,digest in before.items():
 if path.startswith('modules/') and '/src/main/' in path:
  if sha(root/path)==digest:unchanged.append(path)
  else:changed.append(path)
assert changed==['modules/postgres/src/main/scala/sanctity/postgres/PolicyBridge.scala'],changed
for path,v in read(root/'docs/evidence/institutional-pilot/F03.2/historical-source-before.json').items():
 p=root/path;s=p.stat()
 assert sha(p)==v['sha256'] and s.st_ino==v['inode'] and s.st_mtime_ns==v['mtimeNs'],path
write(out/'unchanged-history.json',{'status':'passed','runtimeSourcePaths':unchanged,'changedRuntimeSourcePaths':changed,'change':'Enforce remaining admission/finalization/resolution budgets with PostgreSQL transaction_timeout.',
 'migrations':'V001 through V010 unchanged from the pre-slice snapshot',
 'historicalFixture':'Original F03.2 oracle/source bytes, inode and timestamps unchanged',
 'observedUpgrade':checks['historical-upgrade']['observed']})
backlog=read(root/'docs/institutional-readiness-backlog.json')
parent=next(t for t in backlog['tasks'] if t['key']=='O03.6')
with zipfile.ZipFile(out/'before.zip') as z:old= json.loads(z.read('docs/institutional-readiness-backlog.json'))
assert parent['acceptance']==next(t for t in old['tasks'] if t['key']=='O03.6')['acceptance']
parent_evidence=[
 'The unchanged policy-transactions inventory names nineteen current producer kinds. PolicyIntegrationSuite exercises all nineteen against PostgreSQL/TLS, atomic event rollback, offboarding and local authorization; the 102 current integration regressions pass. O03.6.2 defines the atomic PolicySql boundary and credential operation identity.',
 'Signed TLS journal/store vectors and current regression pass. Separate primary/witness/operator UIDs, protected volumes, cross-mounted permission denials, separate SQL runtime role and independently held key bundles are observed. Restrictive enrollment retains revoked grants/credentials, inactive principals, lease status, immutable history and scientific identity; ENROLL record/head crashes resume the exact retained checkpoint.',
 'Eight primary transaction/receipt boundaries and PREPARE/COMMIT/ABORT record/head process kills demonstrate no false success, exact terminal reconciliation, live rollback proof and conservative restarted absence. Pending local revocation denies access, outage prevents overtaking, and a successor follows predecessor COMMIT. Actual primary database deletion retains acknowledged state or leaves unresolved PREPARE closed.',
 'Fresh signed recovery barriers survive record/head kills and exact retries. The running admission client and actual packaged HTTP server are fenced with the local database epoch unchanged; restarted old service fails. The held backend transaction drains within the five-second permit plus recorded 0.25-second polling tolerance and its effects roll back. A separate actual stalled SQL acknowledgement insertion qualifies the unwrapped production finalizer: it drains within its own server-enforced five-second deadline plus recorded scheduling tolerance, returns pending and preserves the local restriction for exact resolution. Current authorization regression remains passing.',
 'The versioned capability inventory and redacted diagnostics are exercised by current source and package checks. Unknown capability/producer, valid alternate signing pin, foreign installation, empty witness and incompatible state refuse admission. Existing local event identities remain LocalOnly; pending/unknown/durable responses remain explicit. Legacy organization recovery stays blocked.',
 'Separate primary/witness JVMs run the SQL/fsync interruption matrix; exact/changed/reordered requests, gaps, tampering, signed unknown capability, foreign installation/trust, durable retries and stale writers are observed. Artifact/harness/source/configuration identities are bound. Same-host isolation does not qualify host/disk loss; identity/session and institutional obligations remain open.'
]
matrix_receipt=str((matrix/'receipt.json').relative_to(root))
def report(name):return str((out/('TEST-'+name+'.xml')).relative_to(root))
parent_paths=[
 ['docs/policy-transactions.md',report('sanctity.postgres.PolicyIntegrationSuite'),report('sanctity.postgres.CurrentAuthoritySuite'),'docs/evidence/institutional-pilot/O03.6.2/receipt.json'],
 [matrix_receipt,report('sanctity.policy.PolicyJournalSuite'),report('sanctity.policy.PolicyTransportSuite'),'docs/evidence/institutional-pilot/O03.6.1/receipt.json'],
 [matrix_receipt,report('sanctity.postgres.PolicyIntegrationSuite'),report('sanctity.postgres.PolicyReceiptCompatibilitySuite')],
 [matrix_receipt,str((finalization/'receipt.json').relative_to(root)),report('sanctity.postgres.PolicyIntegrationSuite'),report('sanctity.postgres.CurrentAuthoritySuite'),'modules/postgres/src/main/scala/sanctity/postgres/PolicyBridge.scala'],
 [matrix_receipt,str((out/'final-artifact/catalog-package/receipt.json').relative_to(root)),str((out/'final-artifact/witness-package/receipt.json').relative_to(root)),report('sanctity.policy.PolicyWireSuite'),'docs/policy-catalog.md'],
 [matrix_receipt,str((out/'process-attempt-04-procedure.py').relative_to(root)),str((out/'harness-inputs.json').relative_to(root))]
]
assert all((root/path).is_file() for paths in parent_paths for path in paths)
review={'schemaVersion':1,'status':'passed','task':'O03.6','scope':'Only currently implemented authority; all six original criteria retained verbatim',
 'criteria':[{'criterion':c,'status':'passed','evidence':e,'evidencePaths':paths} for c,e,paths in zip(parent['acceptance'],parent_evidence,parent_paths,strict=True)],
 'matrix':str(matrix.relative_to(root)),'packagedApi':str(matrix.relative_to(root)),
 'unchangedAcceptance':True,'openObligations':['H03.1','O03.7','O03.8','O03.2','O03.3','SITE01','F03.4','H04.2']}
write(out/'parent-acceptance-review.json',review)
paths=set(inventory)
for module in ['kernel','application','http','postgres','service','integration','app']:
 paths.update(str(p.relative_to(root)) for p in (root/'modules'/module/'src/test').rglob('*') if p.is_file())
paths.update(['docs/policy-process-qualification.md','docs/policy-catalog.md','docs/policy-witness.md','docs/policy-transactions.md',
 'docs/decisions/004-policy-durability.md','docs/core-hardening-plan.md','docs/local-demo-plan.md','docs/implementation-plan.md',
 'docs/institutional-pilot-epics.md','docs/institutional-readiness-plan.md','docs/institutional-readiness-backlog.json','docs/evidence/institutional-readiness-motes.json',
 'scripts/check-policy-processes.py','scripts/check-policy-catalog-package.py','scripts/check-policy-witness-package.py',
 'scripts/check-service-package.py','scripts/check-upgrade-compatibility.py'])
paths.update(str(p.relative_to(root)) for p in out.glob('*.py'))
inputs={p:sha(root/p) for p in sorted(paths)}
write(out/'verification-inputs.json',inputs)
with zipfile.ZipFile(out/'source-snapshot.zip','x',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(paths):z.write(root/p,p)
for p in out.rglob('*'):
 if p.is_file() and '__pycache__' not in p.parts:inputs[str(p.relative_to(root))]=sha(p)
criteria=next(t for t in backlog['tasks'] if t['key']=='O03.6.3')['acceptance']
evidence=[parent_evidence[2]+' '+parent_evidence[5],parent_evidence[1]+' '+parent_evidence[4],parent_evidence[3],
 'The current artifact passes 250 authority/service/reference tests, 28 packaged checks, the retained historical upgrade and 33 distinct integrated process cases. The actual HTTP service classpath contains artifact JARs only; the test harness is absent from every service JAR.',
 'parent-acceptance-review.json maps all six unchanged parent criteria to observed source, artifact and fault outcomes. Backup, restore/replay, complete identity/session, continuous delivery and institutional work remain open.']
verification={'distinctTests':250,'suites':summary['suites'],'distinctProcessCases':33,'caseNames':sorted(cases),
 'primaryBoundaries':8,'witnessRecordAndHeadBoundaries':10,'packagedServiceChecks':11,'packagedWitnessChecks':7,'packagedCatalogChecks':10,
 'historicalUpgrade':checks['historical-upgrade']['observed'],'drainSeconds':cases['barrier-running-service-and-permit-drain']['drainSeconds'],
 'finalizationDrainSeconds':cases['bounded-finalization-drain']['drainSeconds'],'finalizationMeasurement':'PostgreSQL transaction-timeout/disconnection timestamps; excludes Docker polling latency','finalizationSchedulingToleranceSeconds':.25,'pollingToleranceSeconds':.25,'packagedHttpBeforeAndAfterBarrier':[200,503],'failures':0,'errors':0,'skipped':0,
 'counting':'Latest passing regression reports; 33 distinct process case names across the current-artifact matrix and finalizer runs. Repeated setup is counted once. Initial failed fixture attempts are retained, not counted as passes.'}
limitations=['Same Docker host/physical disk; no off-host persistence, physical power-loss or institutional approval claim.',
 'Independent policy mode requires PostgreSQL 17 transaction_timeout; older versions refuse independent admission.',
 'No barrier release, policy replay or organization restore: H03.1 and O03.7/O03.8 remain required.',
 'Current nineteen authority kinds only; full identity/session/lifecycle and institutional qualification remain O03.2/O03.3/SITE01.',
 'F03.4 retains continuous byte-stream and external-worker checks; this qualifies SQL admission and actual API authentication.',
 'H04.2 retains scale/performance qualification; five-second SQL permits and the bounded finalization phase keep their documented availability limits.',
 'Synthetic keys and data; the Docker/database operator is trusted. Test-only JDBC/after-force controls are separate from the service artifact.']
write(out/'receipt.json',{'schemaVersion':1,'task':'O03.6.3','mote':'bd-01M4XWXRBJ36AWBKR8ZEDXNJK0','status':'passed',
 'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Integrated current-authority process interruption, storage isolation, fencing and exact-outcome qualification, including repair of server-side phase deadlines.',
 'artifact':{'path':str(artifact.relative_to(root)),'sha256':build['sha256'],'bytes':build['bytes']},
 'harness':{'path':str(harness.relative_to(root)),'sha256':sha(harness),'excludedFromServiceArtifact':True},
 'acceptance':[{'criterion':c,'status':'passed','evidence':e} for c,e in zip(criteria,evidence,strict=True)],
 'parentReview':str((out/'parent-acceptance-review.json').relative_to(root)),'verification':verification,
 'processEvidence':[str((p/'receipt.json').relative_to(root)) for p in [matrix,finalization]],
 'commands':[['python3','scripts/package-service.py','--output',str(release.relative_to(root))],
 ['python3','docs/evidence/institutional-pilot/O03.6.3/regression-procedure.py'],
 ['python3','docs/evidence/institutional-pilot/O03.6.3/package-procedure.py'],
 ['python3','scripts/check-policy-processes.py','--artifact',str(artifact.relative_to(root)),'--harness',str(harness.relative_to(root)),'--output',str(matrix.relative_to(root))],
 ['python3','docs/evidence/institutional-pilot/O03.6.3/finalization-procedure.py']],
 'matrixProcedureAtExecution':str((out/(matrix.name+'-procedure.py')).relative_to(root)),
 'environment':{'host':platform.platform(),'regression':read(out/'environment.json'),
  'processConfigurations':[checks['matrix']['configuration'],checks['finalization']['configuration']]},'inputs':inputs,'limitations':limitations})
print(json.dumps({'status':'passed','tests':250,'distinctProcessCases':33,'artifactSha256':build['sha256'],'parentCriteriaPassed':6}))
