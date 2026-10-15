#!/usr/bin/env python3
"""Independently inspect the synthetic BehaviouralDemo exports using standard-library readers."""
import argparse
import csv
import hashlib
import io
import json
import pathlib
import platform
import re
import shutil


def inventory(root):
    return {
        p.relative_to(root).as_posix(): {
            "sha256": hashlib.sha256(p.read_bytes()).hexdigest(), "size": p.stat().st_size
        }
        for p in sorted(root.rglob("*")) if p.is_file()
    }


def table(path):
    return list(csv.reader(io.StringIO(path.read_text(encoding="utf-8")), delimiter="\t"))


def check(workspace):
    report = json.loads((workspace / "report.json").read_text())
    assert report["fixtureKind"] == "synthetic"
    assert report["reimportAdmissible"] is True
    source, full, subset = (workspace / name for name in ("source", "full", "subset"))
    assert inventory(source) == inventory(full), "full export changed paths or bytes"
    directories = lambda root: sorted(p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_dir())
    assert directories(source) == directories(full), "full export changed directories"
    primary = "sub-01/beh/sub-01_task-choice_beh.tsv"
    events = "sub-01/beh/sub-01_task-choice_events.tsv"
    expected = {
        primary, primary.removesuffix(".tsv") + ".json",
        events, events.removesuffix(".tsv") + ".json",
        "dataset_description.json", "participants.tsv", "participants.json", "stimuli/cat.txt",
    }
    assert set(inventory(subset)) == expected, "unexpected subset membership"
    assert table(subset / primary) == [
        ["trial", "response_time", "stim_file"],
        ["left", "0.125000000000000000001", "cat.txt"], ["right", "n/a", "n/a"],
    ], "behavioural values, lexical precision, missingness or column order changed"
    assert table(subset / events) == [
        ["onset", "duration", "trial_type"], ["-0.1", "0", "left"], ["0", "1", "right"],
    ], "event values or ordering changed"
    assert table(subset / "participants.tsv") == [["participant_id", "age"], ["sub-01", "27"]]
    metadata = json.loads((subset / primary.removesuffix(".tsv")).with_suffix(".json").read_text())
    assert set(metadata) == {"TaskName", "trial", "response_time"}
    assert metadata["TaskName"] == "choice" and metadata["response_time"]["Units"] == "s"
    assert json.loads((subset / "participants.json").read_text()) == {"age": {"Units": "year"}}
    description = json.loads((subset / "dataset_description.json").read_text())
    assert description == {
        "Name": "Authorized behavioural view", "BIDSVersion": "1.11.2", "DatasetType": "raw", "License": "CC0",
    }
    assert (subset / "stimuli/cat.txt").read_bytes() == (source / "stimuli/cat.txt").read_bytes()
    receipt = {
        "checker": "behavioural-export-oracle-v1", "python": platform.python_version(),
        "fixtureKind": "synthetic", "source": inventory(source), "subset": inventory(subset),
        "checks": ["full-bytes-and-directories", "subset-membership", "lexical-values-and-missingness",
                   "events", "participant-projection", "dictionary-disclosure", "description", "stimulus-bytes"],
    }
    (workspace / "independent.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--workspace", type=pathlib.Path)
    source.add_argument("--demo-log", type=pathlib.Path)
    parser.add_argument("--receipt", type=pathlib.Path)
    args = parser.parse_args()
    if args.demo_log:
        matches = re.findall(r"^\[info\] (/[^\n]+sanctity-behavioural-demo-[^\n]+)$", args.demo_log.read_text(), re.MULTILINE)
        if len(matches) != 1:
            parser.error("demo log must identify exactly one retained BehaviouralDemo workspace")
        workspace = pathlib.Path(matches[0])
    else:
        workspace = args.workspace
    receipt = check(workspace.resolve())
    output = workspace / "independent.json"
    if args.receipt:
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(output, args.receipt)
        output = args.receipt
    print(f"Passed {len(receipt['checks'])} independent checks; receipt: {output}")


if __name__ == "__main__":
    main()
