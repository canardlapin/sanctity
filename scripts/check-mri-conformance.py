#!/usr/bin/env python3
"""Pinned official validation of an explicitly assembled MRI fixture and mutations.
No image headers are excluded. --record is for deliberate expectation review only.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'modules/app/src/test/resources/mri-conformance'
PINS = ROOT / 'modules/app/src/test/resources/conformance'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deno', default='deno')
    parser.add_argument('--record', action='store_true')
    args = parser.parse_args()
    deno = str(Path(args.deno).resolve()) if '/' in args.deno else args.deno
    assert subprocess.check_output([deno, '--version'], text=True).splitlines()[0].split()[:2] == ['deno', '2.5.4']
    for base, name in [(FIXTURES, 'source.json'), (FIXTURES / 'ds000117', 'provenance.json')]:
        for path, record in json.loads((base / name).read_text())['files'].items():
            assert hashlib.sha256((base / path).read_bytes()).hexdigest() == record['sha256'], path
    pin = json.loads((PINS / 'validator.json').read_text())
    schema = gzip.decompress((PINS / 'schema-1.11.1.json.gz').read_bytes())
    assert hashlib.sha256(schema).hexdigest() == pin['schemaSha256']
    out = Path(tempfile.mkdtemp(prefix='mri-conformance-', dir=ROOT / 'target'))
    schema_file = out / 'schema.json'
    schema_file.write_bytes(schema)
    template = out / 'template'
    def write(name, value):
        path = template / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value if isinstance(value, bytes) else (json.dumps(value) + '\n').encode())
    bold = 'sub-01/ses-task/func/sub-01_ses-task_task-rest_bold.nii.gz'
    phase = 'sub-01/ses-cal/fmap/sub-01_ses-cal_acq-func_phasediff.nii.gz'
    write('dataset_description.json', {'Name': 'Assembled conformance fixture', 'BIDSVersion': '1.11.1', 'DatasetType': 'raw', 'Authors': ['Sanctity contributors']})
    write('README', b'Assembled software fixture; not an acquired study. Images copied from NiBabel 5.3.2 (MIT) and OpenNeuro ds000117 (CC0). See retained source records.\n')
    write('LICENSE', (FIXTURES / 'COPYING').read_bytes() + b'\nFieldmap data: OpenNeuro ds000117, CC0, Wakeman/Ap\xc5\xa1valka/Henson. See original dataset_description.json in the retained corpus.\n')
    write(bold, (FIXTURES / 'example4d.nii.gz').read_bytes())
    write(bold.replace('.nii.gz', '.json'), {'TaskName': 'rest', 'RepetitionTime': 2000.0, 'B0FieldSource': 'gre'})
    source = 'sub-01/ses-mri/fmap/sub-01_ses-mri_acq-func_'
    for role in ['phasediff', 'magnitude1', 'magnitude2']:
        path = phase.replace('phasediff', role)
        write(path, (FIXTURES / 'ds000117' / (source + role + '.nii.gz')).read_bytes())
        metadata = json.loads((FIXTURES / 'ds000117' / (source + role + '.json')).read_text())
        fields = {k: v for k, v in metadata.items() if k in ['EchoTime', 'EchoTime1', 'EchoTime2']}
        if role == 'phasediff':
            fields.update({'B0FieldIdentifier': 'gre', 'IntendedFor': ['bids::' + bold]})
        write(path.replace('.nii.gz', '.json'), fields)
    results = []
    cases = ['complete', 'without-optional-magnitude2', 'missing-magnitude1', 'missing-echo-time2', 'invalid-spatial-spacing', 'header-tr-mismatch']
    for case in cases:
        dataset = out / case
        shutil.copytree(template, dataset)
        if case in ['without-optional-magnitude2', 'missing-magnitude1']:
            role = 'magnitude2' if case.startswith('without') else 'magnitude1'
            for extension in ['.nii.gz', '.json']:
                (dataset / phase.replace('phasediff', role).replace('.nii.gz', extension)).unlink()
        elif case == 'missing-echo-time2':
            path = dataset / phase.replace('.nii.gz', '.json')
            fields = json.loads(path.read_text()); del fields['EchoTime2']
            path.write_text(json.dumps(fields) + '\n')
        elif case == 'header-tr-mismatch':
            path = dataset / bold.replace('.nii.gz', '.json')
            fields = json.loads(path.read_text()); fields['RepetitionTime'] = 2.0
            path.write_text(json.dumps(fields) + '\n')
        elif case == 'invalid-spatial-spacing':
            path = dataset / phase
            image = bytearray(gzip.decompress(path.read_bytes()))
            struct.pack_into('<f', image, 80, 0.0)
            path.write_bytes(gzip.compress(image, mtime=0))
        report = out / (case + '.json')
        run = subprocess.run([deno, 'run', '-ERWN', '--lock=' + str(PINS / 'deno.lock'), '--frozen', pin['validator'], str(dataset),
            '--schema', schema_file.as_uri(), '--max-rows', '-1', '--format', 'json', '--outfile', str(report)],
            capture_output=True, text=True, env={**os.environ, 'BIDS_SCHEMA': schema_file.as_uri()})
        (out / (case + '.log')).write_text(run.stdout + run.stderr)
        result = json.loads(report.read_text())
        errors = sorted({(i['code'], i.get('subCode', '')) for i in result['issues']['issues'] if i['severity'] == 'error'})
        assert run.returncode == (16 if errors else 0), (case, run.returncode)
        assert result['summary']['schemaVersion'] == '1.2.2'
        results.append({'case': case, 'errors': errors})
        print(case, errors, flush=True)
    recorded = {'validator': pin['validator'], 'schemaSha256': pin['schemaSha256'], 'niftiHeadersChecked': True, 'cases': results}
    # JSON round trip normalizes tuples to arrays.
    recorded = json.loads(json.dumps(recorded))
    expected = FIXTURES / 'official-results.json'
    if args.record:
        expected.write_text(json.dumps(recorded, indent=2) + '\n')
    else:
        assert recorded == json.loads(expected.read_text()), 'official results differ from reviewed signatures'
    (out / 'comparison.json').write_text(json.dumps(recorded, indent=2) + '\n')
    print('Reports:', out)


if __name__ == '__main__':
    main()
