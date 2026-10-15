"""Seal catalog integration evidence after tests, package qualification and unchanged-history checks."""
from pathlib import Path
import datetime, hashlib, json, platform, shutil, zipfile
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[4]
out=Path(__file__).resolve().parent
assert not (out/'receipt.json').exists(), 'accepted evidence is immutable'
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p): return json.loads(p.read_text())
def write(p,value): p.write_text(json.dumps(value,indent=2)+'\n')
release=root/'target/service-release-o0362-20261014'
build=read(release/'build-receipt.json')
artifact=release/build['artifact']
assert sha(artifact)==build['sha256']
packages={name:read(out/name/'receipt.json') for name in ['package','witness-package','catalog-package','historical-upgrade']}
for name,result in packages.items():
    assert result['status']=='passed' and result['artifactSha256']==build['sha256'], name
summary=read(out/'test-summary.json')
assert summary['suites']['sanctity.postgres.PolicyIntegrationSuite']==21
assert summary['suites']['sanctity.postgres.PolicyReceiptCompatibilitySuite']==3
for name,count in summary['suites'].items():
    suite=ET.parse(out/('TEST-'+name+'.xml')).getroot()
    assert int(suite.attrib['tests'])==count and all(int(suite.attrib.get(k,0))==0 for k in ['failures','errors','skipped'])
assert 'Tests unsuccessful' not in (out/'regression-final.log').read_text()
assert 'Tests unsuccessful' not in (out/'regression-outcomes.log').read_text()
for name in ['build-receipt.json','manifest.json']:
    shutil.copyfile(release/name,out/('artifact-manifest.json' if name=='manifest.json' else name))
with zipfile.ZipFile(artifact) as z:
    source=next(n for n in z.namelist() if n.endswith('/metadata/source.json'))
    (out/'source-inventory.json').write_bytes(z.read(source))
source_inventory=read(out/'source-inventory.json')
for p,digest in source_inventory.items(): assert sha(root/p)==digest,('artifact input changed',p)
before=read(out/'before.json')
unchanged=[]
for p,digest in before.items():
    if '/src/main/resources/sanctity/postgres/V00' in p or p.startswith(('modules/bids/','modules/processing-mri/','modules/application/','modules/objects/')):
        assert sha(root/p)==digest,('protected existing input changed',p)
        unchanged.append(p)
for p,expected in read(root/'docs/evidence/institutional-pilot/F03.2/historical-source-before.json').items():
    source=root/p
    assert sha(source)==expected['sha256'] and source.stat().st_ino==expected['inode'] and source.stat().st_mtime_ns==expected['mtimeNs'],p
write(out/'unchanged-history.json',{'status':'passed','unchangedSourcePaths':unchanged,
    'migrations':'V001–V009 byte-identical to the pre-slice snapshot; only V010 added',
    'historicalOracle':'retained F03.2 oracle/source inode, timestamp and digest unchanged; no regeneration',
    'upgrade':packages['historical-upgrade']['observed']})
paths=set(source_inventory)
for module in ['kernel','application','http','postgres','service','integration','app']:
    paths.update(str(p.relative_to(root)) for p in (root/'modules'/module/'src/test').rglob('*') if p.is_file())
paths.update(['docs/policy-catalog.md','docs/policy-witness.md','docs/policy-transactions.md','docs/decisions/004-policy-durability.md',
    'docs/core-hardening-plan.md','docs/local-demo-plan.md','docs/implementation-plan.md','docs/institutional-pilot-epics.md',
    'docs/institutional-readiness-backlog.json','docs/evidence/institutional-readiness-motes.json',
    'scripts/check-policy-catalog-package.py','scripts/check-policy-witness-package.py','scripts/check-service-package.py',
    'scripts/check-upgrade-compatibility.py'])
