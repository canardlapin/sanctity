"""Retain the completed standalone component; parent durability remains unqualified."""
import datetime
import hashlib
import json
from pathlib import Path
import platform
import shutil
import zipfile
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[4]
out=Path(__file__).resolve().parent
assert not (out/'receipt.json').exists(), 'do not overwrite accepted evidence'
package=root/'target/service-release-o0361-final-20261014'
smoke=root/'target/service-smoke-o0361-20261014'
witness=root/'target/witness-smoke-o0361-20261014'
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path): return json.loads(path.read_text())
def write(path,value): path.write_text(json.dumps(value,indent=2)+'\n')
build=read(package/'build-receipt.json'); ordinary=read(smoke/'receipt.json'); independent=read(witness/'receipt.json')
assert ordinary['status']==independent['status']=='passed'
artifact=package/build['artifact']
assert sha(artifact)==build['sha256']==ordinary['artifactSha256']==independent['artifactSha256']
assert len(ordinary['checks'])==11 and len(independent['checks'])==7
summary=read(out/'test-summary.json'); assert summary['total']==77
assert summary['suites']=={'sanctity.policy.PolicyJournalSuite':17,'sanctity.policy.PolicyTransportSuite':13,
 'sanctity.policy.PolicyWireSuite':19,'sanctity.postgres.PersistenceSuite':19,'sanctity.app.ServiceConfigurationSuite':9}
for name,count in summary['suites'].items():
    suite=ET.parse(out/('TEST-'+name+'.xml')).getroot()
    assert int(suite.attrib['tests'])==count and all(int(suite.attrib.get(k,0))==0 for k in ['failures','errors','skipped'])
for source,target in [(package/'build-receipt.json','build-receipt.json'),(package/'manifest.json','artifact-manifest.json'),
    (smoke/'receipt.json','service-smoke-receipt.json'),(smoke/'service.log','service-smoke.log'),
    (witness/'receipt.json','witness-smoke-receipt.json'),(witness/'service.log','witness-service.log')]: shutil.copyfile(source,out/target)
with zipfile.ZipFile(artifact) as z:
    source=next(n for n in z.namelist() if n.endswith('/metadata/source.json'))
    (out/'source-inventory.json').write_bytes(z.read(source))
source_inventory=read(out/'source-inventory.json')
for path,digest in source_inventory.items(): assert sha(root/path)==digest,('artifact source changed',path)
prior=read(root/'docs/evidence/institutional-pilot/F03.2/source-inventory.json')
unchanged=[]
for path,digest in prior.items():
    if path.startswith('modules/') and path!='modules/service/src/main/scala/sanctity/app/ServiceMain.scala':
        assert sha(root/path)==digest,('existing implementation changed',path); unchanged.append(path)
write(out/'unchanged-existing-implementation.json',{'baseline':'F03.2','paths':unchanged,
 'explanation':'New isolated policy package and explicit service dispatch only. All existing catalog, credential, organization, migration, recovery and scientific implementation inputs retain their F03.2 bytes. Full historical SQL/authority qualification is not rerun or claimed here.'})
paths=set(source_inventory)
for module in ['postgres','service']:
    paths.update(str(p.relative_to(root)) for p in (root/'modules'/module/'src/test').rglob('*') if p.is_file())
paths.update(['docs/policy-witness.md','docs/policy-transactions.md','docs/decisions/004-policy-durability.md',
 'docs/core-hardening-plan.md','docs/institutional-pilot-epics.md','docs/implementation-plan.md','docs/local-demo-plan.md',
 'docs/institutional-readiness-plan.md','docs/institutional-readiness-backlog.json','docs/evidence/institutional-readiness-motes.json',
 'scripts/check-policy-witness-package.py','scripts/check-service-package.py',
 'docs/evidence/institutional-pilot/O03.6.1/regression-procedure.py','docs/evidence/institutional-pilot/O03.6.1/qualification-procedure.py',
 'docs/evidence/institutional-pilot/O03.6.1/record-evidence.py'])
