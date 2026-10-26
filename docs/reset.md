# Reset

`probetron reset` resets through SWD by default, without a reset-pin connection.
Pass `--chip` or set `PROBETRON_CHIP` to identify the target.
Use `--method swd` to select this behavior explicitly:

```sh
probetron reset --chip RP2350
```

The explicit form is:

```sh
probetron reset --method swd --chip RP2350
```

SWD reset requires a chip name from `--chip` or `PROBETRON_CHIP`.
Use `--speed-khz` or `PROBETRON_SPEED_KHZ` to change the default 8000 kHz clock request.
Use `probetron reset --method pin` to pulse the DUT reset pin through GPIO26.
Pin reset rejects explicit `--chip` and `--speed-khz` options and ignores their environment defaults.

SWD reset returns the exit status from `probe-rs reset --method core` and preserves its diagnostic output.
Exit status 0 means the target-specific reset sequence completed successfully, not that the firmware booted correctly.
A nonzero status means the sequence failed; the target might already have reset before the failure.
The reset scope depends on the target and can differ from a reset-pin pulse.
Probetron does not retry or fall back to pin reset.

The rig command accepts the same reset options but does not read client environment defaults:

```sh
probetron-rig reset --method swd --chip RP2350 --speed-khz 8000
```

## Reset after flashing

`probetron flash` uses SWD reset by default.
The DUT RUN pin does not need to be wired to the rig.
Use `--reset-method swd` to select this behavior explicitly:

```sh
probetron flash --chip RP2350 --reset-method swd firmware.elf
```

Use `--reset-method pin` to select a wired reset through GPIO26.
The rig command accepts the same `--reset-method pin|swd` option.
Flash verifies the download before it runs exactly one selected reset method.
A failed download does not reset the target.
A failed reset returns its failure status without retrying or falling back to the other method.
SWD flash reset does not require GPIO26 or its GPIO device.

The rig cannot detect whether GPIO26 is physically connected to RUN, so reset selection is explicit.
Update both the client and rig source before using the flash reset option.
Session `--reset-on-exit` continues to use the reset pin.
