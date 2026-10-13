#!/usr/bin/env python3
"""Write a tiny synthetic phase-pair fixture, with no external image data.

Explicit destination must not exist. This generator writes NIfTI bytes directly;
it does not import bids4s, NiBabel or the repository interpreter.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct


def image(volumes=1):
    b = bytearray(352 + 4 * 4 * volumes)
    struct.pack_into('<i', b, 0, 348)
    struct.pack_into('<8h', b, 40, 4 if volumes > 1 else 3, 2, 2, 1, volumes, 1, 1, 1)
    struct.pack_into('<2h', b, 70, 16, 32)
    struct.pack_into('<8f', b, 76, 1, 2, 3, 4, 1.5, 1, 1, 1)
    struct.pack_into('<3f', b, 108, 352, 1, 0)
    b[123] = 10
    struct.pack_into('<2h', b, 252, 1, 1)
    struct.pack_into('<12f', b, 280, 2, 0, 0, 0, 0, 3, 0, 0, 0, 0, 4, 0)
    b[344:348] = b'n+1\0'
    struct.pack_into('<' + 'f' * (4 * volumes), b, 352, *(float(i + 1) for i in range(4 * volumes)))
    return bytes(b)


def create(destination):
    destination.mkdir(parents=True, exist_ok=False)
    dataset = destination / 'dataset'
    target = 'sub-01/ses-task/func/sub-01_ses-task_task-rest_run-1_bold.nii'
    other = target.replace('run-1', 'run-2')
    prefix = 'sub-01/ses-cal/fmap/sub-01_ses-cal_acq-gre_run-01_'
    members = [prefix + role + '.nii' for role in ('phase1', 'phase2', 'magnitude1', 'magnitude2')]

    def write(path, value):
        out = dataset / path
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(value if isinstance(value, bytes) else (json.dumps(value, indent=2) + '\n').encode())

    write('dataset_description.json', {'Name': 'Synthetic phase-pair contract fixture', 'BIDSVersion': '1.11.1', 'DatasetType': 'raw', 'License': 'CC0', 'Authors': ['Sanctity contributors']})
    write('README', b'Synthetic software fixture generated from direct NIfTI bytes; not acquired MRI and not phase-physics evidence. Dedicated to CC0.\n')
    write('LICENSE', b'CC0 1.0 Universal: https://creativecommons.org/publicdomain/zero/1.0/\n')
    write('task-rest_bold.json', {'TaskName': 'rest', 'RepetitionTime': 1.5, 'B0FieldSource': 'gre-pair'})
    for path in (target, other):
        write(path, image(2))
    for path in members:
        write(path, image())
        fields = {'EchoTime': 0.006 if path.endswith('1.nii') else 0.00746}
        if '_phase' in path:
            fields.update({'B0FieldIdentifier': 'gre-pair', 'IntendedFor': ['bids::' + target, 'bids::' + other]})
        write(path.removesuffix('.nii') + '.json', fields)
    files = {str(p.relative_to(dataset)): {'sha256': hashlib.sha256(p.read_bytes()).hexdigest(), 'size': p.stat().st_size}
             for p in sorted(dataset.rglob('*')) if p.is_file()}
    (destination / 'provenance.json').write_text(json.dumps({
        'kind': 'synthetic-direct-byte-fixture', 'license': 'CC0-1.0',
        'generator': 'scripts/create-phase-pair-fixture.py',
        'generatorSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'schemaSha256': '9bac1441212012d1971982053545dba8160652d73d8d0faed71709b4401ad300',
        'specification': 'https://github.com/bids-standard/bids-specification/blob/v1.11.1/src/modality-specific-files/magnetic-resonance-imaging-data.md#case-2-two-phase-maps-and-two-magnitude-images',
        'expectedMembers': members, 'target': target, 'excludedTarget': other, 'files': files,
    }, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', type=Path)
    create(parser.parse_args().destination)
