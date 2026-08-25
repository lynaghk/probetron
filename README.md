# Probetron

Probetron turns one Raspberry Pi 4B into a network appliance for one physically connected RP2350 device under test.
Lab clients on macOS and Linux flash firmware, erase or reset the target, debug it over DAP, read RTT logs, and bridge the DUT serial channel without an interactive login shell on the Pi.

The project stays project-agnostic so that it can move into its own Git repository unchanged.
Everything it owns lives under `probetron/`.

## Layout

| Path                                     | Contents                                                          |
| ---------------------------------------- | ----------------------------------------------------------------- |
| `bin/probetron`                          | public client entry point                                         |
| `bin/probetron-rig`                      | rig entry point that the client reaches over SSH                  |
| `src/probetron/operation.clj`            | pure operation model, validators, and rig command construction    |
| `src/probetron/version.clj`              | the release version that every role reports                       |
| `src/probetron/client/cli.clj`           | pure parser of the public command line                            |
| `src/probetron/client/command.clj`       | pure remote command, SSH argv, and key cache paths                |
| `src/probetron/client/report.clj`        | pure client information record and its two output forms           |
| `src/probetron/rig/config.clj`           | pure parser of the rig-only SSH protocol                          |
| `src/probetron/rig/lifecycle.clj`        | pure ownership model and appliance command lines                  |
| `src/probetron/rig/hardware.clj`         | pure description of the fixed target slot and its command lines   |
| `src/probetron/rig/elf.clj`              | pure validator of one uploaded firmware image                     |
| `src/probetron/rig/information.clj`      | pure information record and its two output forms                  |
| `src/probetron/client/main.clj`          | imperative shell of the client                                    |
| `src/probetron/client/key.clj`           | imperative shell that refreshes the cached rig key                |
| `src/probetron/client/session.clj`       | imperative shell of the short client operations                   |
| `src/probetron/client/tunnel.clj`        | imperative shell of the long client sessions                      |
| `src/probetron/rig/main.clj`             | imperative shell of the rig                                       |
| `src/probetron/rig/runner.clj`           | imperative shell that owns the target lock and the process groups |
| `src/probetron/rig/target.clj`           | imperative shell of `info`, `flash`, `erase`, and `reset`         |
| `src/probetron/rig/session.clj`          | imperative shell of the locked `connect` and `debug` sessions     |
| `src/probetron/provisioning/package.clj` | pure release plan and the shell that writes the release archive   |
| `src/probetron/provisioning/archive.clj` | pure deterministic tar, gzip, and SHA-256 encoder                 |
| `src/probetron/provisioning/image.clj`   | the rig image build driver that `bb image` runs                   |
| `test/probetron/`                        | `clojure.test` namespaces that the runner discovers               |
| `VERSION`                                | the release version, which `probetron.version` repeats            |

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
The client returns exactly what the rig returned, and 255 means that SSH never reached the rig at all.

## Client authentication

The rig image generates one SSH key at build time and serves its private half over plain HTTP, so the lab LAN is the whole trust boundary.
Every operation fetches that key again before it opens SSH, which is why a rebuilt or replaced Pi at the same address needs no cleanup on any client.

```text
http://<host>/probetron_key  ->  ${XDG_CACHE_HOME:-$HOME/.cache}/probetron/keys/<host>.key
```

Each host keeps its own cache file, and unchanged bytes leave that file alone.
Changed bytes arrive through a temporary neighbour with mode `0600` and one atomic rename, so SSH never reads half a key.
An HTTP failure, an empty answer, an answer that is not a private key, a cache file that anyone else can read, and a failed replacement all stop the operation, even when an older copy is still there.

## Client operations

The client wraps the validated rig command in one SSH invocation that trusts no persistent host key.

```sh
ssh -i <cache> -T \
  -o BatchMode=yes -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null -o GlobalKnownHostsFile=/dev/null \
  -o LogLevel=ERROR \
  probetron@<host> 'sudo -n /usr/local/sbin/probetron-rig flash --chip RP235x --speed-khz 1000'
```

