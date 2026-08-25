# Probetron

Probetron turns one Raspberry Pi 4B into a network appliance for one physically connected RP2350 device under test.
Lab clients on macOS and Linux flash firmware, erase or reset the target, debug it over DAP, read RTT logs, and bridge the DUT serial channel without an interactive login shell on the Pi.

The project stays project-agnostic so that it can move into its own Git repository unchanged.
Everything it owns lives under `probetron/`.

## Layout

| Path                                     | Contents                                                           |
| ---------------------------------------- | ------------------------------------------------------------------ |
| `bin/probetron`                          | public client entry point                                          |
| `bin/probetron-rig`                      | rig entry point that the client reaches over SSH                   |
| `src/probetron/operation.clj`            | pure operation model, validators, and rig command construction     |
| `src/probetron/version.clj`              | the release version that every role reports                        |
| `src/probetron/client/cli.clj`           | pure parser of the public command line                             |
| `src/probetron/client/command.clj`       | pure remote command, SSH argv, and key cache paths                 |
| `src/probetron/client/report.clj`        | pure client information record and its two output forms            |
| `src/probetron/rig/config.clj`           | pure parser of the rig-only SSH protocol                           |
| `src/probetron/rig/lifecycle.clj`        | pure ownership model and appliance command lines                   |
| `src/probetron/rig/hardware.clj`         | pure description of the fixed target slot and its command lines    |
| `src/probetron/rig/elf.clj`              | pure validator of one uploaded firmware image                      |
| `src/probetron/rig/information.clj`      | pure information record and its two output forms                   |
| `src/probetron/client/main.clj`          | imperative shell of the client                                     |
| `src/probetron/client/key.clj`           | imperative shell that refreshes the cached rig key                 |
| `src/probetron/client/session.clj`       | imperative shell of the short client operations                    |
| `src/probetron/client/tunnel.clj`        | imperative shell of the long client sessions                       |
| `src/probetron/rig/main.clj`             | imperative shell of the rig                                        |
| `src/probetron/rig/runner.clj`           | imperative shell that owns the target lock and the process groups  |
| `src/probetron/rig/target.clj`           | imperative shell of `info`, `flash`, `erase`, and `reset`          |
| `src/probetron/rig/session.clj`          | imperative shell of the locked `connect` and `debug` sessions      |
| `src/probetron/provisioning/package.clj` | pure release plan and the shell that writes the release archive    |
| `src/probetron/provisioning/archive.clj` | pure deterministic tar, gzip, and SHA-256 encoder                  |
| `src/probetron/provisioning/image.clj`   | the rig image build driver that `bb image` runs                    |
| `image/pins.edn`                         | every pinned revision, archive, and digest of the rig image        |
| `image/config/probetron.yaml`            | the one rpi-image-gen configuration of the appliance               |
| `image/layer/`                           | the five named appliance layers and their `.rootfs-overlay/` trees |
| `test/probetron/`                        | `clojure.test` namespaces that the runner discovers                |
| `VERSION`                                | the release version, which `probetron.version` repeats             |

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
It never builds an image, because that build wants a Debian 13 arm64 host, elevated privilege, and several gigabytes.
`bb image --validate-only` is the cheap check that any checkout can run.

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

## Rig image

`bb image` and `mise run image` build the whole appliance on a Debian 13 arm64 host and write one compressed image beside its checksum.

```sh
bb image
```

```text
probetron: 0.1.0
image: build/probetron-rpi4.img.xz
checksum: build/probetron-rpi4.img.xz.sha256
sha256: <64 hexadecimal digits>
```

Both outputs arrive through a temporary neighbour and one rename each, so an interrupted build replaces neither a valid image nor a valid checksum.

`--validate-only` stops as soon as rpi-image-gen has accepted the configuration and resolved every layer, which is the whole check that a checkout without build dependencies, elevated privilege, or a spare hour can run.

```sh
bb image --validate-only
```

```text
Valid Probetron image configuration.
```

