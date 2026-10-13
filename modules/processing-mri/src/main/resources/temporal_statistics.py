"""Qualified bounded temporal statistics v1. Invoked by the trusted local adapter."""
import gzip
import hashlib
import json
import math
import resource
from pathlib import Path
import sys
import time

import nibabel as nib
import numpy as np

MAX_BYTES = 8 * 1024**3
MAX_SPATIAL = 16 * 1024**2
TYPES = {2, 4, 8, 16, 64, 256, 512, 768}
PARAMETERS = {"volumes": "all", "ddof": "0", "nonfinite": "reject", "precision": "float64", "algorithm": "shifted-welford-v1"}


def runtime():
    if nib.__version__ != "5.3.2" or np.__version__ != "2.2.6":
        raise ValueError("requires nibabel 5.3.2 and numpy 2.2.6")
    return f"Python:{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro};nibabel:{nib.__version__};numpy:{np.__version__}"


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n")


def checked_path(root, name):
    path = root / name
    if path.resolve().is_relative_to(root.resolve()) and not Path(name).is_absolute():
        return path
    raise ValueError("path escapes assigned workspace")


def compute(request, inputs, outputs):
    runtime()
    if request["parameters"] != PARAMETERS or not 1 <= len(request["rows"]) <= 8:
        raise ValueError("unsupported recipe request")
    outputs.mkdir()
    scratch = outputs.parent / "scratch"
    scratch.mkdir()
    started = time.monotonic()
    processed = 0
    np.seterr(over="raise", invalid="raise", divide="raise")
    for row in request["rows"]:
        path = checked_path(inputs, row["path"])
        if path.stat().st_size != row["size"] or path.stat().st_size > MAX_BYTES:
            raise ValueError("input size mismatch")
        with path.open("rb") as source:
            if hashlib.file_digest(source, "sha256").hexdigest() != row["sha256"]:
                raise ValueError("input digest mismatch")
        # Inflate once, with a hard disk bound. Fully consume gzip to verify its CRC and trailer.
        local = scratch / "input.nii"
        opener = gzip.open if path.name.endswith(".gz") else open
        with opener(path, "rb") as source, local.open("xb") as target:
            total = 0
            while block := source.read(65536):
                total += len(block)
                if total > MAX_BYTES:
                    raise ValueError("logical input exceeds 8 GiB")
                target.write(block)
        image = nib.load(local, mmap="r")
        shape = image.shape
        if len(shape) != 4 or shape[3] < 2 or math.prod(shape[:3]) > MAX_SPATIAL:
            raise ValueError("requires bounded 4D multi-volume image")
        if int(image.header["datatype"]) not in TYPES:
            raise ValueError("unsupported scalar datatype")
        mean = np.zeros(shape[:3], dtype=np.float64, order="F")
        m2 = np.zeros_like(mean)
        origin = np.asarray(image.dataobj[..., 0], dtype=np.float64).copy()
        for t in range(shape[3]):
            volume = np.asarray(image.dataobj[..., t], dtype=np.float64)
            if not np.isfinite(volume).all() or np.abs(volume).max() > 1e90:
                raise ValueError("invalid scaled signal")
            volume = volume - origin
            delta = volume - mean
            mean += delta / (t + 1)
            m2 += delta * (volume - mean)
        mean += origin
        stddev = np.sqrt(np.maximum(m2 / shape[3], 0))
        if not np.isfinite(mean).all() or not np.isfinite(stddev).all():
            raise ValueError("nonfinite result")
        for spec in row["outputs"]:
            data = mean if spec["statistic"] == "mean" else stddev
            header = image.header.copy()
            header.extensions.clear()
            header.set_data_dtype(np.float64)
            header.set_intent("none")
            header["slice_code"] = 0
            header["slice_start"] = 0
            header["slice_end"] = 0
            header["slice_duration"] = 0
            header["toffset"] = 0
            header["xyzt_units"] = int(header["xyzt_units"]) & 7
            header["descrip"] = b"Sanctity temporal statistics v1"
            header["aux_file"] = b""
            header["cal_min"] = 0
            header["cal_max"] = 0
            # affine=None prevents implicit selection/replacement of independent qform/sform declarations.
            result = type(image)(data, None, header=header)
            result.header.set_slope_inter(1.0, 0.0)
            destination = checked_path(outputs, spec["path"])
            destination.parent.mkdir(parents=True, exist_ok=True)
            nib.save(result, destination)
            write_json(checked_path(outputs, spec["sidecarPath"]), spec["sidecar"])
        processed += total
        del image, volume, delta, mean, m2, stddev, result, data, origin
        local.unlink()
    scratch.rmdir()
    write_json(outputs / "dataset_description.json", request["description"])
    print(json.dumps({"logicalBytes": processed, "seconds": time.monotonic() - started,
        "peakRssBytes": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * (1 if sys.platform == "darwin" else 1024)}))


if __name__ == "__main__":
    if sys.argv[1:] == ["--identity"]:
        print(runtime())
    else:
        compute(json.loads(Path(sys.argv[1]).read_text()), Path(sys.argv[2]), Path(sys.argv[3]))
