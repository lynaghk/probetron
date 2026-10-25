# Qualification manifest

`manifest.json` identifies the firmware and compared probe-rs binaries and summarizes qualification.
It preserves the measured binaries' original commit identities and records the source-equivalent selected revision after commit metadata normalization.
Raw trial output is not checked in.
See [the report](../../../docs/turnaround.md) for the decision and qualification limits.

To summarize locally collected trials, run from the example directory:

```sh
python3 summarize.py /path/to/trial.json
```

A successful execution requires the exact marker `TURNAROUND READY bytes=425984 checksum=0da11840` and debugger verification.
The harness deliberately interrupts a successful monitor after READY, so its final exit status can reflect that interrupt.
