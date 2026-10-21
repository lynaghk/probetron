#!/usr/bin/env python3
"""Pi-local verified trials with measured firmware clocks and conservative recovery."""
import argparse
import array
import fcntl
import hashlib
import json
import mmap
import os
from pathlib import Path
import subprocess
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", default="/usr/local/bin/probe-rs")
    parser.add_argument("--mode", choices=["ram", "flash"], required=True)
    parser.add_argument("--speed", type=int, required=True)
    parser.add_argument("--count", type=int, default=3)
    parser.add_argument("--cpu", action="store_true")
    parser.add_argument("--label", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    os.chdir(root)
    results = root / "results"
    results.mkdir(exist_ok=True)
    lock = open("/run/probetron/target.lock", "w")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    workers = []
    stop = threading.Event()
    samples = []
    sampler = threading.Thread(target=sample, args=(stop, samples))
    sampler.start()
    target = ["--chip", "RP235x", "--probe", "0:0:/dev/spidev0.0", "--protocol", "swd"]

    def trial(label, mode, speed, timeout=180):
        output = results / f"{label}.json"
        command = ["python3", "measure.py", "--output", str(output), "--timeout", str(timeout),
                   "--", args.binary, "run", *target, "--speed", str(speed),
                   "--verify", f"{mode}.elf"]
        binary_sha256 = hashlib.sha256(Path(args.binary).read_bytes()).hexdigest()
        begin = time.monotonic()
        result = subprocess.run(command)
        data = json.loads(output.read_text())
        data.update(mode=mode, speed_khz=speed, verify=True, cpu=bool(workers),
                    binary_sha256=binary_sha256,
                    samples=[s for s in samples if s["monotonic"] >= begin])
        output.write_text(json.dumps(data, indent=2) + "\n")
        return result.returncode == 0

    def stop_workers():
        for worker in workers:
            worker.terminate()
        for worker in workers:
            worker.wait()
        workers.clear()

    try:
        if args.cpu:
            code = "import hashlib,os; os.nice(10); b=bytes(1024*1024)\nwhile True: hashlib.sha256(b).digest()"
            workers.extend(subprocess.Popen(["python3", "-c", code]) for _ in range(4))
            time.sleep(3)
        for index in range(args.count):
            if samples and samples[-1].get("temperature_mC", 0) >= 75000:
                raise RuntimeError("Pi reached 75 C; stop testing")
            if not trial(f"{args.label}-{index + 1}", args.mode, args.speed):
                stop_workers()
                if args.mode == "flash":
                    if not trial(f"{args.label}-recovery", "flash", 1000, timeout=240):
                        raise RuntimeError("Conservative flash recovery failed; stop testing")
                raise SystemExit(1)
    finally:
        stop_workers()
        stop.set()
        sampler.join()


def sample(stop, samples):
    fd = os.open("/dev/mem", os.O_RDONLY | os.O_SYNC)
    spi = mmap.mmap(fd, 4096, flags=mmap.MAP_SHARED, prot=mmap.PROT_READ, offset=0xfe204000)
    os.close(fd)
    vcio = os.open("/dev/vcio", os.O_RDWR)
    try:
        while not stop.is_set():
            clock = array.array("I", [32, 0, 0x30047, 8, 0, 4, 0, 0])
            fcntl.ioctl(vcio, 0xc0086400, clock, True)
            throttled = array.array("I", [28, 0, 0x30046, 4, 0, 0, 0])
            fcntl.ioctl(vcio, 0xc0086400, throttled, True)
            divider = int.from_bytes(spi[8:12], "little") or 65536
            assert clock[1] == 0x80000000 and clock[4] == 0x80000008
            samples.append({"monotonic": time.monotonic(), "measured_core_hz": clock[6],
                            "divider": divider, "inferred_swclk_hz": clock[6] / divider,
                            "throttled": throttled[5],
                            "temperature_mC": int(Path("/sys/class/thermal/thermal_zone0/temp").read_text())})
            stop.wait(0.2)
    finally:
        spi.close()
        os.close(vcio)


if __name__ == "__main__":
    main()
