#!/usr/bin/env python3
"""Timestamp a command's output with a monotonic clock; stop after READY."""
import argparse
import json
import os
from pathlib import Path
import selectors
import signal
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--until-exit", action="store_true", help="measure a build or finite command")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    start = time.monotonic_ns()
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               start_new_session=True)
    events = []
    pending = b""
    ready = False
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while time.monotonic_ns() - start < args.timeout * 1e9:
                for key, _ in selector.select(0.1):
                    data = os.read(key.fd, 65536)
                    if not data:
                        selector.unregister(key.fd)
                        break
                    pending += data
                    while b"\n" in pending:
                        line, pending = pending.split(b"\n", 1)
                        text = line.decode(errors="replace")
                        elapsed = (time.monotonic_ns() - start) / 1e9
                        events.append({"seconds": elapsed, "line": text})
                        print(f"{elapsed:.6f} {text}", flush=True)
                        ready |= "TURNAROUND READY bytes=425984 checksum=0da11840" in text
                if (ready and not args.until_exit) or not selector.get_map():
                    break
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGINT)
        try:
            tail, _ = process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            tail, _ = process.communicate()
        for line in (pending + tail).decode(errors="replace").splitlines():
            elapsed = (time.monotonic_ns() - start) / 1e9
            events.append({"seconds": elapsed, "line": line, "drained_after_stop": True})
            print(f"{elapsed:.6f} {line}", flush=True)
    result = {"command": command, "ready": ready, "exit": process.returncode,
              "started_monotonic_ns": start,
              "elapsed": (time.monotonic_ns() - start) / 1e9, "events": events}
    Path(args.output).write_text(json.dumps(result, indent=2) + "\n")
    if not ready and not (args.until_exit and process.returncode == 0):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
