# Flash and erase cancellation

`probetron flash` and `probetron erase` hold the SSH input stream open until the rig command ends.
Flash sends a four-byte big-endian ELF length followed by that many bytes, then keeps the stream open.
The rig validates the complete upload before it opens the probe.

The rig monitors the remaining input for EOF and runs probe-rs in an owned process group.
When the client exits or its connection closes, the rig terminates the group, waits for cleanup, and releases the target lock.
The cleanup also removes flash uploads.
The rig does not pulse RUN after an interrupted download.
Normal downloads still verify the image before pulsing RUN, and probe-rs failures retain their exit status and diagnostics.

Ctrl-C and handled termination signals close the tether and stop the client's SSH process.
A hard client kill closes the pipe even when a multiplexed SSH master survives.
A network failure triggers cleanup when SSH detects the lost connection; cancellation is not instantaneous during an undetected network outage.
Cancellation does not restore partially erased or programmed firmware.
Flash a complete valid image to recover the target.

## Deploy both ends

Update the client and rig source together because flash requires the length-framed upload protocol.
A client or rig that uses EOF-delimited flash uploads is incompatible with this protocol.
This change needs no probe-rs binary update.

For a temporary rig source update, run this command from the updated checkout:

```sh
mise exec -- bb rig-dev-deploy --host probetron.home.arpa
```

The source overlay disappears on reboot.
Build and install a new SD image for a persistent update.
An erase already running under older rig code must finish or be stopped before the new lifecycle can take effect.

## Verification

`bb check` formats, lints, and runs the tests.
Cancellation integration tests connect the real client and rig shells through a local pipe, with stand-ins for SSH and hardware.
The tests terminate and hard-kill clients during both flash and erase, reject truncated uploads, and close the connection before helper startup.
They check that child processes stop, uploads disappear, RUN stays untouched, and a subsequent operation can acquire the target lock.
