"""Run the F03.1 pure authorization checks and retain their actual reports.

Requires the repository's normal JDK/sbt toolchain and cache access. No database,
network service or institutional identity provider is exercised by these tests.
"""
import pathlib
import shutil
import subprocess
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parents[4]
out = pathlib.Path(__file__).resolve().parent
command = ['sbt', 'app/testOnly sanctity.app.OrganizationAuthoritySuite sanctity.app.OrganizationSuite']
with (out / 'tests.log').open('w') as log:
    subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
for suite, expected in [('OrganizationAuthoritySuite', 57), ('OrganizationSuite', 14)]:
    source = root / 'modules/app/target/test-reports' / f'TEST-sanctity.app.{suite}.xml'
    report = ET.parse(source).getroot()
    assert int(report.attrib['tests']) == expected
    assert all(int(report.attrib.get(key, '0')) == 0 for key in ['errors', 'failures', 'skipped'])
    shutil.copyfile(source, out / source.name)
print('71 tests passed: 57 authority tests and 14 organization regressions.')
