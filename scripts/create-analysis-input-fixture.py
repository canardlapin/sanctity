#!/usr/bin/env python3
"""Create CC0 synthetic multi-echo inputs directly from bytes, without MRI libraries."""
import argparse
import hashlib
import json
from pathlib import Path
import struct


def image(echo):
    b = bytearray(352 + 4 * 8 * 3)
    struct.pack_into('<i', b, 0, 348)
    struct.pack_into('<8h', b, 40, 4, 2, 2, 2, 3, 1, 1, 1)
    struct.pack_into('<2h', b, 70, 16, 32)
    struct.pack_into('<8f', b, 76, 1, 2, 3, 4, 2, 1, 1, 1)
    struct.pack_into('<3f', b, 108, 352, 1, 0)
    b[123] = 10
    struct.pack_into('<2h', b, 252, 1, 1)
    struct.pack_into('<12f', b, 280, 2, 0, 0, 0, 0, 3, 0, 0, 0, 0, 4, 0)
    b[344:348] = b'n+1\0'
    struct.pack_into('<24f', b, 352, *(float(echo * 100 + i) for i in range(24)))
    return bytes(b)


def create(destination):
    destination.mkdir(parents=True, exist_ok=False)
    dataset = destination / 'dataset'
    members = [f'sub-01/func/sub-01_task-cue_run-01_echo-{i}_bold.nii' for i in (1, 2, 3)]
    events = 'task-cue_events.tsv'
    stimuli = ['stimuli/images/red.ppm', 'stimuli/tone.txt']
    def write(path, value):
        out = dataset / path
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(value if isinstance(value, bytes) else (json.dumps(value, indent=2) + '\n').encode())
    write('dataset_description.json', {'Name': 'Synthetic coherent analysis inputs', 'BIDSVersion': '1.11.1', 'DatasetType': 'raw', 'License': 'CC0', 'Authors': ['Sanctity contributors']})
    write('README', b'CC0 direct-byte synthetic software fixture; not acquired MRI, registration or scanner qualification.\n')
    write('LICENSE', b'CC0 1.0 Universal: https://creativecommons.org/publicdomain/zero/1.0/\n')
    write('task-cue_bold.json', {'TaskName': 'cue', 'RepetitionTime': 2.0})
    for i, p in enumerate(members, 1):
        write(p, image(i))
        write(p.removesuffix('.nii') + '.json', {'EchoTime': i / 100})
    write(events, b'onset\tduration\ttrial_type\tstim_file\n0\t1\tvisual\timages/red.ppm\n2\t1\tauditory\ttone.txt\n4\t1\tvisual\timages/red.ppm\n5\t0\tcatch\tn/a\n')
    write('task-cue_events.json', {'trial_type': {'Description': 'Synthetic trial'}, 'stim_file': {'Description': 'Local synthetic stimulus'}})
    write(stimuli[0], b'P3\n1 1\n255\n255 0 0\n')
    write(stimuli[1], b'Synthetic tone instruction: 440 Hz for one second.\n')
    write('stimuli/unreferenced.json', b'{This deliberately is not metadata JSON; stimulus bytes are opaque.}\n')
    files = {str(p.relative_to(dataset)): {'sha256': hashlib.sha256(p.read_bytes()).hexdigest(), 'size': p.stat().st_size}
             for p in sorted(dataset.rglob('*')) if p.is_file()}
    (destination / 'provenance.json').write_text(json.dumps({
        'kind': 'synthetic-direct-byte-fixture', 'license': 'CC0-1.0',
        'generator': 'scripts/create-analysis-input-fixture.py',
        'generatorSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'expectedMembers': members, 'events': events, 'expectedStimuli': stimuli,
        'expectedDimensions': [2, 2, 2, 3], 'expectedSpacing': [2, 3, 4, 2], 'expectedEchoTimes': [.01, .02, .03],
        'schemaSha256': '9bac1441212012d1971982053545dba8160652d73d8d0faed71709b4401ad300',
        'files': files}, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', type=Path)
    create(parser.parse_args().destination)
