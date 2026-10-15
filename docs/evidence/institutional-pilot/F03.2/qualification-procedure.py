"""Qualify one local F03.2 candidate; all databases are newly created disposable containers.
Run from the repository root after stopping other sbt/package invocations.
"""
import json
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

out = Path('docs/evidence/institutional-pilot/F03.2')
package = Path('target/service-release-f032-20261014')
smoke = Path('target/service-smoke-f032-20261014')
compat = Path('target/compatibility-f032-current')
assert not any(p.exists() for p in [package, smoke, compat]), 'qualification output must be new'


def run(args, log):
    print('Running: ' + ' '.join(args), flush=True)
    with log.open('w') as stream:
        subprocess.run(args, stdout=stream, stderr=subprocess.STDOUT, check=True)


run(['python3', str(out / 'regression-procedure.py')], out / 'regression.log')
shutil.copyfile('/private/tmp/sanctity-f032-final-environment.json', out / 'database-environment.json')
suites = re.findall(r'Test run ([\w.]+) finished: 0 failed, 0 ignored, (\d+) total', (out / 'regression.log').read_text())
assert len(suites) == len(set(name for name, _ in suites)) == 13
assert sum(int(count) for _, count in suites) == 351
for name, count in suites:
    module = 'integration' if '.postgres.' in name else 'service' if name.endswith('ServiceConfigurationSuite') else 'app'
    source = Path('modules') / module / 'target/test-reports' / ('TEST-' + name + '.xml')
    report = ET.parse(source).getroot()
    assert int(report.attrib['tests']) == int(count)
    assert all(int(report.attrib.get(field, 0)) == 0 for field in ['failures', 'errors', 'skipped'])
    shutil.copyfile(source, out / source.name)
(out / 'test-summary.json').write_text(json.dumps(dict(suites), indent=2) + '\n')
print('Passed 351 distinct tests; retained all 13 XML reports before package clean.', flush=True)
run(['python3', 'scripts/package-service.py', '--output', str(package)], out / 'build.log')
artifact = package / 'sanctity-0.1.0-SNAPSHOT.zip'
run(['python3', 'scripts/check-service-package.py', str(artifact), '--output', str(smoke)], out / 'smoke.log')
run(['python3', 'scripts/check-upgrade-compatibility.py', 'verify', '--modern', '--organization', '--artifact', str(artifact),
     '--fixture', 'local-artifacts/compatibility/pre-core-20261014/corpus-v2', '--output', str(compat)], out / 'compatibility.log')
print('Package and unchanged historical corpus checks passed.', flush=True)
