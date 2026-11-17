#!/usr/bin/env python3
"""Reproduce byte-authored timing headers and compare independent NiBabel observations."""
import argparse
import gzip
import hashlib
import io
import json
import pathlib
import struct

import nibabel as nib

ROOT = pathlib.Path(__file__).resolve().parent.parent
CORPUS = ROOT / "modules/app/src/test/resources/mri-conformance"
DEST = CORPUS / "bold-timing"


def generate(version, little, compressed, axis):
    offset = 352 if version == 1 else 544
    data = bytearray(offset + 2 * 3 * 4 * 3 * 4)
    endian = "<" if little else ">"

    def put(pos, fmt, *values):
        struct.pack_into(endian + fmt, data, pos, *values)

    if version == 1:
        put(0, "i", 348)
        data[344:348] = b"n+1\0"
        put(40, "8h", 4, 2, 3, 4, 3, 1, 1, 1)
        put(70, "2h", 16, 32)
        put(76, "8f", 1, 2, 3, 4, 2000, 1, 1, 1)
        put(108, "f", offset)
        data[123] = 18  # millimetres and milliseconds
        data[39] = (axis << 4) | 9  # other dim_info bits must not affect slice_dim
    else:
        put(0, "i", 540)
        data[4:12] = b"n+2\0\r\n\x1a\n"
        put(12, "2h", 16, 32)
        put(16, "8q", 4, 2, 3, 4, 3, 1, 1, 1)
        put(104, "8d", 1, 2, 3, 4, 2000000, 1, 1, 1)
        put(168, "q", offset)
        put(500, "i", 26)  # millimetres and microseconds
        data[524] = (axis << 4) | 9
    if not compressed:
        return bytes(data)
    out = io.BytesIO()
    with gzip.GzipFile(fileobj=out, filename="", mode="wb", mtime=0) as stream:
        stream.write(data)
    return out.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", action="store_true", help="deliberately refresh retained fixtures and observations")
    args = parser.parse_args()
    assert nib.__version__ == "5.3.2", "use the pinned MRI Python environment"
    images = {}
    for version in (1, 2):
        for little in (True, False):
            for compressed in (False, True):
                axis = int(not little) * 2 + int(compressed)
                name = f"v{version}-{'le' if little else 'be'}-axis{axis}.nii" + (".gz" if compressed else "")
                expected = generate(version, little, compressed, axis)
                file = DEST / name
                if args.record:
                    DEST.mkdir(parents=True, exist_ok=True)
                    file.write_bytes(expected)
                assert file.read_bytes() == expected, f"fixture differs: {name}"
                images["bold-timing/" + name] = file
    acquired = CORPUS / "acquired-multiecho/dataset"
    images.update({str(p.relative_to(CORPUS)): p for p in sorted(acquired.rglob("*_bold.nii"))})
    assert len(images) == 12
    records = {}
    for name, file in sorted(images.items()):
        image = nib.load(file)
        h = image.header
        axis = h.get_dim_info()[2]
        records[name] = {"sha256": hashlib.sha256(file.read_bytes()).hexdigest(),
                         "size": file.stat().st_size, "dimensions": list(image.shape),
                         "sliceDimension": 0 if axis is None else axis + 1,
                         "temporalUnit": h.get_xyzt_units()[1],
                         "temporalSpacing": float(h.get_zooms()[3])}
    record = {"nibabel": nib.__version__, "scope": "Header observations only; acquired provenance remains in acquired-multiecho/provenance.json",
              "images": records}
    oracle = DEST / "independent-results.json"
    if args.record:
        oracle.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n")
    assert json.loads(oracle.read_text()) == record, "independent header observations changed"
    print(f"PASS: {len(records)} independent timing headers; 8 generated files reproduce exactly")


if __name__ == "__main__":
    main()