paths.update(str(p.relative_to(root)) for p in out.glob('*-procedure.py'))
paths.add(str(Path(__file__).resolve().relative_to(root)))
inputs={p:sha(root/p) for p in sorted(paths)}
write(out/'verification-inputs.json',inputs)
with zipfile.ZipFile(out/'source-snapshot.zip','x',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(paths): z.write(root/p,p)
for p in sorted(out.rglob('*')):
    if p.is_file() and '__pycache__' not in p.parts:
        inputs[str(p.relative_to(root))]=sha(p)
backlog=read(root/'docs/institutional-readiness-backlog.json')
criteria=next(t['acceptance'] for t in backlog['tasks'] if t['key']=='O03.6.2')
evidence=[
 'Closed maintenance enrollment captures all current authority, ended/revoked state, installation/schema identity, immutable history and redacted credential/lease status. Existing runtime SQL identities are retired and cannot be regranted. Integration tests exercise complete enrollment, unsupported eligibility/lifecycle/history, size refusal, lost enrollment response and historical v1 continuity.',
 'Additive V010 plus PolicySql.commit and the connection-owned transaction strategy implement bounded verified PREPARE, atomic effects/event/Pending receipt, and verified COMMIT before success. The nineteen supported mutation kinds are exercised against real PostgreSQL and TLS witness; old migrations and original scientific implementation/history remain unchanged.',
 'Observed failures include lost PREPARE acknowledgement, local COMMIT response loss, lost remote COMMIT acknowledgement, live event-insert rollback/ABORT, pending restrictive revocation, no overtaking and retained credential issuance retries. Resolution uses exact event/payload under a live exclusive lock; restarted absence stays closed.',
 'Protected writer/recovery configuration, pinned signing public key, fresh CURRENT admission, five-second transaction permits and bounded acknowledgement/abort phases are integrated. Tests prove running-service barrier refusal with unchanged local epoch, expired connection eviction, stale head/missing trust refusal, old SQL identity retirement and fresh least-privilege runtime mutation.',
 'Organization receipts, credential results and HTTP grant/revoke headers explicitly distinguish LocalOnly/Durable; pending and unknown errors carry separate operation codes. Real HTTP failure tests and packaged CLI/API checks verify those outcomes. Redacted readiness, a complete operator guide and unchanged legacy organization-recovery refusal are retained. O03.6.3, the parent and O03.7/O03.8 stay open.'
]
verification={'distinctTests':summary['total'],'suites':summary['suites'],'policyIntegrationTests':21,
 'supportedMutationKinds':19,'packagedServiceChecks':len(packages['package']['checks']),
 'packagedWitnessChecks':len(packages['witness-package']['checks']),
 'packagedCatalogChecks':len(packages['catalog-package']['checks']),
 'historicalUpgrade':packages['historical-upgrade']['observed'],
 'failures':0,'errors':0,'skipped':0,
 'strategy':'Complete PostgreSQL regression and witness/service/reference tests, followed by focused policy/HTTP/current-authority tests for explicit response status additions. Latest passing XML reports retained; no duplicate executions counted as distinct tests.'}
limitations=[
 'O03.6.3 integrated crash/fencing qualification and unchanged O03.6 parent acceptance remain open. Organization replay and restore O03.7/O03.8 remain unavailable; legacy recovery still refuses initialized catalogs.',
 'Local synthetic PostgreSQL/TLS/process evidence does not qualify separate OS accounts, off-host persistence, physical power loss, institutional identity or institutional deployment approval.',
 'No key rotation, barrier release or heuristic resolution of a prepared event absent from a restarted/restored catalog. Uncertain absence stays closed.',
 'Enrollment and runtime admission refuse nondefault guest eligibility/lifecycle and unsupported producers. Full identity/session/sponsorship/retention/hold work remains open.',
 'Full-state authority/checkpoint loading and acknowledgement verification are correctness baselines. The 1 MiB event, 4 MiB checkpoint and 8 MiB wire limits refuse oversize state; institutional scale/performance remains unqualified.',
 'SQL admission is bounded; complete byte-stream and external-worker heartbeat coverage remains F03.4. Privileged database operators are trusted; the service requires a fresh ordinary SQL login after enrollment.'
]
write(out/'receipt.json',{'schemaVersion':1,'task':'O03.6.2','mote':'bd-01M4XWXQE6BXNAJFHHKE96HS30','status':'passed',
 'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
 'scope':'Current-catalog fenced enrollment, synchronous policy acknowledgement, exact outcome resolution, protected trust and bounded SQL admission; local engineering qualification.',
 'parentAcceptance':'open; integrated O03.6.3 qualification and parent review remain required',
 'artifact':{'path':str(artifact.relative_to(root)),'sha256':build['sha256'],'bytes':build['bytes']},
 'acceptance':[{'criterion':c,'status':'passed','evidence':e} for c,e in zip(criteria,evidence,strict=True)],
 'verification':verification,'commands':[
  ['python3','docs/evidence/institutional-pilot/O03.6.2/regression-procedure.py','integration/test','service/test','postgres/test','app/testOnly sanctity.app.OrganizationAuthoritySuite sanctity.app.OrganizationSuite'],
  ['python3','docs/evidence/institutional-pilot/O03.6.2/regression-procedure.py','integration/testOnly sanctity.postgres.PolicyIntegrationSuite sanctity.postgres.HttpSuite sanctity.postgres.CurrentAuthoritySuite sanctity.postgres.ServicePrivilegesSuite sanctity.postgres.TransactionSuite sanctity.postgres.OrganizationPersistenceSuite','service/test'],
  ['python3','docs/evidence/institutional-pilot/O03.6.2/qualification-procedure.py']],
 'environment':{'platform':platform.platform(),'java':build['java'],'scala':'3.3.8','sbt':'1.10.5','postgres':read(out/'environment.json'),
 'fixture':'Disposable PostgreSQL and real loopback mutual TLS witness; synthetic keys/principals; process and fault boundaries as identified in the individual suites.'},
 'inputs':inputs,'limitations':limitations})
print(json.dumps({'status':'passed','tests':summary['total'],'artifactSha256':build['sha256'],'sourceInputs':len(paths)}))
