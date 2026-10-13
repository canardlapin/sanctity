#!/usr/bin/env python3
"""Compare pre-authored analysis input cases against a pinned official validator and NiBabel."""
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
CORPUS = ROOT / 'modules/app/src/test/resources/mri-conformance/analysis-inputs'
PINS = ROOT / 'modules/app/src/test/resources/conformance'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deno', default=str(ROOT / 'target/conformance-tools/deno'))
    parser.add_argument('--record', action='store_true')
    args = parser.parse_args()
    deno = str(Path(args.deno).resolve())
    assert subprocess.check_output([deno, '--version'], text=True).splitlines()[0].split()[:2] == ['deno', '2.5.4']
    import nibabel as nib
    provenance = json.loads((CORPUS / 'provenance.json').read_text())
    for name, expected in provenance['files'].items():
        raw = (CORPUS / 'dataset' / name).read_bytes()
        assert hashlib.sha256(raw).hexdigest() == expected['sha256'] and len(raw) == expected['size'], name
    headers = []
    for i, name in enumerate(provenance['expectedMembers'], 1):
        img = nib.load(CORPUS / 'dataset' / name)
        assert list(img.shape) == provenance['expectedDimensions']
        assert list(img.header.get_zooms()) == provenance['expectedSpacing']
        assert img.header.get_xyzt_units() == ('mm', 'sec')
        assert img.get_fdata().ravel(order='F').tolist() == [float(i * 100 + n) for n in range(24)]
        headers.append({'path': name, 'shape': list(img.shape), 'spacing': [float(n) for n in img.header.get_zooms()], 'voxelsChecked': 24})
    pin = json.loads((PINS / 'validator.json').read_text())
    schema = gzip.decompress((PINS / 'schema-1.11.1.json.gz').read_bytes())
    assert hashlib.sha256(schema).hexdigest() == pin['schemaSha256'] == provenance['schemaSha256']
    out = Path(tempfile.mkdtemp(prefix='analysis-input-conformance-', dir=ROOT / 'target'))
    schema_file = out / 'schema.json'
    schema_file.write_bytes(schema)
    results = []
    for case in json.loads((CORPUS / 'contract.json').read_text())['cases']:
        dataset = out / case['name']
        shutil.copytree(CORPUS / 'dataset', dataset)
        for name in case.get('delete', []):
            (dataset / name).unlink()
        for name, value in case.get('jsonEdits', {}).items():
            (dataset / name).write_text(json.dumps(value) + '\n')
        for name, value in case.get('writes', {}).items():
            (dataset / name).write_text(value)
        for src, dst in case.get('copy', {}).items():
            shutil.copyfile(dataset / src, dataset / dst)
        for name, value in case.get('pixelDimension', {}).items():
            data = bytearray((dataset / name).read_bytes())
            struct.pack_into('<f', data, 80, value)
            (dataset / name).write_bytes(data)
        report = out / (case['name'] + '.json')
        run = subprocess.run([deno, 'run', '-ERWN', '--lock=' + str(PINS / 'deno.lock'), '--frozen', pin['validator'], str(dataset),
                              '--schema', schema_file.as_uri(), '--max-rows', '-1', '--format', 'json', '--outfile', str(report)],
                             capture_output=True, text=True, env={**os.environ, 'BIDS_SCHEMA': schema_file.as_uri()}, timeout=180)
        (out / (case['name'] + '.log')).write_text(run.stdout + run.stderr)
        value = json.loads(report.read_text())
        errors = sorted({(i['code'], i.get('subCode', '')) for i in value['issues']['issues'] if i['severity'] == 'error'})
        assert run.returncode == (16 if errors else 0), (case['name'], run.returncode)
        assert value['summary']['schemaVersion'] == '1.2.2'
        results.append({'case': case['name'], 'officialErrors': errors, 'repositoryFindings': case['findings']})
        print(case['name'], errors, flush=True)
    result = json.loads(json.dumps({'validator': pin['validator'], 'schemaSha256': pin['schemaSha256'], 'niftiHeadersChecked': True,
                                   'ignoredIssues': [], 'nibabelVersion': nib.__version__, 'headers': headers, 'cases': results}))
    (out / 'comparison.json').write_text(json.dumps(result, indent=2) + '\n')
    expected = CORPUS / 'official-results.json'
    if args.record:
        expected.write_text(json.dumps(result, indent=2) + '\n')
    else:
        assert json.loads(expected.read_text()) == result
    print(out)


if __name__ == '__main__':
    main()
