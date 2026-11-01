# Two SWD connections

The Raspberry Pi 4 supports two independent SWD connections on SPI0 and SPI1.
SPI0 also supports the existing USB and UART serial channels.
SPI1 supports SWD only: flash, erase, reset, info, DAP debugging, and firmware execution with RTT monitoring.
RTT requires compatible firmware.

## Select a connection

Commands default to SPI0 and never fall back to another connection.
An explicit `--spi` overrides `PROBETRON_SPI`, which overrides the SPI0 default.
A disconnected or busy connection causes an error.

```sh
probetron flash --chip RP235x firmware.elf
probetron flash --spi 1 --chip STM32F103C8 firmware.elf
probetron reset --spi 1 --method pin
probetron debug --spi 1
probetron run --spi 1 --chip STM32F103C8 firmware.elf
probetron log --spi 1
```

Each connection has an exclusive lock, its own operation records and uploads, and a separate debugger listener.
Operations on different connections can run concurrently.
Debugging or RTT monitoring blocks flashing on the same connection.
Reset-on-exit affects only the selected connection.

`connect` supports only SPI0's USB/UART channels, including its optional RTT decoder.
Use `run` for SPI1 firmware loading and RTT monitoring, or `debug` for debugger access.
There is no standalone SPI1 RTT attach command.

`status` shows both connections without opening either SWD bus.
An explicit selection or `PROBETRON_SPI` restricts status to one connection.
The EDN aggregate has the form `{:connections {0 {...} 1 {...}}}`.

## Wire the second DUT

Keep the existing SPI0 wiring unchanged.
Connect the second DUT as follows, using physical header pin numbers:

| Pi pin  | GPIO   | Second DUT connection |
| ------- | ------ | --------------------- |
| 40      | GPIO21 | SWCLK                 |
| 38      | GPIO20 | Through 1 kΩ to SWDIO |
| 35      | GPIO19 | Directly to SWDIO     |
| 36      | GPIO16 | RESET / RUN           |
| Any GND | —      | GND                   |

Use a separate SWDIO resistor for each connection.
Use 3.3 V-compatible signals and provide suitable power for both DUTs.
Do not connect two targets to SPI0's shared clock and data pins.
SPI chip select does not isolate SWD targets.

The rig image enables `spi1-1cs`, which reserves GPIO18 on physical pin 12 for the unused chip-select signal.
Leave that pin unconnected.
The image exposes `/dev/spidev1.0` and the discovery alias `/dev/spidev_swd1`.
SPI0 retains `/dev/spidev0.0`, `/dev/spidev_swd0`, and GPIO26 on physical pin 37 for reset.

## Deploy and validate

Build and flash an updated image with `bb image` and the normal SD-card installation procedure.
SPI1 requires the rebuilt probe-rs binary as well as the image's SPI1 overlay and device permissions.
A source-only deployment does not install these changes.

The Pi's `spi-bcm2835aux` driver rejects SPI mode 3 because it does not support CPHA.
The pinned probe-rs backend tries mode 3 first and retries with mode 0 only for an invalid-input error.
Both modes sample on rising edges and drive MOSI on falling edges.
SPI0 retains mode 3, and other I/O errors remain errors.

Start hardware qualification at a conservative speed:

```sh
probetron info --spi 1 --speed-khz 1000
probetron flash --spi 1 --chip STM32F103C8 --speed-khz 1000 firmware.elf
```

## Hardware qualification

The connected Pi 4 rig identified an RP2350 on SPI1 at both 1 MHz and the default 8 MHz.
The [turnaround workload](../examples/rp2350-turnaround/README.md) passed flash verification at 8 MHz and SRAM verification at 12 MHz.
The public `run` and `run-ram` commands both reported `TURNAROUND READY bytes=425984 checksum=0da11840` over RTT.
Sample command-to-READY times were about 13 s from flash and 4 s from RAM.
The full public `erase` command completed, and read-back confirmed that the first 512 KiB contained only erased bytes.
The generic `RP235x` target maps 64 MiB, so full erase iterates many sectors and can take several minutes.

A DAP client attached through `probetron debug --spi 1`, paused core 0, read its Cortex-M33 CPUID, wrote and read a RAM word, restored the word, and resumed execution.
SWD reset restarted the installed flash firmware after a RAM run.
SPI0's existing USB monitoring session remained active during these SPI1 operations.

The qualified setup has no GPIO16 reset wire.
SWD reset works without this wire.
Connect physical pin 36 to RESET / RUN before using `--method pin` or `--reset-on-exit` on SPI1.
These checks qualify the connected board and wiring, not every target or SPI speed.