Both known-host files are `/dev/null` and the log level drops the new-host and changed-host warnings that this provokes, while every SSH error and all remote stderr still reach the client.
`sudo -n /usr/local/sbin/probetron-rig` is the only program the client ever runs remotely.
The remote command carries every token quoted for a POSIX shell, so no public value can become a second token however it passed validation, and `flash` streams its ELF file to remote standard input rather than naming a client path.

Client `info` adds what the client itself is and where it cached the key to the report of the rig.

```text
client probetron: 0.1.0
client babashka: 1.13.219
client key: /home/bench/.cache/probetron/keys/pi.lab.key
probetron: 0.1.0
babashka: 1.13.219
probe-rs: probe-rs 0.32.0
os: Debian GNU/Linux 13 (trixie)
hostname: probetron-01
machine-id: 0123456789abcdef0123456789abcdef
probe: 0:0:/dev/spidev0.0 swd
```

`--format edn` prints the same facts as `{:client {:probetron ... :babashka ... :key ...} :rig {...}}`, where the rig map is the record that `probetron-rig info --format edn` wrote.

## Client sessions

`connect` holds the rig target until the client lets go.
The client forwards the rig byte service to one client loopback port and prints where a host program finds it.

```sh
ssh -i <cache> -T \
  -o BatchMode=yes ... -o LogLevel=ERROR \
  -L 127.0.0.1:45678:127.0.0.1:5555 -o ExitOnForwardFailure=yes \
  probetron@<host> 'sudo -n /usr/local/sbin/probetron-rig connect --channel uart --baud 115200'
```

```text
tcp://127.0.0.1:45678
```

`--local-port` names that port, and a session without one reserves a free ephemeral port on the client loopback, so an ordinary session needs no port bookkeeping at all.
The client prints the endpoint as soon as it starts SSH, so a host program has somewhere to go while the forward and the rig target are still opening.
`ExitOnForwardFailure=yes` ends a whole session whose forward never appeared, so an endpoint that reaches nothing ends with its session instead of waiting for a host program.
Both rig streams stay attached to the client, so probe-rs diagnostics and decoded RTT text arrive unchanged, while the optional RTT ELF travels the other way on standard input.

`--pty` presents the same endpoint as a pseudo-terminal, which is what `screen`, `minicom`, and every other host program that wants a serial device asks for.

```sh
socat PTY,link=<link>,raw,echo=0,wait-slave TCP:127.0.0.1:45678,retry=30,interval=1
```

The link lives in a private volatile directory that nobody else may enter, and the client prints its path under the TCP endpoint.
socat waits for a host program to open the terminal before it connects, so nothing occupies the one rig byte client until somebody reads DUT bytes.
A client without socat keeps the whole session, names the packages that install socat, and leaves the printed TCP endpoint usable.
A pseudo-terminal that closes is a lost presentation alone: the client says so, and the session keeps the target.

Cleanup runs once, whether the rig command ended or `SIGINT`, `SIGTERM`, or `SIGHUP` arrived.
It stops the pseudo-terminal helper, removes the private link directory, and ends the outer SSH process, which hangs the remote command up and is what gives the target lock back.
The client itself never touches the DUT, because `--reset-on-exit` travels to the rig inside the remote command alone.

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

## Target operations

The one DUT slot never moves, so the rig inlines it.

| Resource               | Value                     |
| ---------------------- | ------------------------- |
| SPI device             | `/dev/spidev0.0`          |
| probe selector         | `0:0:/dev/spidev0.0`      |
| SWD SPI alias          | `/dev/spidev_swd*`        |
| GPIO chip and RUN line | `/dev/gpiochip0`, GPIO26  |
| UART device            | `/dev/ttyAMA0`            |
| DUT USB device         | `/dev/probetron-dut`      |
| probe-rs               | `/usr/local/bin/probe-rs` |
| gpioset                | `/usr/bin/gpioset`        |
| socat                  | `/usr/bin/socat`          |
| byte service           | `127.0.0.1:5555`          |
| DAP service            | `127.0.0.1:50000`         |
| upload directory       | `/run/probetron/uploads`  |
| ELF limit              | 64 MiB                    |

