from pathlib import Path
import csv,hashlib,json,struct,io
sha=lambda data:hashlib.sha256(data).hexdigest()
canonical=lambda x:json.dumps(x,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()
reports=[]
for profile,one,two in [('quick','target/workload-quick-v2','target/workload-quick-repeat-v2'),('stress','target/workload-stress-v1','target/workload-stress-repeat-v1')]:
 a,b=Path(one),Path(two)
 manifest=json.loads((a/'manifest.json').read_text())
 assert manifest==json.loads((b/'manifest.json').read_text())
 for name,h in manifest['files'].items():assert sha((a/name).read_bytes())==h==sha((b/name).read_bytes())
 oracle=json.loads((a/'oracle.json').read_text())
 entries=[json.loads(line) for line in (a/'inventory.ndjson').read_text().splitlines()]
 for ds in oracle['datasets']:
  group=[{k:v for k,v in e.items() if k!='dataset'} for e in entries if e['dataset']==ds['dataset']]
  assert len(group)==ds['sourceEntries'] and sha(canonical(group))==ds['inventorySha256']
  files={e['path']:{'sha256':e['sha256'],'size':e['size']} for e in group if e['kind']=='file'}
  for release in ds['releases']:
   data=release['replacementUtf8'].encode();files['dataset_description.json']={'sha256':sha(data),'size':len(data)}
   assert sha(canonical(files))==release['exportManifestSha256']
  assert ds['queryAnswers']['siemensAgeAtLeast40'], 'hidden and public matching rows required'
  if profile=='quick':
   root=a/'sources'/ds['dataset']
   actual=list(root.rglob('*'));assert len(actual)==ds['sourceEntries']
   for e in group:
    path=root/e['path']
    if e['kind']=='directory':assert path.is_dir()
    else:assert path.stat().st_size==e['size'] and sha(path.read_bytes())==e['sha256']
   participants={row['participant_id']:int(row['age']) for row in csv.DictReader(io.StringIO((root/'participants.tsv').read_text()),delimiter='\t')}
   hits=[]
   for image in sorted(root.rglob('*.nii')):
    data=image.read_bytes();dims=struct.unpack_from('<8h',data,40)
    assert struct.unpack_from('<i',data,0)[0]==348 and dims[0]==3 and data[344:348]==b'n+1\0'
    assert len(data)==352+4*dims[1]*dims[2]*dims[3]
    md=json.loads(image.with_suffix('.json').read_text());subject=image.name.split('_')[0]
    if md['Manufacturer']=='Siemens' and participants[subject]>=40:hits.append(image.relative_to(root).as_posix())
   assert hits==ds['queryAnswers']['siemensAgeAtLeast40']
 for count in [10000,10001]:
  rows=[json.loads(l) for l in (a/f'boundary-{count}.ndjson').read_text().splitlines()]
  assert len(rows)==count and len({r['path'] for r in rows})==count
  if profile=='quick':assert len(list((a/'sources'/f'boundary-{count}').rglob('*')))==count
 if profile=='quick':
  payload=(a/'sources'/'slow-stream.bin').read_bytes()
  assert len(payload)==oracle['slowStream']['size'] and sha(payload)==oracle['slowStream']['sha256']
 # Handwritten contract sentinels do not call generator authority logic.
 kinds={d['kind']:d for d in oracle['datasets']}
 assert kinds['linked-b-into-a']['releases'][0]['access']['actions']['reader_a']==[]
 assert kinds['restricted-explicit']['releases'][0]['access']['actions']['metadata_only']==['ReadMetadata']
 guest=kinds['guest-fixed-r1']['releases'];assert guest[0]['access']['actions']['guest']==['Discover','ReadMetadata','ReadContent']
 assert guest[1]['access']['actions']['guest']==[] and 'guest' not in guest[0]['access']['headVisibleTo']
 assert all('Propose' not in acts for acts in kinds['archived-a']['releases'][0]['access']['actions'].values())
 reports.append({'profile':profile,'deterministic':True,'datasets':len(oracle['datasets']),'files':oracle['sourceFiles'],'bytes':oracle['sourceBytes'],'releases':oracle['acceptedReleaseCount'],'materializedQuickBytesQueriesNiftiAndBoundaries':'pass' if profile=='quick' else 'not materialized; deterministic recipes checked'})
b=json.load(open('docs/workloads/engineering-v1.json'));assert sum(b['mixPercent'].values())==100
assert b['budgets']['streamPermissionRecheckMaxChunkBytes']==65536 and b['budgets']['backgroundAuthorityHeartbeatMilliseconds']==5000
assert b['budgets']['intakeEntryCeiling']==10000 and b['sampling']['independentWarmRuns']>=3
print(json.dumps({'status':'passed','profiles':reports,'budgetManifest':'consistent with ADR002','performanceQualified':False},indent=2))