inputs={p:sha(root/p) for p in sorted(paths)}
write(out/'verification-inputs.json',inputs)
with zipfile.ZipFile(out/'source-snapshot.zip','x',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(paths):z.write(root/p,p)
for p in sorted(out.iterdir()):
    if p.is_file(): inputs[str(p.relative_to(root))]=sha(p)
backlog=read(root/'docs/institutional-readiness-backlog.json')
criteria=next(t['acceptance'] for t in backlog['tasks'] if t['key']=='O03.6.1')
evidence=[
 '19 wire tests verify two RFC8032 vectors, RFC8785 UTF-16 sorting/control escaping, exact payload/signature binding, fresh challenge binding and malformed/profile/overflow rejection; 17 journal tests include message headroom bounds and current capability refusal.',
 'Protected CLI package checks exercise explicit init, unsafe path/mode/unknown configuration refusal, loopback TLS1.3 and redacted diagnostics. The 13 transport tests use separate JVMs and private stores with separate pinned writer/recovery certificates; unauthorized/missing identity, wrong hostname/signing key and the two-second whole-request deadline fail closed.',
 'Journal tests verify contiguous state transitions and exact retries, file locks, forward-only crash reconciliation, missing/tampered/foreign/unknown history and poisoned live stores. Signed journal and signed head are forced before acknowledgment; an empty store remains unenrolled.',
 'Separate JVM is killed after record force and head force for PREPARE, COMMIT, ABORT and RECOVERY_BARRIER (eight fault cases). Restart preserves the exact durable outcome without duplicate effects. Concurrent clients cannot branch/overtake; barriers fence the writer and retain pending intent. This is witness-component evidence, not a SQL commit-boundary matrix.',
 'The capability hash closes unsupported producers, diagnostics omit policy/identity/secrets, and docs/policy-witness.md states key-rotation, checkpoint semantics, catalog integration, admission/drain, restore and failure-domain limits. Existing PostgreSQL/recovery source bytes are unchanged; all eleven packaged service checks pass. Parent O03.6 and its integration/qualification children stay open.'
]
verification={'distinctTests':77,'witnessTests':49,'wireTests':19,'journalTests':17,'separateProcessTransportTests':13,
 'processKillBoundaryCases':8,'persistenceRegressionTests':19,'serviceConfigurationTests':9,
 'packagedWitnessChecks':7,'packagedServiceChecks':11,'failures':0,'errors':0,'skipped':0,
 'sqlWitnessIntegration':'not implemented or qualified; O03.6.2/O03.6.3 remain open',
 'historicalCompatibility':'existing implementation bytes unchanged; retained F03.2 baseline, no new historical-upgrade claim'}
limitations=[
 'Catalog receipts remain LocalOnly. This witness is not yet connected to PolicySql.commit; complete fenced enrollment, pending acknowledgments and startup/runtime admission remain O03.6.2.',
 'Parent O03.6 and the integrated failure matrix O03.6.3 remain open. Organization-aware restore O03.7/O03.8 is unavailable; legacy recovery still refuses initialized catalogs.',
 'Separate local processes and synthetic TLS identities prove component separation, file protection and restart behavior only. No separate operating-system account, physical power-loss, separate disk or host-loss qualification.',
 'Checkpoint bytes are opaque at this boundary; the catalog adapter must prove complete current authority/tombstones/history and reject unsupported nondefault eligibility/lifecycle state. Synthetic checkpoints do not qualify real enrollment.',
 'Barrier denies subsequent witness requests; catalog permit expiry, bounded drain and independent old-service fencing are not yet integrated.',
 'No key rotation or barrier release/reopen endpoint. Loss/rollback of all trusted continuity has no automatic recovery path.',
 'Full-history in-memory replay and 1 MiB event / 4 MiB checkpoint / 8 MiB wire limits are a correctness baseline; larger workloads require explicit qualified extension.',
 'No browser identity/session/lifecycle producer integration or institutional acceptance is claimed.'
]
write(out/'receipt.json',{'schemaVersion':1,'task':'O03.6.1','mote':'bd-01M4XWXPN5CTR71KYVTPCPT2X8','status':'passed',
 'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Standalone signed policy witness, separate-process TLS/fault evidence and packaged command; catalog integration remains open.',
 'parentAcceptance':'open; catalog integration and integrated qualification remain required',
 'artifact':{'path':str(artifact.relative_to(root)),'sha256':build['sha256'],'bytes':build['bytes']},
 'acceptance':[{'criterion':c,'status':'passed','evidence':e} for c,e in zip(criteria,evidence,strict=True)],
 'verification':verification,'commands':[summary['command'],['python3','docs/evidence/institutional-pilot/O03.6.1/qualification-procedure.py']],
 'environment':{'platform':platform.platform(),'java':build['java'],'scala':'3.3.8','sbt':'1.10.5','fixture':'Generated disposable self-signed EC TLS identities, synthetic checkpoint/revocation intent, Ed25519 JDK vectors; separate witness JVMs and stores, no catalog in component tests.',
 'packageConfiguration':independent['configuration']},'inputs':inputs,'limitations':limitations})
print(json.dumps({'status':'passed','tests':77,'artifactSha256':build['sha256'],'sourceInputs':len(paths)}))
