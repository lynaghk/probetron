# Pico 2 W turnaround workload

This standalone firmware targets the RP2350 ARM cores.
It initializes clocks, emits plain RTT BOOT and READY markers, and keeps core 0 active.
It does not initialize wireless, USB, or the LED.
The [official HAL example](https://github.com/rp-rs/rp-hal/blob/main/rp235x-hal-examples/src/bin/blinky.rs) supplies the boot-block and clock setup.

Both builds retain a varied 416 KiB payload and report `TURNAROUND READY bytes=425984 checksum=0da11840`.
Debugger verification covers the entire loaded image; the firmware checksum independently checks the payload.
The toolchain and dependencies are pinned locally, and optimization settings apply only to this example.

## Build

Run from this directory:

```sh
cargo build --release --locked --target-dir target/flash
cargo build --release --locked --features ram --target-dir target/ram
python3 prepare.py
```

`prepare.py` computes the expected checksum and creates `target/flash.elf` and `target/ram.elf` with DWARF removed.
It checks that every LOAD byte and address is unchanged and that `_SEGGER_RTT` remains defined.
Do not strip all symbols, because RTT discovery needs that symbol.

`memory-flash.x` places executable content and data initializers in flash.
`memory-ram.x` places them in the first 448 KiB of SRAM and reserves the next 64 KiB for writable state and stack.
The complete image contains 437,488 loadable bytes.

## Run

From the repository root, with the updated rig binary:

```sh
bin/probetron run --host probetron.home.arpa --chip RP235x examples/rp2350-turnaround/target/flash.elf
bin/probetron run-ram --host probetron.home.arpa --chip RP235x examples/rp2350-turnaround/target/ram.elf
```

Both commands verify before starting firmware.
A physical reset ends RAM takeover and starts installed flash firmware.
See [the turnaround report](../../docs/turnaround.md) for measurements, deployment, and repeatable qualification commands.

## Checks

```sh
cargo fmt --check
cargo clippy --release --locked -- -D warnings
cargo clippy --release --locked --features ram -- -D warnings
```