`info` holds the target lock while it reports the rig and asks probe-rs what sits on the SWD bus.

```text
probetron: 0.1.0
babashka: 1.13.219
probe-rs: probe-rs 0.32.0
os: Debian GNU/Linux 13 (trixie)
hostname: probetron-01
machine-id: 0123456789abcdef0123456789abcdef
probe: 0:0:/dev/spidev0.0 swd
target:
  ARM Chip with debug port ...
```

`--format edn` prints the same facts as one map with the keys `:probetron`, `:babashka`, `:probe-rs`, `:os`, `:rig`, `:probe`, and `:target`.

`flash` reads the ELF from standard input into a unique file under the upload directory.
It stops one byte past the limit, so an oversized upload never fills the volatile filesystem.
It then reads the ELF32 little-endian header, requires the header, program-entry, and section-entry sizes of the ELF specification and an ARM or RISC-V machine, and refuses a table or a load segment that reads outside the upload.
All of that happens before probe-rs opens the target, and the upload disappears on every exit path.

```text
probe-rs download --probe 0:0:/dev/spidev0.0 --chip RP235x --protocol swd --speed 1000 --verify <upload>
```

Only a verified download pulses RUN, so a failed flash leaves the target where it was.
`erase` delegates once to `probe-rs erase` and gives back its status without a second destructive attempt.
`reset` never asks probe-rs: `gpioset --chip /dev/gpiochip0 --hold-period 100ms 26=0` holds RUN low for 100 ms and releases it when it exits.

A missing SPI device, GPIO chip, or executable stops the operation before any process starts, and the diagnostic names the resource and the repair.

## Byte and RTT sessions

`connect` owns the target until the outer SSH command ends.
It publishes the DUT byte channel on Pi loopback alone through one `socat` listener.

```text
socat TCP-LISTEN:5555,bind=127.0.0.1,reuseaddr,fork,max-children=1 FILE:/dev/ttyAMA0,raw,echo=0,b115200
```

`max-children=1` allows one byte client at a time, and `fork` accepts the next client as soon as that one leaves, so a byte client reconnects for as long as the session lives.
`fork` also opens the channel address again for every accepted connection, so a DUT that re-enumerated over USB resolves the stable `/dev/probetron-dut` path once more.

The UART channel takes the requested bit rate and the USB channel takes none.
The UART device belongs to the image, so a missing `/dev/ttyAMA0` fails at once, while `/dev/probetron-dut` appears only after the DUT enumerates, so the USB channel waits up to `--usb-wait-seconds` for it.
A channel device that is missing or that the rig cannot read names itself, its expected path, and the wiring or image rule that repairs it.

`--rtt` decodes the logs of the firmware the target already runs.
The ELF arrives over the same bounded volatile upload path as a flash and validates before any hardware opens.

```text
probe-rs attach --probe 0:0:/dev/spidev0.0 --chip RP235x --protocol swd --speed 1000 <upload>
```

`attach` never downloads and never resets, so RTT joins a running target.
The decoder and the listener are two owned process groups that know nothing of each other: DUT bytes stay on the TCP service, decoded RTT text stays on rig standard output and standard error, and a decoder that stops leaves the bridge usable.

The session ends when its listener ends, when the client disconnects, or when a handled signal arrives.
Cleanup then removes both process groups and the RTT upload, and it leaves the target alone unless `--reset-on-exit` asked for one best-effort reset.

## DAP sessions

`debug` owns the target until the outer SSH command ends.
It discovers the SWD bus, announces the probe selector that a DAP client request repeats, and serves DAP on Pi loopback alone.

