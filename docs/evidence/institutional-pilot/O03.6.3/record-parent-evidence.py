"""Review the unchanged parent only after all three implementation slices close."""
from pathlib import Path
import datetime,hashlib,json,subprocess
root=Path(__file__).resolve().parents[4];out=Path(__file__).resolve().parent
target=root/'docs/evidence/institutional-pilot/O03.6/receipt.json'
assert not target.exists()
def read(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
qualification=read(out/'receipt.json');review=read(out/'parent-acceptance-review.json')
assert qualification['status']==review['status']=='passed' and len(review['criteria'])==6
assert all(c['status']=='passed' for c in review['criteria'])
for path,digest in qualification['inputs'].items():assert sha(root/path)==digest,path
motes=read(root/'docs/evidence/institutional-readiness-motes.json')
parent=json.loads(subprocess.check_output(['mote','--actor','codex-core-build','--json','show',motes['tasks']['O03.6']],cwd=root))
assert parent['status'] in ['open','doing']
for dep in parent['deps']:
 if dep['kind']=='blocks':
  state=json.loads(subprocess.check_output(['mote','--actor','codex-core-build','--json','show',dep['parent']],cwd=root))
  assert state['status']=='closed',state['title']
for c in review['criteria']:assert c['criterion'] in parent['body']
paths=['docs/evidence/institutional-pilot/O03.6.1/receipt.json','docs/evidence/institutional-pilot/O03.6.2/receipt.json',
 'docs/evidence/institutional-pilot/O03.6.3/receipt.json','docs/evidence/institutional-pilot/O03.6.3/parent-acceptance-review.json',
 'docs/evidence/institutional-pilot/O03.6.3/record-parent-evidence.py','docs/policy-transactions.md','docs/policy-witness.md',
 'docs/policy-catalog.md','docs/policy-process-qualification.md','docs/decisions/004-policy-durability.md',
 'docs/institutional-readiness-backlog.json','docs/evidence/institutional-readiness-motes.json']
result={'schemaVersion':1,'task':'O03.6','mote':parent['id'],'status':'passed',
 'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Independent durability and fencing for the currently implemented authority model only.',
 'acceptance':review['criteria'],'acceptanceUnchanged':True,'components':['O03.6.1','O03.6.2','O03.6.3'],
 'artifact':qualification['artifact'],'verification':qualification['verification'],
 'verificationSource':'docs/evidence/institutional-pilot/O03.6.3/receipt.json',
 'command':['python3','docs/evidence/institutional-pilot/O03.6.3/record-parent-evidence.py'],
 'next':'H03.1 isolated backup automation; O03.7 restore remains blocked by H03.1. O03.8 and broader institutional gates remain open.',
 'inputs':{p:sha(root/p) for p in paths},'limitations':qualification['limitations']}
target.write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'task':'O03.6','status':'passed','unchangedCriteria':6,'artifactSha256':result['artifact']['sha256']}))
