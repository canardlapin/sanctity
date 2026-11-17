#!/usr/bin/env python3
"""CC0 functional reference/EPI fixture, authored directly as bytes (no imaging library)."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]


def create(destination):
    spec = importlib.util.spec_from_file_location("analysis_fixture", ROOT / "scripts/create-analysis-input-fixture.py")
    original = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(original)
    original.create(destination)
    dataset = destination / "dataset"
    bold = dataset / "task-cue_bold.json"
    metadata = json.loads(bold.read_text())
    metadata.update(PhaseEncodingDirection="j", TotalReadoutTime=0.05, B0FieldSource="cal1")
    bold.write_text(json.dumps(metadata, indent=2) + "\n")
    reference = "sub-01/func/sub-01_task-cue_run-01_sbref.nii"
    epis = [f"sub-01/fmap/sub-01_dir-{direction}_epi.nii" for direction in ("AP", "PA")]
    for index, name in enumerate([reference] + epis):
        data = bytearray(original.image(4 + index)[:352 + 8 * 4])
        struct.pack_into("<8h", data, 40, 3, 2, 2, 2, 1, 1, 1, 1)
        path = dataset / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        values = {"PhaseEncodingDirection": "j-" if index == 2 else "j", "TotalReadoutTime": 0.06 if index == 2 else 0.05}
        if index == 0:
            values.update(TaskName="cue", EchoTime=0.01)
        else:
            values["B0FieldIdentifier"] = "cal1"
        path.with_suffix(".json").write_text(json.dumps(values, indent=2) + "\n")
    record = json.loads((destination / "provenance.json").read_text())
    record.update(generator="scripts/create-calibration-fixture.py", generatorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  reference=reference, fieldmaps=epis, policy="Exact shared SBRef; explicit B0 EPI pair, opposing j/j-, same coded grid. Different positive readout times are intentional.")
    record["files"] = {str(p.relative_to(dataset)): {"size": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                       for p in sorted(dataset.rglob("*")) if p.is_file()}
    (destination / "provenance.json").write_text(json.dumps(record, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    create(parser.parse_args().destination)