Any `key=value` argument reaches rpi-image-gen unchanged, so renaming one rig needs no edit to a tracked file.

```sh
bb image IGconf_device_hostname=probetron-02
```

### Order of operations

The build spends nothing before it knows it can finish.

1. The platform gate reads `/etc/os-release` and the machine type, because rpi-image-gen supports native Debian arm64 alone and the pinned probe-rs is an aarch64 GNU binary.
2. The GLIBC check confirms that the pinned base carries at least the GLIBC that pinned probe-rs needs.
3. The pinned rpi-image-gen checkout appears under ignored `.cache/`, and a checkout already at the pinned revision opens no connection at all.
4. rpi-image-gen lints all five layers, parses the configuration, and resolves the whole layer graph, then the build prints `Valid Probetron image configuration.`
5. `--validate-only` exits here, before any key, any staged file, any privilege check, and any image construction.
6. The DUT topology gate refuses a build whose one receptacle nobody has measured.
7. The privilege gate refuses a host that can give the build neither root nor the podman user namespace that mmdebstrap needs.
8. Staging runs `bb package`, fetches each pinned archive, checks it against its pinned digest before use, unpacks the one member it wants, and generates one SSH keypair.
9. rpi-image-gen builds the image, and `xz` and one SHA-256 publish it.

### Pinned inputs

`image/pins.edn` is the only file in the project that names a revision, an archive, or a digest.

| Pin           | Value                                                                                         |
| ------------- | --------------------------------------------------------------------------------------------- |
| rpi-image-gen | `v2.8.0`, revision `262d4df5a9f9d4133370465399a7958a7c22cdc7`                                 |
| base          | Debian 13 Trixie arm64, `debian-trixie-arm64-minbase-snapshot` at snapshot `20260801T000000Z` |
| Babashka      | 1.13.219, `linux-aarch64-static`, digest pinned, installed as `/usr/local/bin/bb`             |
| probe-rs      | 0.32.0, `aarch64-unknown-linux-gnu`, digest pinned, installed as `/usr/local/bin/probe-rs`    |
| device        | Raspberry Pi 4, layer `rpi4`, layout `image-rpios`, wired DHCP, OpenSSH                       |

The checkout is an ordinary clone under `.cache/` and never a submodule of the repository, so the surrounding project keeps its own history and its own ignore file.
Every other artefact arrives on the build host, and the Pi therefore asks for nothing at first boot or later.
The snapshot mirror pins the package set through `SOURCE_DATE_EPOCH`, so the same revision and the same pins rebuild the same appliance.

probe-rs 0.32 is the initial pin.
A later pin belongs in `pins.edn` only once hardware qualification records that probe-rs identifies an RP2350 over this SWD wiring.

### Layers

`image/config/probetron.yaml` selects the device, the layout, and five named layers, and each layer owns exactly one runtime invariant.
Every layer keeps its static files in the matching `.rootfs-overlay/` tree, which rpi-image-gen copies into the root filesystem before that layer's own hooks run.

| Layer                 | Runtime invariant                                                                                                                                                      |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `probetron-runtime`   | The appliance exists: the release archive under `/usr/local`, `bb`, `probe-rs`, `socat`, `gpiod`, `procps`, `sudo`, and `udev`, and the volatile upload directory.     |
| `probetron-access`    | The lab LAN is the trust boundary: one generated key, an unrestricted `probetron` shell with forwarding, sudo for the one immutable command, and the HTTP key service. |
| `probetron-hardware`  | The one DUT slot exists and belongs to nobody else: SPI0, UART0 on GPIO14 and GPIO15 with no console, GPIO26 free, and udev rules that reserve every target device.    |
| `probetron-immutable` | The rig stores nothing: read-only root and boot, sized tmpfs for every writable path, and a journal that dies with its boot.                                           |
| `probetron-offline`   | The rig asks the internet for nothing: no package timer, no time synchronisation, no radio, and no multicast discovery.                                                |

### What the appliance is

