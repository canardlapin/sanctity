"""Retain a source-bound F03.2 receipt only after qualification has passed."""
import datetime
import hashlib
import json
from pathlib import Path
import platform
import shutil
import zipfile

root = Path(__file__).resolve().parents[4]
out = root / 'docs/evidence/institutional-pilot/F03.2'
assert not (out / 'receipt.json').exists(), 'do not overwrite accepted evidence'
package = root / 'target/service-release-f032-20261014'
smoke = root / 'target/service-smoke-f032-20261014'
compat = root / 'target/compatibility-f032-current'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def read(path):
    return json.loads(path.read_text())

def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')

build = read(package / 'build-receipt.json')
smoke_result = read(smoke / 'receipt.json')
compat_result = read(compat / 'receipt.json')
assert smoke_result['status'] == compat_result['status'] == 'passed'
artifact = package / build['artifact']
assert digest(artifact) == build['sha256'] == smoke_result['artifactSha256'] == compat_result['artifactSha256']
assert len(smoke_result['checks']) == 11
assert compat_result['oracleSha256'] == '9fa0e0e2eab7d5fe13b12802cec35de6e07ff7ec59a7f4c3db7c1827ef1dfd4d'
assert compat_result['fixtureManifestSha256'] == '4c3175c029633999c779d9d12ea4b829f8f09821af1d4fc7b8e429deeab5b5d1'
for name, expected in read(out / 'historical-source-before.json').items():
    p = root / name
    assert digest(p) == expected['sha256'] and p.stat().st_ino == expected['inode'] and p.stat().st_mtime_ns == expected['mtimeNs']
counts = {name: int(count) for name, count in read(out / 'test-summary.json').items()}
assert len(counts) == 13 and sum(counts.values()) == 351
assert counts['sanctity.postgres.CurrentAuthoritySuite'] == 44
assert counts['sanctity.postgres.PolicyReceiptCompatibilitySuite'] == 2
for source, target in [(package/'build-receipt.json','build-receipt.json'), (package/'manifest.json','artifact-manifest.json'),
                       (smoke/'receipt.json','smoke-receipt.json'), (smoke/'service.log','service.log'),
                       (compat/'receipt.json','compatibility-current.json'), (compat/'service.log','compatibility-service.log')]:
    shutil.copyfile(source, out / target)
with zipfile.ZipFile(artifact) as z:
    source_name = next(n for n in z.namelist() if n.endswith('/metadata/source.json'))
    (out / 'source-inventory.json').write_bytes(z.read(source_name))
for name, expected in read(out / 'source-inventory.json').items():
    assert digest(root / name) == expected, ('artifact source changed', name)
# Existing checked migrations are immutable; V009 is the only new migration.
previous = read(root / 'docs/evidence/institutional-pilot/F02.2/receipt.json')
for name, expected in previous['inputs'].items():
    if '/postgres/V00' in name and name.endswith('.sql'):
        assert digest(root / name) == expected, ('old migration changed', name)
source_paths = set(read(out / 'source-inventory.json'))
source_paths.update(str(p.relative_to(root)) for p in (root/'modules').glob('*/src/**/*') if p.is_file())
source_paths.update(str(p.relative_to(root)) for p in (root/'project').iterdir() if p.is_file())
source_paths.update(['build.sbt','docs/policy-transactions.md','docs/organization-reference.md',
    'docs/core-hardening-plan.md','docs/institutional-pilot-epics.md','docs/implementation-plan.md','docs/local-demo-plan.md',
    'docs/institutional-readiness-backlog.json','docs/evidence/institutional-readiness-motes.json',
    'docs/decisions/001-institutional-organization.md','docs/decisions/002-institutional-authority.md',
    'docs/decisions/003-institutional-lifecycle.md','docs/decisions/004-policy-durability.md',
    'docs/decisions/institutional-authority-cases.json','scripts/check-service-package.py',
    'scripts/check-upgrade-compatibility.py','scripts/package-service.py',
    'docs/evidence/institutional-pilot/F03.2/regression-procedure.py',
    'docs/evidence/institutional-pilot/F03.2/qualification-procedure.py',
    'docs/evidence/institutional-pilot/F03.2/record-evidence.py'])
inputs = {name: digest(root/name) for name in sorted(source_paths)}
write(out/'verification-inputs.json', inputs)
with zipfile.ZipFile(out/'source-snapshot.zip','w',zipfile.ZIP_DEFLATED) as z:
    for name in sorted(source_paths): z.write(root/name,name)
for p in sorted(out.iterdir()):
    if p.is_file(): inputs[str(p.relative_to(root))] = digest(p)
