#!/usr/bin/env python3
"""Opt-in, independent streaming table and JVM RSS probe (stdlib only)."""

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import threading
import time


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fixture(path, decoded_bytes):
    x = b"-0.125000000000000000001"
    timestamp = b"1234567890." + b"0" * (64 - len(x) - len(b"\t\tn/a\n") - 11)
    row = timestamp + b"\t" + x + b"\tn/a\n"
    assert len(row) == 64 and decoded_bytes % len(row) == 0
    block = row * 16384
    with path.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=1, mtime=0) as output:
            remaining = decoded_bytes
            while remaining:
                output.write(block[:min(remaining, len(block))])
                remaining -= min(remaining, len(block))
    return decoded_bytes // 64, timestamp + b"\t" + x + b"\n"


def rss(pid):
    result = subprocess.run(["ps", "-o", "rss=", "-p", str(pid)], capture_output=True, text=True, check=False)
    return int(result.stdout.strip()) if result.stdout.strip() else None


def probe(repository, path, output, timeout):
    command = ["sbt", 'set app / Compile / run / javaOptions ++= Seq("-Xms128m", "-Xmx128m", "-XX:+AlwaysPreTouch", "-XX:ActiveProcessorCount=4", "-XX:MaxDirectMemorySize=64m")',
               "app/runMain sanctity.app.StreamingTableProbe " + json.dumps(str(path)) + " " + json.dumps(str(output))]
    process = subprocess.Popen(command, cwd=repository, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, bufsize=1, start_new_session=True)
    measured = {"workerPid": None, "baselineRssKiB": None, "peakRssKiB": 0, "samples": 0}
    done = threading.Event()

    def sample():
        while not done.wait(0.1):
            if measured["workerPid"]:
                value = rss(measured["workerPid"])
                if value is not None:
                    if measured["baselineRssKiB"] is None:
                        measured["baselineRssKiB"] = value
                    measured["peakRssKiB"] = max(measured["peakRssKiB"], value)
                    measured["samples"] += 1

    def expire():
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass

    sampler = threading.Thread(target=sample, daemon=True)
    sampler.start()
    timer = threading.Timer(timeout, expire)
    timer.start()
    try:
        with (output / "sbt.log").open("w") as log:
            for line in process.stdout:
                log.write(line)
                match = re.search(r"STREAMING_READY (\d+)", line)
                if match:
                    measured["workerPid"] = int(match.group(1))
            status = process.wait()
        if status:
            raise RuntimeError(f"probe exited {status}; see {output / 'sbt.log'}")
    finally:
        timer.cancel()
        done.set()
        sampler.join()
        process.stdout.close()
    assert measured["baselineRssKiB"] is not None and measured["samples"] >= 2, measured
    measured["incrementalPeakRssKiB"] = measured["peakRssKiB"] - measured["baselineRssKiB"]
    measured["readerPeakRssKiB"] = 0
    measured["runtimeAggregatePeakRssKiB"] = measured["peakRssKiB"]
    return measured


def independent_output(path, expected_row, rows):
    size = 0
    expected_size = len(expected_row) * rows
    with gzip.open(path, "rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            offset = size % len(expected_row)
            expected = (expected_row * ((len(chunk) + offset) // len(expected_row) + 1))[offset:offset + len(chunk)]
            assert chunk == expected, f"output differs at decoded byte {size}"
            size += len(chunk)
    assert size == expected_size, (size, expected_size)
    return size


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workdir", type=Path, required=True)
    parser.add_argument("--decoded-mib", type=int, nargs="+", default=[128, 1024])
    parser.add_argument("--timeout-seconds", type=int, default=1200)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parent.parent
    work = args.workdir.resolve()
    work.mkdir(parents=True, exist_ok=False)
    results = []
    for mib in args.decoded_mib:
        assert mib > 0 and mib <= 2048
        directory = work / f"{mib}mib"
        directory.mkdir()
        source = directory / "input.tsv.gz"
        rows, expected_row = fixture(source, mib * 1024 * 1024)
        print(f"Running {mib} MiB decoded, {rows} rows", flush=True)
        memory = probe(repository, source, directory, args.timeout_seconds)
        result = json.loads((directory / "probe.json").read_text())
        assert result["rows"] == rows and result["decodedBytes"] == mib * 1024 * 1024
        assert result["invalidValues"] == 0 and result["receiptMatchesOutput"] is True
        assert result["inputSha256"] == sha256(source)
        assert result["outputSha256"] == sha256(directory / "projected.tsv.gz")
        output_size = independent_output(directory / "projected.tsv.gz", expected_row, rows)
        record = {**result, "memory": memory, "independentlyCheckedOutputBytes": output_size,
                  "incrementalMemoryBudgetPassed": memory["incrementalPeakRssKiB"] <= 256 * 1024}
        results.append(record)
        (directory / "result.json").write_text(json.dumps(record, indent=2) + "\n")
        print(f"Checked {mib} MiB: {result['seconds']:.2f}s; incremental RSS {memory['incrementalPeakRssKiB'] / 1024:.1f} MiB", flush=True)
    growth = None
    if set(args.decoded_mib) >= {128, 1024}:
        by_size = {r["decodedBytes"]: r for r in results}
        growth = by_size[1024**3]["memory"]["peakRssKiB"] - by_size[128 * 1024**2]["memory"]["peakRssKiB"]
    passed = all(r["incrementalMemoryBudgetPassed"] for r in results) and (growth is None or growth <= 64 * 1024)
    receipt = {"schema": "sanctity-streaming-table-resource-v1", "passed": passed,
               "host": {"system": platform.system(), "release": platform.release(), "machine": platform.machine()},
               "measuredAtUnix": time.time(), "rssSampleSeconds": 0.1, "peakGrowthRssKiB": growth,
               "jvmOptions": ["-Xms128m", "-Xmx128m", "-XX:+AlwaysPreTouch", "-XX:ActiveProcessorCount=4", "-XX:MaxDirectMemorySize=64m"],
               "scope": "One warmed standalone JVM; no child reader. sbt and Python are build/probe orchestrators excluded from runtime aggregate. Not a complete service or multimodal qualification.",
               "results": results}
    (work / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    assert passed, f"memory budget failed; see {work / 'receipt.json'}"
    print(work / "receipt.json", flush=True)


if __name__ == "__main__":
    main()
