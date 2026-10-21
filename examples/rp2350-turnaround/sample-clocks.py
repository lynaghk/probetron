#!/usr/bin/env python3
"""Sample Pi 4 firmware clocks during a separate public-CLI experiment."""
import json
from pathlib import Path
import sys
import threading
import time
from qualify import sample


def main():
    output, seconds = sys.argv[1], float(sys.argv[2])
    stop = threading.Event()
    samples = []
    worker = threading.Thread(target=sample, args=(stop, samples))
    worker.start()
    try:
        time.sleep(seconds)
    finally:
        stop.set()
        worker.join()
        Path(output).write_text(json.dumps(samples, indent=2) + "\n")


if __name__ == "__main__":
    main()
