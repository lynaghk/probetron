# Load, run, and monitor firmware

`probetron run` uploads an ELF, verifies the loaded image, starts the target, and streams RTT output over SSH.
The ELF's load addresses select flash or RAM.

```sh
probetron run --host probetron.home.arpa --chip RP235x firmware.elf
probetron run-ram --host probetron.home.arpa --chip RP235x firmware-ram.elf
```

SWD operations default to an 8 MHz request; `run-ram` defaults to 12 MHz.
An explicit `--speed-khz` overrides `PROBETRON_SPEED_KHZ`, which overrides the command default.
Use `--speed-khz 1000` for conservative recovery or targets and wiring that need a lower speed.
See [the turnaround report](turnaround.md) for the required probe-rs version and hardware qualification.

## RAM takeover

`run-ram` requires an ARM ELF whose entry point and every nonempty LOAD segment lie in `0x20000000..0x40000000`.
The rig rejects other images before opening the probe; probe-rs also checks the selected chip's memory map.
Generic `run` can use other memory layouts.

Link code, vectors, constants, and data into SRAM.
A normal flash-linked ELF cannot be relocated by changing the command.
Use `readelf -lW firmware-ram.elf` to inspect LOAD addresses.
The [Pico 2 W example](../examples/rp2350-turnaround/README.md) provides both linker layouts.

RAM execution takes over the target; it does not preserve the previous application's state.
A reset returns to installed flash firmware, and power loss discards the RAM image.
Probetron therefore does not pulse RUN after a RAM upload.

## Session lifetime

Both commands hold the target lock until the monitor ends.
Ctrl-C stops monitoring and releases the lock, but leaves firmware running.
`--reset-on-exit` instead pulses physical RUN and requires the reset wire.

The firmware must provide an RTT buffer for output.
No additional TCP service is opened, and `probetron log` contains operation events rather than a stored copy of RTT output.

## Deploy

Update both the local client and the rig.
A source overlay updates the interpreted rig code, not the compiled probe-rs executable:

```sh
mise exec -- bb rig-dev-deploy --host probetron.home.arpa
```

The overlay disappears on reboot or `bb rig-dev-reset`.
See [binary deployment](turnaround.md#deployment) for a temporary executable update.
Build and flash a new SD image for a persistent update.