criteria = next(t for t in read(root/'docs/institutional-readiness-backlog.json')['tasks'] if t['key']=='F03.2')['acceptance']
evidence = [
    '44 current-authority tests include the unchanged 38-case SQL/reference matrix, inherited roles, named dependency loss and independent share survival; all supported repository checks use the guarded current model.',
    'Cached credential/principal contexts cannot accept, grant, renew import work, resume publication or read protected receipts after disablement. Initial acceptance and retries require current metadata authority; failed event append rolls back grants, credential changes and principal disablement.',
    'Both reference and relational projections agree on all 38 scenarios. Explicit curator migration and the unchanged historical corpus pass; current authorized retries return the exact receipt without duplicate publication.',
    'One installation lock/revision and same-transaction events/receipts cover current mutations. LocalOnly is explicit in schema/organization receipts; prior V008 receipt decoding and rejection of unknown durability are tested. ADR004 independent wire/witness acknowledgment remains O03.6.',
    'The supported mutation inventory and PolicySql.commit boundary cover organization, grant, API credential and invalidation changes. Runtime grant events pass while forged operator events fail row security; operator identity and DB login are recorded, without bearer secrets.'
]
assert len(criteria) == len(evidence) == 5
verification = {'distinctTests':351,'referenceAuthorityTests':57,'referenceOrganizationTests':14,'serviceTests':9,
    'allPostgresIntegrationTests':271,'currentAuthorityTests':44,'legacyReceiptCodecTests':2,'adrCases':38,
    'propertyTests':6,'successfulTrialsPerProperty':500,'failures':0,'errors':0,'skipped':0,'packagedChecks':11,
    'fixtureSha256':digest(root/'docs/decisions/institutional-authority-cases.json'),
    'historicalCompatibility':'V009 candidate passes the unchanged corpus-v2 oracle; old V007 binary refuses the upgrade',
    'migrationFailure':'Interrupted populated V007 upgrade rolls back after first V008 ALTER; all historical hashes retained; retry applies V008 and V009',
    'legacySourceIdentity':'original source file hashes, inode and mtime unchanged; source remains available (not source-loss recovery)'}
receipt = {'schemaVersion':1,'task':'F03.2','mote':'bd-01M4W5P5GCCHC6ZMZ7MCGEJCW8','status':'passed',
    'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'artifact':{'path':str(artifact.relative_to(root)),'sha256':build['sha256'],'bytes':build['bytes']},
    'implementation':{'schema':'V009__current_authority.sql','authority':'OrganizationAuthority via PolicySql under the policy guard',
        'eventContract':'docs/policy-transactions.md','durability':'LocalOnly; no independent recovery claim'},
    'acceptance':[{'criterion':c,'status':'passed','evidence':e} for c,e in zip(criteria,evidence)],
    'verification':verification,'commands':['python3 docs/evidence/institutional-pilot/F03.2/qualification-procedure.py',
        'python3 docs/evidence/institutional-pilot/F03.2/record-evidence.py'],
    'environment':{'os':platform.platform(),'java':build['java'],'scala':'3.3.8','sbt':'1.10.5',
        'postgresImage':smoke_result['postgresImage'],'database':read(out/'database-environment.json'),
        'scope':'Synthetic local engineering; separate disposable PostgreSQL schemas, four-connection pools; packaged ordinary runtime and migration owner.'},
    'inputs':inputs,
    'limitations':[
        'Organization events are local engineering durability only. O03.6 witness, O03.7 restore and O03.8 source-isolation rehearsal remain open; legacy recovery still refuses initialized catalogs.',
        'The local event encoding/chain is versioned and retained, not a qualified RFC8785/Ed25519 witness protocol. No off-host or physical machine/disk-loss proof.',
        'Policy and organization retry loading still scans complete state. Institutional-scale query/latency qualification remains H04.',
        'Public disclosure/effective-access projections and continuous stream/worker admission remain F03.3/F03.4; organization administration remains F02.3. The SQL matrix tests the trusted adapter, not all public endpoint projections. Receipt reads retain the prior Accept check alongside metadata authority; candidate reads retain the prior Propose-or-Accept path pending F03.3.',
        'Eligibility and lifecycle storage inputs are exercised by trusted SQL fixtures; browser identity, guest sponsorship and lifecycle administration remain F04/O04.',
        'Local principals and operator DB logins are engineering identities, not verified institutional identities. Runtime/schema owner are trusted service/operator boundaries.',
        'Legacy Action/Grant HTTP shapes remain unchanged; expanded permissions and grant lineage currently use the typed PostgreSQL adapter.',
        'Current credential issuance has no client-selected retry key or replayable secret response; inspect/revoke ambiguous local issuance before reissuing.',
        'No institutional acceptance, complete demo/guide, six-area review or integrated H00 qualification is claimed.'
    ]}
write(out/'receipt.json', receipt)
print(json.dumps({'status':'passed','tests':351,'sourceInputs':len(source_paths),'artifact':receipt['artifact']},indent=2))