`probetron-runtime` unpacks the release archive into `/usr/local`, so `/usr/local/bin/probetron-rig` loads `/usr/local/lib/probetron` and `/usr/local/sbin/probetron-rig` is the symlink that sudo names.
It installs the verified Babashka and probe-rs binaries and every other program that the rig runs by absolute path.

`bench` is the locked, non-root account of the image, and it belongs to no SPI, GPIO, dialout, or `probetron` group.
`probetron-access` takes back the general sudo permission that the base layer grants and removes `bench` from the `sudo` group, so the one rule that survives gives `bench` `/usr/local/sbin/probetron-rig` and nothing else: no shell, no editor, and no package management.
The public half of the keypair that the build generated authorises `bench`, which keeps an ordinary shell and TCP forwarding, because the target lock and not the login shell owns the hardware.

```text
http://<host>/probetron_key
```

One `socat` service answers that one resource and refuses every other request, and it runs as a `probetron-key` system account, so no unauthenticated request is ever answered by root.
sshd accepts public keys alone, refuses root, resolves no name, and reaps a client that stops answering within a minute, which is what lets rig cleanup release the target lock instead of holding it until somebody reboots the Pi.
The host identity is generated at build time and host-key regeneration is masked, because a read-only `/etc` cannot make one at first boot and every client already refuses to cache host keys.

`probetron-hardware` enables SPI0, puts the PL011 on GPIO14 and GPIO15, removes the serial console from the kernel command line, and masks the serial getty, so the DUT owns that line alone.
Disabling Bluetooth is what moves the PL011 onto those two pins in the first place.
Its udev rules give `/dev/spidev0.0`, `/dev/gpiochip0`, and `/dev/ttyAMA0` to root and the `probetron` group, and name `/dev/spidev_swd0` for the one SPI bus that carries SWD, so a probe request can never reach another SPI device of the Pi.

`probetron-immutable` adds `ro` to the kernel command line and masks `systemd-remount-fs.service`, because the image layout writes `/etc/fstab` after every layer has run and that file asks for `rw`.
A drop-in mounts `/boot/firmware` read-only for the same reason.

| Volatile path    | Bound                                                                                      |
| ---------------- | ------------------------------------------------------------------------------------------ |
| `/tmp`           | 128 MiB tmpfs                                                                              |
| `/var/tmp`       | 32 MiB tmpfs                                                                               |
| `/var/log`       | 32 MiB tmpfs                                                                               |
| `/run/probetron` | 256 MiB tmpfs, which carries the target lock, the active record, and the one 64 MiB upload |
| journal          | volatile, at most 32 MiB of `/run`                                                         |

Network lease state already lives in `/run`, the rig identity is fixed at build time, and the automatic EEPROM updater is off, so nothing writes to persistent storage and power loss needs no filesystem repair.

`probetron-offline` masks every package, time, discovery, and maintenance unit, disables the Wi-Fi and Bluetooth radios, and turns off LLMNR and multicast DNS, while wired DHCP, SSH, SPI, GPIO, UART, and USB are untouched.

### The one DUT slot

The DUT udev rule matches one physical Pi receptacle and the CDC tty class, and never a VID, a PID, or a serial descriptor, so any RP2350 board in that receptacle is the DUT and a board in another receptacle is not.

Hardware qualification records that receptacle by connecting a CDC DUT to it and reading its ancestry.

```sh
udevadm info --attribute-walk --name=/dev/ttyACM0
```

The board-relative receptacle and the exact `KERNELS` ancestry belong in `image/pins.edn` as `:usb-port-label` and `:usb-kernels`.
Until somebody measures them, both stay `nil`, `bb image` refuses to build, and the hardware layer refuses to write a rule for a topology that nobody has seen.
`bb image --validate-only` still passes, because the configuration and the layers are complete and the measurement is the only thing missing.

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
`bb image` describes the whole appliance to rpi-image-gen and validates that description, and it builds the image once hardware qualification has recorded the one DUT receptacle in `image/pins.edn`.