```text
probe: 0:0:/dev/spidev_swd0 swd
probe-rs dap-server --port 50000 --ip 127.0.0.1
```

Discovery accepts `/dev/spidev_swd*` alone, which the image udev rule gives to the one SPI bus that carries SWD, so a request that names a probe reaches that bus and no other SPI device of the Pi.
probe-rs serves one DAP client after another whenever `--single-session` is absent, so an editor disconnects and connects again while the rig keeps the target lock and the DUT keeps its state.
Chip, speed, ELF, SVD, source, launch, and attach configuration all travel inside the DAP client request, which is what lets the probe-rs editor integration upload a client-local ELF and resolve client-local source against a rig that knows no project.

The client forwards that server to one client loopback port and prints the endpoint that an editor connects to.

```sh
ssh -i <cache> -T \
  -o BatchMode=yes ... -o LogLevel=ERROR \
  -L 127.0.0.1:45678:127.0.0.1:50000 -o ExitOnForwardFailure=yes \
  probetron@<host> 'sudo -n /usr/local/sbin/probetron-rig debug'
```

```text
tcp://127.0.0.1:45678
```

`--local-port` names that port and a session without one reserves a free ephemeral port, exactly as `connect` does.
The outer SSH process then lives until the client ends it, so no DAP disconnect ever releases the target lock.
Cleanup reaps the whole DAP process group and leaves the target alone, unless `--reset-on-exit` asked for one best-effort reset, which runs after that group has stopped.

## Release archive

`bb package` and `mise run package` write one release archive and its checksum under `build/`.

```sh
mise run package
```

```text
probetron: 0.1.0
archive: build/probetron-0.1.0.tar.gz
checksum: build/probetron-0.1.0.tar.gz.sha256
sha256: <64 hexadecimal digits>
```

The `VERSION` file names the release, and `probetron.version` must repeat it, so one edit can never leave a release half renamed.
`--tag` states which release the archive belongs to and passes only when it names that version, with or without its leading `v`.

```sh
bb package --tag v0.1.0
```

The archive is deterministic: every member carries mode 0644 or 0755, owner 0, and modification time 0, members arrive in one sorted order, and gzip records no name and no timestamp.
Two runs over the same sources therefore write identical bytes, so anybody can rebuild a tag and compare its digest with the published `probetron-<version>.tar.gz.sha256`, whose one line is what `sha256sum -c` reads.

| Archive path        | Contents                                                |
| ------------------- | ------------------------------------------------------- |
| `bin/probetron`     | the public client entry point, mode 0755                |
| `bin/probetron-rig` | the rig entry point, mode 0755                          |
| `lib/probetron/`    | the relocatable source tree that both entry points load |
| `VERSION`           | the release version                                     |
| `README.md`         | this documentation                                      |

Both entry points resolve their own symlinks and load `../lib`, so the tree works wherever it is unpacked, and the archive carries no test tree and no build task.
`bin/` sits at the archive root, which is where a mise GitHub tool looks for the programs it puts on `PATH`: such a tool downloads `probetron-<version>.tar.gz`, unpacks it into one installation directory, and needs neither a strip nor a rename.
The archive carries no Babashka binary, so a client installs Babashka once through mise or its own package manager and one archive then serves macOS and Linux alike.

## Current state

The operation model, both command lines, and both entry points parse, validate, and refuse malformed input.
The rig owns the target lock, the active record, and the process groups of one operation, and `status` reports what owns the target.
`info`, `flash`, `erase`, `reset`, `connect`, and `debug` drive the hardware.
The client refreshes the rig key, reaches `info`, `status`, `flash`, `erase`, and `reset` over SSH, and gives back what the rig said.
It also opens the `connect` and `debug` sessions, publishes the rig byte service or the rig DAP server on a client loopback port, and presents the byte service as a pseudo-terminal when it can.
`bb package` builds the release archive that a future mise tool installs.
