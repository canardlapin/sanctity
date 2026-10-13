#!/usr/bin/env python3
"""Independent pinned NiBabel oracle; offline after installing the two pinned packages.

Use --record only when intentionally reviewing/updating fixtures. Normal operation
verifies both source digests and all recorded observations without rewriting them.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

import nibabel as nib
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'modules/app/src/test/resources/mri-conformance'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def observe(path):
    raw = path.read_bytes()
    data = gzip.decompress(raw) if path.name.endswith('.gz') else raw
    # from_fileobj reads original header values, avoiding load-time offset/scaling changes.
    import io
    size = int.from_bytes(data[:4], 'little')
    if size not in (348, 540):
        size = int.from_bytes(data[:4], 'big')
    cls = nib.Nifti1Header if size == 348 else nib.Nifti2Header
    header = cls.from_fileobj(io.BytesIO(data), check=False)
    def real(v):
        n = float(v)
        return n if np.isfinite(n) else 'NaN' if np.isnan(n) else '+Infinity' if n > 0 else '-Infinity'
    return {
        'sha256': digest(raw), 'version': 1 if size == 348 else 2,
        'byteOrder': 'LittleEndian' if header.endianness == '<' else 'BigEndian',
        'dimensions': list(header.get_data_shape()),
        'pixelDimensions': [real(v) for v in header['pixdim']],
        'unitsCode': int(header['xyzt_units']),
        'qformCode': int(header['qform_code']), 'sformCode': int(header['sform_code']),
        'quaternion': [real(header[k]) for k in ('quatern_b', 'quatern_c', 'quatern_d')],
        'qoffset': [real(header[k]) for k in ('qoffset_x', 'qoffset_y', 'qoffset_z')],
        'sformRows': [[real(v) for v in header[k]] for k in ('srow_x', 'srow_y', 'srow_z')],
        'datatype': int(header['datatype']), 'bitsPerVoxel': int(header['bitpix']),
        'dataOffset': int(header['vox_offset']),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--record', action='store_true')
    args = parser.parse_args()
    assert nib.__version__ == '5.3.2', nib.__version__
    assert np.__version__ == '2.2.6', np.__version__
    sources = json.loads((FIXTURES / 'source.json').read_text())
    fieldmaps = json.loads((FIXTURES / 'ds000117/provenance.json').read_text())
    for base, record in [(FIXTURES, sources), (FIXTURES / 'ds000117', fieldmaps)]:
        for name, item in record['files'].items():
            raw = (base / name).read_bytes()
            assert digest(raw) == item['sha256'], name
            assert len(raw) == item['size'], name
    paths = sorted(p for p in FIXTURES.rglob('*') if p.name.endswith(('.nii', '.nii.gz')))
    report = {'nibabel': nib.__version__, 'numpy': np.__version__,
              'images': {str(p.relative_to(FIXTURES)): observe(p) for p in paths}}
    expected = FIXTURES / 'header-oracle.json'
    if args.record:
        expected.write_text(json.dumps(report, indent=2, allow_nan=False) + '\n')
        print(f'Recorded {len(paths)} independent image observations: {expected}')
    else:
        assert report == json.loads(expected.read_text()), 'header observations differ from reviewed record'
        print(f'Verified {len(paths)} image headers against pinned NiBabel observations and source digests.')


if __name__ == '__main__':
    main()
