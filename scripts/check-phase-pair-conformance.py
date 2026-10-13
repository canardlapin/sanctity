#!/usr/bin/env python3
"""Compare the synthetic phase-pair corpus with the pinned official validator.

NIfTI checks remain enabled, and no issue is ignored. All reports are retained.
--record updates expected signatures only for deliberate review.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / 'modules/app/src/test/resources/mri-conformance/phase-pair'
PINS = ROOT / 'modules/app/src/test/resources/conformance'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deno', default='deno')
    parser.add_argument('--record', action='store_true')
    args = parser.parse_args()
    deno = str(Path(args.deno).resolve()) if '/' in args.deno else args.deno
    assert subprocess.check_output([deno, '--version'], text=True).splitlines()[0].split()[:2] == ['deno', '2.5.4']
    provenance = json.loads((CORPUS / 'provenance.json').read_text())
    for name, expected in provenance['files'].items():
        raw = (CORPUS / 'dataset' / name).read_bytes()
        assert hashlib.sha256(raw).hexdigest() == expected['sha256'] and len(raw) == expected['size'], name
    pin = json.loads((PINS / 'validator.json').read_text())
    schema = gzip.decompress((PINS / 'schema-1.11.1.json.gz').read_bytes())
    assert hashlib.sha256(schema).hexdigest() == pin['schemaSha256'] == provenance['schemaSha256']
    out = Path(tempfile.mkdtemp(prefix='phase-pair-conformance-', dir=ROOT / 'target'))
    schema_file = out / 'schema.json'
    schema_file.write_bytes(schema)
    members = provenance['expectedMembers']
    cases = json.loads((CORPUS / 'contract.json').read_text())['cases']
    results = []
    for specification in cases:
        case = specification['name']
        dataset = out / case
        shutil.copytree(CORPUS / 'dataset', dataset)

        def sidecar(name):
            return dataset / (name.removesuffix('.nii') + '.json')

        def fields(name, edits, remove=()):
            path = sidecar(name)
            value = json.loads(path.read_text())
            for field in remove:
                value.pop(field, None)
            value.update(edits)
            path.write_text(json.dumps(value, indent=2) + '\n')

        def move(old, new):
            (dataset / old).rename(dataset / new)
            sidecar(old).rename(sidecar(new))

        if case.startswith('missing-') and case.removeprefix('missing-') in ('phase1', 'phase2', 'magnitude1', 'magnitude2'):
            role = case.removeprefix('missing-')
            name = next(p for p in members if p.endswith('_' + role + '.nii'))
            (dataset / name).unlink()
        elif case in ('ambiguous-phase', 'ambiguous-magnitude'):
            name = members[0 if case == 'ambiguous-phase' else 3]
            (dataset / (name + '.gz')).write_bytes(gzip.compress((dataset / name).read_bytes(), mtime=0))
        elif case == 'different-acquisition':
            move(members[2], members[2].replace('acq-gre', 'acq-other'))
        elif case == 'run-padding':
            move(members[2], members[2].replace('run-01', 'run-1'))
        elif case == 'missing-echo-time':
            fields(members[1], {}, ['EchoTime'])
        elif case == 'reversed-echo-times':
            fields(members[1], {'EchoTime': 0.005})
        elif case == 'magnitude-echo-mismatch':
            fields(members[3], {'EchoTime': 0.008})
        elif case == 'missing-reference-target':
            fields(members[0], {'IntendedFor': 'bids::' + provenance['target'].replace('run-1', 'run-99')})
        elif case in ('mixed-representation', 'separate-phasediff'):
            roles = ['phasediff'] if case == 'mixed-representation' else ['phasediff', 'magnitude1', 'magnitude2']
            for role in roles:
                path = members[0].replace('phase1', role)
                if case == 'separate-phasediff':
                    path = path.replace('acq-gre', 'acq-other')
                (dataset / path).write_bytes((dataset / members[0]).read_bytes())
                sidecar(path).write_text(json.dumps({'EchoTime1': 0.006, 'EchoTime2': 0.00746} if role == 'phasediff' else {}) + '\n')
        elif case == 'separate-epi':
            path = 'sub-01/ses-cal/fmap/sub-01_ses-cal_dir-AP_epi.nii'
            (dataset / path).write_bytes((dataset / members[0]).read_bytes())
            sidecar(path).write_text(json.dumps({'B0FieldIdentifier': 'other-epi', 'PhaseEncodingDirection': 'j', 'TotalReadoutTime': 0.03}) + '\n')
        elif case != 'complete-cross-session':
            raise AssertionError('unimplemented corpus mutation: ' + case)

        report = out / (case + '.json')
        run = subprocess.run([deno, 'run', '-ERWN', '--lock=' + str(PINS / 'deno.lock'), '--frozen', pin['validator'], str(dataset),
                              '--schema', schema_file.as_uri(), '--max-rows', '-1', '--format', 'json', '--outfile', str(report)],
                             capture_output=True, text=True, env={**os.environ, 'BIDS_SCHEMA': schema_file.as_uri()}, timeout=180)
        (out / (case + '.log')).write_text(run.stdout + run.stderr)
        value = json.loads(report.read_text())
        errors = sorted({(i['code'], i.get('subCode', '')) for i in value['issues']['issues'] if i['severity'] == 'error'})
        assert run.returncode == (16 if errors else 0), (case, run.returncode)
        assert value['summary']['schemaVersion'] == '1.2.2'
        results.append({'case': case, 'officialErrors': errors, 'repositoryFindings': specification['findings']})
        print(case, errors, flush=True)
    compared = json.loads(json.dumps({'validator': pin['validator'], 'schemaSha256': pin['schemaSha256'],
                                    'niftiHeadersChecked': True, 'ignoredIssues': [], 'cases': results}))
    (out / 'comparison.json').write_text(json.dumps(compared, indent=2) + '\n')
    expected = CORPUS / 'official-results.json'
    if args.record:
        expected.write_text(json.dumps(compared, indent=2) + '\n')
    else:
        assert compared == json.loads(expected.read_text()), 'official results differ from reviewed signatures'
    print('Reports:', out)


if __name__ == '__main__':
    main()
