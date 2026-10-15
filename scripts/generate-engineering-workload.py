#!/usr/bin/env python3
"""Generate versioned synthetic inventory and independent answers; never call Sanctity.

Default output contains recipes/hashes, not gigabytes of payload. --materialize
writes exact initial source files under a new directory. No existing output is reset.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

VERSION = "sanctity-engineering-v1"
SEED = 104729
PROFILES = {
    "quick": {"datasets": 6, "subjects": [48, 24, 36, 24, 24, 24], "releases": 3, "padding": 1024, "dimensions": [32, 32, 16]},
    "stress": {"datasets": 24, "subjects": [500, 250, 250, 125, 125, 250], "releases": 20, "padding": 8192, "dimensions": [64, 64, 32]},
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def write_json(path, value):
    path.write_bytes(canonical(value) + b"\n")


def description(dataset, revision):
    return canonical({"Name": dataset, "BIDSVersion": "1.10.0", "DatasetType": "raw", "Description": "Synthetic revision " + str(revision)}) + b"\n"


def image_bytes(dimensions, subject, dataset):
    # Valid little-endian NIfTI-1, float32, millimetres, identity sform; no human data.
    header = bytearray(352)
    struct.pack_into("<i", header, 0, 348)
    struct.pack_into("<8h", header, 40, 3, *dimensions, 1, 1, 1, 1)
    struct.pack_into("<hh", header, 70, 16, 32)
    struct.pack_into("<8f", header, 76, *([1.0] * 8))
    struct.pack_into("<f", header, 108, 352.0)
    header[123] = 2
    identity = (dataset + "/subject-" + str(subject)).encode()
    header[148:148 + len(identity)] = identity
    struct.pack_into("<h", header, 254, 1)
    struct.pack_into("<12f", header, 280, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0)
    header[344:348] = b"n+1\0"
    voxels = dimensions[0] * dimensions[1] * dimensions[2]
    return bytes(header) + struct.pack("<f", float(subject % 16)) * voxels


def expected_access(kind, revision, latest):
    # Explicit fixture answers, not a general implementation of ADR 002. Unknown
    # personas/actions deny. The qualification runner compares real authority to this.
    read = ["Discover", "ReadMetadata", "ReadContent"]
    owner = read + ["Propose", "Accept", "ManageAccess", "ReadEvidence"]
    result = {"owner": owner, "reader_a": [], "contributor_a": [], "steward_a": [],
              "reader_b": [], "guest": [], "metadata_only": [], "disabled": [], "outsider": []}
    if kind == 0:
        result.update(reader_a=read, contributor_a=read + ["Propose"], steward_a=read + ["Propose", "Accept", "ManageAccess"])
    elif kind in (2, 4):
        result["reader_b"] = read
    elif kind == 3 and revision == 1:
        result["guest"] = read
    elif kind == 5:  # archived project: reads retained, writes removed
        result.update(reader_a=read, contributor_a=read, steward_a=read)
        result["owner"] = read + ["ManageAccess", "ReadEvidence"]
    if kind == 1:
        result["metadata_only"] = ["ReadMetadata"]
    return {"actions": result, "headVisibleTo": [p for p, actions in result.items()
             if "ReadMetadata" in actions and p != "guest"], "guestAtExpiry": [],
            "afterPrincipalDisablement": [], "afterGrantRevocation": [],
            "privateCanaryVisibleTo": ["owner", "metadata_only"] if kind == 1 else [],
            "sourceEvidenceVisibleTo": ["owner"]}


def generate(profile, output, materialize):
    config = PROFILES[profile]
    output.mkdir(parents=True, exist_ok=False)
    inventory = (output / "inventory.ndjson").open("wb")
    datasets, file_count, total_bytes = [], 0, 0
    kinds = ["project-a", "restricted-explicit", "project-b", "guest-fixed-r1", "linked-b-into-a", "archived-a"]
    for index in range(config["datasets"]):
        kind = index % 6
        name = "dataset-%03d" % index
        subjects = config["subjects"][kind]
        entries, rows, matches, all_images = [], [], [], []
        def add(path, data=None):
            nonlocal file_count, total_bytes
            if data is None:
                entries.append({"path": path, "kind": "directory"})
                return
            entry = {"path": path, "kind": "file", "size": len(data), "sha256": sha(data)}
            entries.append(entry)
            file_count += 1
            total_bytes += len(data)
            if materialize:
                dest = output / "sources" / name / path
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(data)
        add("dataset_description.json", description(name, 1))
        add("README", ("Synthetic fixture " + name + "; seed=" + str(SEED) + "; kind=" + kinds[kind] + "\n").encode())
        for subject in range(1, subjects + 1):
            label = "%05d" % subject
            age = 20 + subject % 50
            manufacturer = "Siemens" if subject % 10 != 0 else "Philips"  # explicit 90/10 skew
            rows.append("sub-" + label + "\t" + str(age) + "\t" + ("control" if subject % 2 else "case"))
            directory = "sub-" + label
            add(directory)
            add(directory + "/anat")
            prefix = directory + "/anat/sub-" + label + "_T1w"
            metadata = {"Manufacturer": manufacturer, "MagneticFieldStrength": 3, "RepetitionTime": 2.0,
                        "Description": "x" * config["padding"]}
            if kind == 1:
                metadata["InstitutionName"] = "PRIVATE-CANARY-" + name
            add(prefix + ".json", canonical(metadata) + b"\n")
            add(prefix + ".nii", image_bytes(config["dimensions"], subject, name))
            all_images.append(prefix + ".nii")
            if age >= 40 and manufacturer == "Siemens":
                matches.append(prefix + ".nii")
        add("participants.tsv", ("participant_id\tage\tgroup\n" + "\n".join(rows) + "\n").encode())
        entries.sort(key=lambda e: e["path"])
        for entry in entries:
            inventory.write(canonical({"dataset": name, **entry}) + b"\n")
        releases = []
        for revision in range(1, config["releases"] + 1):
            content = {e["path"]: {"sha256": e["sha256"], "size": e["size"]} for e in entries if e["kind"] == "file"}
            desc = description(name, revision)
            content["dataset_description.json"] = {"sha256": sha(desc), "size": len(desc)}
            releases.append({"logicalRelease": "R" + str(revision), "parent": None if revision == 1 else "R" + str(revision - 1),
                             "changedPath": "dataset_description.json", "replacementUtf8": desc.decode(),
                             "exportManifestSha256": sha(canonical(content)), "access": expected_access(kind, revision, config["releases"])})
        datasets.append({"dataset": name, "kind": kinds[kind], "lab": "B" if kind in (2, 4) else "A",
                         "primaryProject": "B1" if kind in (2, 4) else ("A2" if kind == 5 else "A1"),
                         "linkedProject": "A1" if kind == 4 else None,
                         "inheritance": "ExplicitOnly" if kind in (1, 3) else "ProjectMembers",
                         "subjects": subjects, "sourceEntries": len(entries), "sourceFiles": sum(e["kind"] == "file" for e in entries),
                         "inventorySha256": sha(canonical(entries)), "releases": releases,
                         "queryAnswers": {"allT1w": all_images, "siemensAgeAtLeast40": matches},
                         "scientificFacts": {"suffix": "T1w", "datatype": "anat", "ageFormula": "20 + subjectNumber % 50",
                                             "manufacturerFormula": "Philips for subjectNumber % 10 == 0, otherwise Siemens",
                                             "RepetitionTime": 2.0, "MagneticFieldStrength": 3}})
    inventory.close()
    # Separate intake-boundary sources, never silently substituted into the ordinary
    # corpus or split into smaller accepted datasets. All entry counts include dirs.
    boundaries = []
    for count in (10000, 10001):
        name = "boundary-" + str(count)
        files = {"dataset_description.json": description(name, 1), "README": b"Synthetic entry-count boundary.\n"}
        # Flat BIDS-ignored code files make count exact without inventing imaging entities.
        entries = [{"path": "code", "kind": "directory"}]
        files.update({"code/item-%05d.txt" % i: ("boundary %d\n" % i).encode() for i in range(count - 3)})
        for path, data in sorted(files.items()):
            entries.append({"path": path, "kind": "file", "size": len(data), "sha256": sha(data)})
            if materialize:
                dest = output / "sources" / name / path
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(data)
        entries.sort(key=lambda e: e["path"])
        require = len(entries) == count
        if not require:
            raise AssertionError("boundary count")
        with (output / (name + ".ndjson")).open("wb") as stream:
            for entry in entries:
                stream.write(canonical(entry) + b"\n")
        boundaries.append({"dataset": name, "entries": count, "files": len(files), "inventorySha256": sha(canonical(entries)),
                           "expectedIntake": "within-entry-limit" if count == 10000 else "LimitExceeded",
                           "overLimitSideEffects": "no new operation, candidate, release, blob or reservation retained" if count > 10000 else None})
    # Stream content is generated blockwise by Q01.2/materialization, not stored in RAM.
    block = hashlib.sha256((VERSION + "/slow/" + str(SEED)).encode()).digest() * 2048
    size = (64 if profile == "quick" else 256) * 1024 * 1024
    h = hashlib.sha256()
    stream_path = output / "sources" / "slow-stream.bin"
    target = stream_path.open("wb") if materialize else None
    try:
        for _ in range(size // len(block)):
            h.update(block)
            if target:
                target.write(block)
    finally:
        if target:
            target.close()
    oracle = {"version": VERSION, "seed": SEED, "profile": profile, "configuration": config, "datasets": datasets,
              "labs": ["A", "B"], "projects": ["A1", "A2", "B1", "B2"], "archivedProjects": ["A2"],
              "sourceFiles": file_count, "sourceBytes": total_bytes, "acceptedReleaseCount": config["datasets"] * config["releases"],
              "boundaryCases": boundaries, "slowStream": {"size": size, "sha256": h.hexdigest(), "blockRecipe": "sha256(UTF8(version + '/slow/' + seed)) repeated 2048; repeat block to size",
                "placement": "standalone verified download fixture, excluded from BIDS source counts"},
              "negativeCases": {"cursor": ["other-principal", "expired", "tampered", "other-query", "revoked-after-first-page"],
                                "expectedCursorResult": "denied/invalid with no cached rows or counts",
                                "hiddenCount": "query/list totals equal authorized rows only", "fixedGuest": "R1 only; no head, R2, parent project, restricted canary or source evidence",
                                "publicationDuringPaging": "frozen logical release mapping unchanged; new head requires new query"}}
    write_json(output / "oracle.json", oracle)
    manifest = {"version": VERSION, "profile": profile, "seed": SEED,
                "files": {p.name: sha(p.read_bytes()) for p in sorted(output.iterdir()) if p.is_file()},
                "qualification": "targets and oracle only; performance not measured"}
    write_json(output / "manifest.json", manifest)
    print(json.dumps({"profile": profile, "datasets": config["datasets"], "releases": oracle["acceptedReleaseCount"],
                      "files": file_count, "bytes": total_bytes, "oracleSha256": sha((output / "oracle.json").read_bytes()),
                      "manifestSha256": sha((output / "manifest.json").read_bytes()), "materialized": materialize}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", choices=PROFILES)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--materialize", action="store_true")
    args = parser.parse_args()
    generate(args.profile, args.output, args.materialize)
