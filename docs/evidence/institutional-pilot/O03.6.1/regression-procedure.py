"""Run standalone witness qualification; never connect to a catalog."""
from pathlib import Path
import subprocess, xml.etree.ElementTree as ET, json, shutil
root=Path(__file__).resolve().parents[4]
out=Path(__file__).resolve().parent
command=['sbt','postgres/test','service/test']
with (out/'regression.log').open('w') as log:
    subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
counts={}
for module in ['postgres','service']:
    for report in sorted((root/'modules'/module/'target/test-reports').glob('TEST-*.xml')):
        suite=ET.parse(report).getroot()
        assert all(int(suite.attrib.get(k,0))==0 for k in ['failures','errors','skipped']), report
        counts[suite.attrib['name']]=int(suite.attrib['tests'])
        shutil.copyfile(report,out/report.name)
assert set(counts)=={'sanctity.policy.PolicyWireSuite','sanctity.policy.PolicyJournalSuite','sanctity.policy.PolicyTransportSuite','sanctity.postgres.PersistenceSuite','sanctity.app.ServiceConfigurationSuite'}
(out/'test-summary.json').write_text(json.dumps({'command':command,'suites':counts,'total':sum(counts.values())},indent=2)+'\n')
print(json.dumps(counts))
