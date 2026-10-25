# Faster firmware turnaround

## Decision

Keep a small patch stack on upstream probe-rs `f6b571a3` rather than the old 0.32.0 fork.
The selected revision is `53b3b2ff` on [probetron/2026-10-25](https://github.com/lynaghk/probe-rs/tree/probetron/2026-10-25).
The submodule pins that revision, and the image build compiles it.
The measured binary used revision `2244c031`, whose source tree is identical; only commit metadata changed.

Flash, generic `run`, and other SWD operations default to an **8 MHz request**.
SRAM-only `run-ram` defaults to **12 MHz**.
Verification remains enabled.
Use `--speed-khz 1000` for conservative recovery or other targets and wiring that need a lower speed.

## What remains patched

Upstream's shared SWD implementation now handles accepted prefixes, posted AP reads, write drains, overrun detection, and reset-time WAIT recovery.
The old driver-specific implementations and timing instrumentation are removed.
Four focused commits remain:

- `cbb7cad3`: batch consecutive SPI reads within the 4096-byte transfer limit; retain prefix results and exact failure indices.
- `5fa65eab`: route exclusively owned SPI selectors without unrelated USB discovery.
- `74cd7ee7`: leave initially debugger-halted secondary cores parked until firmware resumes them.
- `53b3b2ff`: shrink retries after WAIT, grow after progress, and apply idle backoff only at the retry boundary.

The retry change lives in upstream's shared SWD layer rather than duplicating protocol recovery inside the SPI driver.
Its regression test bounds submitted batch sizes under sustained back-pressure and checks progress beyond the stall budget.
The patch stack is published for upstream review; it is not merged upstream and no pull request has been opened.

## Hardware comparison

Tests use a Raspberry Pi 4, Pico 2 W (`RP235x`), the existing wiring, and the standalone [turnaround workload](../examples/rp2350-turnaround/README.md).
Both firmware layouts contain 437,488 loadable bytes and report the payload checksum `0da11840` over RTT.
Every qualification run requires debugger verification and the exact READY checksum.

Pi-local measurements use cached ELFs and exclude SSH and upload.
The unmodified upstream flash trials loaded in a median 18.34 s, but two commands took about 53 s because of attachment delays.
The old qualified fork reached READY in medians of 11.19 s for flash and 3.21 s for RAM.
The selected version reaches READY in 12.07 s for flash and 4.06 s for RAM while using the current upstream implementation.
This retains most of the old flash speed; RAM execution costs about 0.85 s more than the old fork.

Unmodified upstream failed RAM execution through both the local tool and Probetron's public CLI.
The new multicore monitor treated core 1's intentional debugger halt as a firmware exit before READY.
The monitor patch fixes this without starting the parked core.

The selected binary passed 70 verified load/boot checks:

- Flash at 8 MHz: 10/10 idle and 10/10 CPU-loaded qualification trials; median command-to-READY times 12.07 s and 11.85 s.
- RAM at 12 MHz: 20/20 idle and 20/20 CPU-loaded qualification trials; medians 4.06 s and 2.49 s.
- Public CLI with default speeds: 3/3 flash and 3/3 RAM trials; medians 12.58 s and 4.61 s, including SSH and upload.
- Four additional smoke checks passed before and after those batches.

The public `flash` command and an independent full-image read-back at 1 MHz also passed.
The rig rejected a flash-linked ELF through `run-ram` before probe access.
The maximum sampled temperature was 70.6 °C, with no sampled throttling flags.

Read batching alone was not sufficient: one verified flash load took 133.55 s.
A second diagnostic run was deliberately interrupted and followed by a verified 1 MHz recovery.

The Pi's SPI parent clock varies with CPU load, so a requested speed is not a measured SWCLK frequency.
The harness records firmware-measured clocks, SPI dividers, temperature, and throttling during idle and four-worker CPU-loaded trials.
These results qualify this hardware and workload, not all ARM targets or wiring.
The 12 MHz SRAM default does not claim that 16 MHz is reliable.

## Deployment

Update the rig's compiled binary as well as its interpreted source.
A source overlay alone does not install these fixes.
From a native aarch64 Linux build host:

```sh
(cd vendor/probe-rs && cargo build --release --locked -p probe-rs-tools --bin probe-rs)
strip --strip-debug -o build/probe-rs-update vendor/probe-rs/target/release/probe-rs
export H=probetron.home.arpa
export KEY="$HOME/.cache/probetron/keys/$H.key"
scp -i "$KEY" build/probe-rs-update probetron@"$H":/tmp/probe-rs-update
ssh -i "$KEY" probetron@"$H" 'chmod 755 /tmp/probe-rs-update; sudo -n flock /run/probetron/target.lock sh -c "umount /usr/local/bin/probe-rs 2>/dev/null || true; mount --bind /tmp/probe-rs-update /usr/local/bin/probe-rs"'
mise exec -- bb rig-dev-deploy --host "$H"
```

Use the cached SSH key created by the Probetron client.
Upload each replacement to a new filename rather than overwriting a mounted or running executable.
Both overlays disappear on reboot.
Run `bb image` and flash the resulting SD image for a persistent update.

## Repeat the qualification

Build the example and run `prepare.py` as described in its README.
Copy `measure.py`, `qualify.py`, `target/flash.elf`, and `target/ram.elf` into one directory on the rig.
The Pi-local harness holds the target lock, stops stress workers on exit, and restores flash at 1 MHz after a failed flash trial.
For example:

```sh
sudo python3 qualify.py --binary /usr/local/bin/probe-rs --mode flash --speed 8000 --count 10 --label flash-idle
sudo python3 qualify.py --binary /usr/local/bin/probe-rs --mode flash --speed 8000 --count 10 --cpu --label flash-cpu
sudo python3 qualify.py --binary /usr/local/bin/probe-rs --mode ram --speed 12000 --count 20 --label ram-idle
sudo python3 qualify.py --binary /usr/local/bin/probe-rs --mode ram --speed 12000 --count 20 --cpu --label ram-cpu
```

Cool the Pi between CPU-loaded batches.
The harness checks its 75 °C guard before each trial, not continuously.
For end-to-end timing, run `measure.py --output trial.json -- bin/probetron run ...` or `run-ram ...` from the client.
Timings measure receipt of RTT output, not the exact target instruction; the monitor is deliberately interrupted after READY.

An independent full-image read-back does not reprogram the target:

```sh
sudo flock /run/probetron/target.lock probe-rs verify --chip RP235x --probe 0:0:/dev/spidev0.0 --speed 1000 flash.elf
```

Reset afterward if verification leaves the target halted.
See [the qualification manifest](../examples/rp2350-turnaround/results/README.md) for binary identities and summary results.
Raw trial output is not checked in.
