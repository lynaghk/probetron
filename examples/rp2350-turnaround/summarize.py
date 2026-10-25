#!/usr/bin/env python3
"""Summarize verified turnaround trials, including failed and interrupted runs."""
import gzip
import json
from pathlib import Path
import re
import sys


def main():
    if len(sys.argv) < 2:
        raise SystemExit("Usage: summarize.py TRIAL.json[.gz] ...")
    paths = [Path(p) for p in sys.argv[1:]]
    summaries = []
    for path in paths:
        read = gzip.open if path.suffix == ".gz" else open
        with read(path, "rt") as source:
            data = json.load(source)
        for trial in data if isinstance(data, list) else [data]:
            summaries.append(summarize(dict(trial, label=trial.get("label", path.name))))
    print(json.dumps(summaries, indent=2))


def summarize(data):
    result = {key: data.get(key) for key in
              ("label", "scope", "outcome", "ready", "exit", "mode", "speed_khz", "verify", "cpu", "binary_sha256")}
    for event in data["events"]:
        line = re.sub(r"\x1b\[[0-9;]*m", "", event["line"])
        match = re.search(r"Finished\s+in\s+([\d.]+)(ms|s)", line)
        if match:
            result["load_seconds"] = float(match[1]) / (1000 if match[2] == "ms" else 1)
        for marker in ("BOOT", "READY"):
            if f"TURNAROUND {marker}" in line:
                result[f"command_to_{marker.lower()}_seconds"] = event["seconds"]
    samples = data.get("samples", [])
    if samples:
        divider = samples[-1]["divider"]
        result["divider"] = divider
        for key in ("measured_core_hz", "inferred_swclk_hz", "temperature_mC", "throttled"):
            relevant = samples if key in ("temperature_mC", "throttled") else [
                sample for sample in samples if sample["divider"] == divider]
            values = [sample[key] for sample in relevant]
            result[key] = [min(values), max(values)]
    return result


if __name__ == "__main__":
    main()
