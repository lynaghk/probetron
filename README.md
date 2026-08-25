# Probetron

Probetron turns one Raspberry Pi 4B into a network appliance for one physically connected RP2350 device under test.
Lab clients on macOS and Linux flash firmware, erase or reset the target, debug it over DAP, read RTT logs, and bridge the DUT serial channel without an interactive login shell on the Pi.

The project stays project-agnostic so that it can move into its own Git repository unchanged.
Everything it owns lives under `probetron/`.

## Layout

| Path                              | Contents                                                          |
| --------------------------------- | ----------------------------------------------------------------- |
| `bin/probetron`                   | public client entry point                                         |
| `bin/probetron-rig`               | rig entry point that the client reaches over SSH                  |
| `src/probetron/operation.clj`     | pure operation model, validators, and rig command construction    |
| `src/probetron/version.clj`       | the release version that every role reports                       |
| `src/probetron/client/cli.clj`    | pure parser of the public command line                            |
| `src/probetron/rig/config.clj`    | pure parser of the rig-only SSH protocol                          |
| `src/probetron/rig/lifecycle.clj` | pure ownership model and appliance command lines                  |
| `src/probetron/client/main.clj`   | imperative shell of the client                                    |
| `src/probetron/rig/main.clj`      | imperative shell of the rig                                       |
| `src/probetron/rig/runner.clj`    | imperative shell that owns the target lock and the process groups |
| `test/probetron/`                 | `clojure.test` namespaces that the runner discovers               |
| `VERSION`                         | the release version, which `probetron.version` repeats            |

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

Exit status 0 reports success, 1 reports a failed operation, 64 reports a usage error, 69 reports a missing rig resource, and 75 reports a rig that is busy with another operation.

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

Production installs the rig entry point as `/usr/local/sbin/probetron-rig`, which resolves its own symlink and loads `/usr/local/lib/probetron`.

## Target ownership

One `flock` transaction owns the target hardware at a time.
`/usr/bin/flock` takes `/run/probetron/target.lock` without waiting and holds it through a `/bin/cat` that lives exactly as long as the operation, so the lock also disappears when the rig dies.
`info`, `flash`, `erase`, and `reset` hold the lock for one hardware operation, `connect` and `debug` hold it until the outer SSH command ends, and `status` never takes it.

After it takes the lock, the rig writes `/run/probetron/active.edn` through a temporary neighbour, so a reader sees the whole record or none of it.

```edn
{:command :connect :pid 4213 :started-at "2026-08-25T11:23:28.036818843Z"}
```

A conflicting operation reads that record, names its owner, and fails at once with status 75.
`status` reports the same record without opening the hardware, and it drops a record that a free lock leaves behind.

```text
lock: held
active command: connect
active pid: 4213
active since: 2026-08-25T11:23:28.036818843Z
```

Every long-running helper starts through `/usr/bin/setsid`, which gives it its own process group.
Cleanup runs once, whether the operation finished, the client disconnected, or `SIGINT`, `SIGTERM`, or `SIGHUP` arrived.
It sends `SIGTERM` and then `SIGKILL` to those groups alone with `/bin/kill`, runs the reset that `--reset-on-exit` asked for, drops the active record, and gives the lock back, in that order.
The default path never touches the target, so a session leaves the DUT as it found it.

## Current state

The operation model, both command lines, and both entry points parse, validate, and refuse malformed input.
The rig owns the target lock, the active record, and the process groups of one operation, and `status` reports what owns the target.
No operation reaches the hardware yet, so the rig reports the operation it holds the target for.
