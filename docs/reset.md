# Reset

`probetron reset` pulses the DUT reset pin through GPIO26.
Use `--method pin` to select this behavior explicitly.

To reset through SWD without a reset-pin connection, run:

```sh
probetron reset --method swd --chip RP2350
```

SWD reset requires a chip name from `--chip` or `PROBETRON_CHIP`.
Use `--speed-khz` or `PROBETRON_SPEED_KHZ` to change the default 8000 kHz clock request.
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

Flash's post-write reset and session `--reset-on-exit` continue to use the reset pin.
