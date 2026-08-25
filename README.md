# Probetron

Probetron turns one Raspberry Pi 4B into a network appliance for one physically connected RP2350 device under test.
Lab clients on macOS and Linux flash firmware, erase or reset the target, debug it over DAP, read RTT logs, and bridge the DUT serial channel without an interactive login shell on the Pi.

The project stays project-agnostic so that it can move into its own Git repository unchanged.
Everything it owns lives under `probetron/`.

## Layout

| Path                            | Contents                                                       |
| ------------------------------- | -------------------------------------------------------------- |
| `bin/probetron`                 | public client entry point                                      |
| `bin/probetron-rig`             | rig entry point that the client reaches over SSH               |
| `src/probetron/operation.clj`   | pure operation model, validators, and rig command construction |
| `src/probetron/version.clj`     | the release version that every role reports                    |
| `src/probetron/client/cli.clj`  | pure parser of the public command line                         |
| `src/probetron/rig/config.clj`  | pure parser of the rig-only SSH protocol                       |
| `src/probetron/client/main.clj` | imperative shell of the client                                 |
| `src/probetron/rig/main.clj`    | imperative shell of the rig                                    |
| `test/probetron/`               | `clojure.test` namespaces that the runner discovers            |
| `VERSION`                       | the release version, which `probetron.version` repeats         |

Both entry points resolve their own symlinks, then load `../src` in a development checkout or `../lib` in an installation.

## Development

`mise.toml` and `mise.lock` pin Babashka and clj-kondo, and Babashka runs every task, so the project needs no other tool.

```sh
mise install            # once, to get the pinned Babashka and clj-kondo
mise exec -- bb check   # lint and test
mise exec -- bb tasks   # every task with its description
```

Drop the `mise exec --` prefix once the pinned tools are on your path.
`bb tasks` is the whole task list, so no command lives in two places.

`bb test` discovers every `test/**/*_test.clj` namespace, so a new test file needs no registration.

## Public commands

```text
probetron info    --host <host> [--format <text|edn>]
probetron status  --host <host> [--format <text|edn>]
probetron flash   --host <host> --chip <chip> [--speed-khz <speed>] <elf>
probetron erase   --host <host> --chip <chip> [--speed-khz <speed>]
probetron reset   --host <host>
probetron connect --host <host> --channel <usb|uart> [--baud <baud>] [--usb-wait-seconds <seconds>] [--local-port <port>] [--rtt <elf> --chip <chip> [--speed-khz <speed>]] [--pty] [--reset-on-exit]
probetron debug   --host <host> [--local-port <port>] [--reset-on-exit]
```

Target-specific values also come from the environment, and an explicit option always wins.

| Variable                     | Default for          | Value  |
| ---------------------------- | -------------------- | ------ |
| `PROBETRON_HOST`             | `--host`             | —      |
| `PROBETRON_CHIP`             | `--chip`             | —      |
| `PROBETRON_SPEED_KHZ`        | `--speed-khz`        | 1000   |
| `PROBETRON_UART_BAUD`        | `--baud`             | 115200 |
| `PROBETRON_USB_WAIT_SECONDS` | `--usb-wait-seconds` | 10     |

Exit status 0 reports success, 64 reports a usage error, and 75 reports a rig that is busy with another operation.

## Rig protocol

The client builds the rig command from a validated operation, so the rig sees a fixed vocabulary and no client path.

```text
probetron-rig info    [--format <text|edn>]
probetron-rig status  [--format <text|edn>]
probetron-rig flash   --chip <chip> [--speed-khz <speed>]
probetron-rig erase   --chip <chip> [--speed-khz <speed>]
probetron-rig reset
probetron-rig connect --channel <usb|uart> [--baud <baud>] [--usb-wait-seconds <seconds>] [--rtt --chip <chip> [--speed-khz <speed>]] [--reset-on-exit]
probetron-rig debug   [--reset-on-exit]
```

`flash` and `connect --rtt` read one bounded ELF file from standard input.
The rig rejects `--host`, `--local-port`, and `--pty`, which belong to the client alone.

## Current state

The operation model, both command lines, and both entry points parse, validate, and refuse malformed input.
Neither entry point carries an operation to the hardware yet.
