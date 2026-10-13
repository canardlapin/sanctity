"""Pinned, standard-library-only header observation producer. No voxel analysis."""
import csv
from decimal import Decimal
import gzip
import json
import math
from pathlib import Path
import struct
import sys


def header(path):
    opener = gzip.open if path.name.endswith('.gz') else open
    with opener(path, 'rb') as stream:
        data = stream.read(540)
    endian = next((e for e in ('<', '>') if struct.unpack_from(e + 'i', data)[0] in (348, 540)), None)
    if endian is None:
        raise ValueError('unknown NIfTI header')
    version = struct.unpack_from(endian + 'i', data)[0]
    if version == 348:
        if data[344:348] != b'n+1\x00':
            raise ValueError('single-file NIfTI-1 required')
        dims = struct.unpack_from(endian + '8h', data, 40)
        pixels = struct.unpack_from(endian + '8f', data, 76)
        units = data[123]
    else:
        if data[4:12] != b'n+2\x00\r\n\x1a\n':
            raise ValueError('single-file NIfTI-2 required')
        dims = struct.unpack_from(endian + '8q', data, 16)
        pixels = struct.unpack_from(endian + '8d', data, 104)
        units = struct.unpack_from(endian + 'i', data, 500)[0]
    if dims[0] != 4 or min(dims[1:5]) < 1 or dims[4] < 2:
        raise ValueError('four-dimensional BOLD required')
    factor = {8: 1, 16: .001, 24: .000001}.get(units & 56)
    if factor is None or not math.isfinite(pixels[4]) or pixels[4] <= 0:
        raise ValueError('known positive temporal spacing required')
    return list(dims[1:5]), pixels[4] * factor


def decimal(value):
    text = format(Decimal(str(value)), 'f')
    return text.rstrip('0').rstrip('.') if '.' in text else text


def main():
    request = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
    root = Path(sys.argv[2]).resolve()
    output = Path(sys.argv[3])
    output.mkdir()
    rows = []
    for item in request['rows']:
        path = (root / item['path']).resolve(strict=True)
        if root not in path.parents:
            raise ValueError('source outside bundle')
        dims, spacing = header(path)
        tr = item['tr']
        if abs(spacing - tr) > max(1e-6, tr * 1e-5):
            raise ValueError('authored/header timing mismatch')
        rows.append([item['path']] + dims + [decimal(tr), decimal(spacing)])
    with (output / 'qc.tsv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream, delimiter='\t', lineterminator='\n')
        writer.writerow(request['columns'])
        writer.writerows(rows)
    for name, value in [('dataset_description.json', request['description']), ('qc.json', request['sidecar'])]:
        (output / name).write_text(json.dumps(value, indent=2, sort_keys=True) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
