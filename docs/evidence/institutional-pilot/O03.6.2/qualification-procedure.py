"""Build and exercise a fresh artifact; preserve all earlier tranches and the historical oracle."""
from pathlib import Path
import json, subprocess, shutil, xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[4]
out=Path(__file__).resolve().parent
# Packaging cleans modules. Retain the actual successful XML reports first.
counts={}
for module in ['postgres','service','integration','app']:
    for report in sorted((root/'modules'/module/'target/test-reports').glob('TEST-*.xml')):
        suite=ET.parse(report).getroot()
        name=suite.attrib['name']
        if module=='app' and name not in ['sanctity.app.OrganizationAuthoritySuite','sanctity.app.OrganizationSuite']:
            continue
        assert all(int(suite.attrib.get(k,0))==0 for k in ['failures','errors','skipped']), report
        counts[name]=int(suite.attrib['tests'])
        shutil.copyfile(report,out/report.name)
assert counts['sanctity.postgres.PolicyIntegrationSuite']==21
assert counts['sanctity.postgres.PolicyReceiptCompatibilitySuite']==3
(out/'test-summary.json').write_text(json.dumps({'suites':counts,'total':sum(counts.values())},indent=2)+'\n')
release=root/'target/service-release-o0362-20261014'
artifact=release/'sanctity-0.1.0-SNAPSHOT.zip'
commands=[
 ['python3','scripts/package-service.py','--output',str(release)],
 ['python3','scripts/check-service-package.py',str(artifact),'--output',str(out/'package')],
 ['python3','scripts/check-policy-witness-package.py',str(artifact),'--output',str(out/'witness-package')],
 ['python3','scripts/check-policy-catalog-package.py',str(artifact),'--output',str(out/'catalog-package')],
 ['python3','scripts/check-upgrade-compatibility.py','verify','--modern','--organization','--artifact',str(artifact),
  '--fixture','local-artifacts/compatibility/pre-core-20261014/corpus-v2','--output',str(out/'historical-upgrade')]
]
for i,command in enumerate(commands):
    with (out/f'qualification-{i+1}.log').open('x') as log:
        subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
    print('passed',command[1],flush=True)
