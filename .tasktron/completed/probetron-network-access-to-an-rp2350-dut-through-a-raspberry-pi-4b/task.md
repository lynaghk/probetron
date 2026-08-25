# Probetron: network access to an RP2350 DUT through a Raspberry Pi 4B

## Intent

Create a self-contained `probetron/` project that turns one Raspberry Pi 4B into a lightweight network appliance for one physically connected RP2350 device under test.

Probetron must let macOS and Linux clients flash ELF firmware, reset or erase the target, use source-level DAP debugging, read RTT logs, and bridge either native USB CDC or the Pi UART without opening an interactive login shell.

The infrastructure must remain project-agnostic and easy to extract into its own Git repository later.

## Shared context

The first target is the piezo-driver setup in this repository, where a Pico 2 W is the RP2350 target.

Existing firmware, host crates, scripts, manifests, root tool configuration, and root Git configuration must not change.

All implementation files outside `.tasktron/` must live under `probetron/`.

The project uses Babashka for client and node logic, has no third-party Clojure runtime dependencies, and separates a functional operation-building core from the imperative code that owns files, subprocesses, signals, sockets, and locks.

The node is one immutable, project-agnostic appliance with one physical DUT slot.

The node builds nothing, stores no durable artifact or project state, and requires no internet access during first boot or normal operation.

The lab LAN is the authentication trust boundary.

Man-in-the-middle protection and protection from other lab-LAN users are intentionally not required.

Malformed public arguments and uploaded ELF files are in scope, but hostile authenticated users, local-port allocation races, resource exhaustion, `SIGKILL`, kernel failure, and power loss during an individual operation are not hard-cleanup guarantees.

The Pi drives SWD directly through a pinned probe-rs v0.32 release or a later version that hardware qualification explicitly records.

The SPI wiring is fixed:

| Pi 4B signal | GPIO | Header pin | DUT signal |
| --- | --- | --- | --- |
| SPI0 SCLK | GPIO11 | 23 | SWCLK |
| SPI0 MISO | GPIO9 | 21 | SWDIO directly |
| SPI0 MOSI | GPIO10 | 19 | SWDIO through 1 kΩ |
| reset | GPIO26 | 37 | RUN |
| GND | — | 20, 25, or 39 | GND |

The optional UART wiring is fixed:

| Pi 4B signal | GPIO | Header pin | DUT signal |
| --- | --- | --- | --- |
| UART0 TXD | GPIO14 | 8 | DUT RX |
| UART0 RXD | GPIO15 | 10 | DUT TX |
| GND | — | 6 | GND |

The node uses wired Ethernet and an operator-supplied static address or DHCP reservation.

One nonblocking `flock` transaction owns all target hardware at a time.

The ordinary `bench` shell account cannot open SPI, GPIO, UART, or DUT USB devices and can reach privileged hardware only through the immutable node entry point.

Flash, erase, reset, and hardware information operations hold short locks.

Connect, RTT, and DAP operations hold the lock until their outer SSH command ends.

A conflicting operation fails immediately with a distinct busy status and volatile active-command metadata.

Handled completion, SSH disconnection, `SIGINT`, `SIGTERM`, and `SIGHUP` remove owned listeners and process groups before lock release.

Cleanup preserves DUT state by default.

Flash resets after a successful verified download, and `--reset-on-exit` requests one best-effort reset after long-session cleanup.

Services carrying DUT bytes or DAP bind only to Pi loopback and exist only during a locked operation.

USB matching uses one fixed physical Pi port rather than DUT VID, PID, or serial identity.

Host programs, editor source, and durable artifacts stay on the client.

## Acceptance criteria

- Documented Babashka or mise tasks run all automated tests and Clojure lint checks.
- The public `probetron` command supports `info`, `status`, `flash`, `erase`, `reset`, `connect`, and `debug` with arguments or ordinary environment variables for target-specific values.
- A client refreshes a changed HTTP-served SSH private key before every operation and fails clearly when refresh fails.
- Every SSH invocation avoids persistent host keys and suppresses new-host and changed-host failures, so replacing a Pi at the same address needs no cache cleanup.
- The image-generated key permits an unrestricted non-root `bench` shell and SSH forwarding, while `bench` cannot bypass target locking.
- A documented `mise run image` on Debian 13 arm64 fetches pinned inputs and emits a compressed complete Pi 4 image plus checksum under `probetron/build/`.
- The built Pi needs no APT, HTTP, DNS, or other internet request during boot or supported operations.
- The built Pi boots with read-only root and boot filesystems, bounded volatile writable state, and a volatile journal.
- Pinned probe-rs identifies an RP2350 over the direct Linux SPI SWD wiring.
- Flash accepts only a bounded ELF, verifies it through probe-rs, and pulses GPIO26 so firmware starts without BOOTSEL or a physical reset press.
- Reset pulses GPIO26, and erase delegates once to probe-rs without an automatic destructive retry.
- Connect exposes either native USB CDC or Pi UART as a client-loopback TCP endpoint and permits sequential byte-client reconnections.
- PTY mode works through client-side `socat` on macOS and Linux, while a missing `socat` leaves TCP mode usable and prints an actionable warning.
- An optional RTT ELF produces decoded human logs while the independent CDC or UART bridge remains usable.
- Debug forwards a multi-session probe-rs DAP server that permits IDE disconnect and reconnect without releasing the outer lock or resetting the DUT.
- Probe-rs diagnostics and nonzero statuses reach the client, and missing resources name the expected resource and corrective action.
- Human-readable and EDN information output identifies Probetron, Babashka, probe-rs, OS, and node identity versions.
- A simultaneous second operation gets an immediate busy response.
- A handled long-session termination removes transient processes and listeners and releases the lock.
- A default session exit preserves target state, while `--reset-on-exit` performs a best-effort reset.
- Power loss during a session does not require filesystem repair, and supported operations work after reboot.
- A deterministic release task emits a tagged-release archive that can later be attached to a GitHub release and installed on `PATH` by mise.

## Out of scope

- Changes to liquid-handler firmware, host crates, scripts, manifests, or root tool configuration.
- Project-specific build, test, disarm, artifact, profile, inventory, or run-manifest orchestration.
- Node-side execution of uploaded host test programs.
- Control of the piezo-driver 35 V rail or ESP32 integration.
- Transparent USB virtualization, USB/IP, or VirtualHere.
- Multiple DUT slots, simultaneous targets, a separate debug probe, or a fallback probe.
- Connect-under-reset.
- GDB-specific integration when DAP provides source-level debugging.
- Publishing or extracting the future standalone repository.
- Image builds on macOS, x86-64 Linux, or any host other than Debian 13 arm64.
- Provisioning a stock Pi image or installing packages from the Pi at runtime.
- CI scheduling against physical hardware.

## Subtasks

### Establish the Probetron project and operation model

check: cd probetron && mise exec -- bb test && mise exec -- bb lint

Create the extractable project boundary with `probetron/.gitignore`, `probetron/VERSION`, `probetron/bb.edn`, `probetron/mise.toml`, `probetron/bin/probetron`, `probetron/bin/probetron-node`, `probetron/src/probetron/`, `probetron/test/probetron/`, and an initial `probetron/README.md`.

Pin the exact Babashka and clj-kondo versions in the local `mise.toml`.

Expose `bb test` through a dependency-free `clojure.test` runner, expose `bb lint` through clj-kondo, and expose the same pair as `mise run check` without touching the root `mise.toml`.

Make both executable entry points find `../src` in a development checkout and a sibling installed `../lib` tree after packaging.

Use `babashka.cli` to parse one public operation map and keep validation and command construction in focused pure namespaces such as `probetron.cli`, `probetron.operation`, and `probetron.node.config`.

Represent operations with explicit maps such as `{:operation :flash :host host :chip chip :speed-khz speed :elf path}` rather than passing raw CLI maps into the imperative shells.

Define and test strict validators for host names or IP literals, chip names, positive bounded SWD and UART speeds, `usb` or `uart` channels, TCP ports, bounded wait durations, existing local ELF paths, and `text` or `edn` output format.

Reject unknown options and invalid option combinations before any filesystem, network, or hardware action.

Support `PROBETRON_HOST`, `PROBETRON_CHIP`, `PROBETRON_SPEED_KHZ`, `PROBETRON_UART_BAUD`, and `PROBETRON_USB_WAIT_SECONDS`, with explicit arguments taking precedence.

Use 1000 kHz as the default SWD speed, 115200 as the default UART baud, and 10 seconds as the default USB wait with a 60-second maximum.

Establish these public forms and help text:

```text
probetron info    --host <host> [--format <text|edn>]
probetron status  --host <host> [--format <text|edn>]
probetron flash   --host <host> --chip <chip> [--speed-khz <speed>] <elf>
probetron erase   --host <host> --chip <chip> [--speed-khz <speed>]
probetron reset   --host <host>
probetron connect --host <host> --channel <usb|uart> [--baud <baud>] [--usb-wait-seconds <seconds>] [--local-port <port>] [--rtt <elf> --chip <chip> [--speed-khz <speed>]] [--pty] [--reset-on-exit]
probetron debug   --host <host> [--local-port <port>] [--reset-on-exit]
```

Use exit status 64 for public or node usage errors and exit status 75 for a busy node.

Define and test this separate node-only SSH protocol:

```text
probetron-node info    [--format <text|edn>]
probetron-node status  [--format <text|edn>]
probetron-node flash   --chip <chip> [--speed-khz <speed>]
probetron-node erase   --chip <chip> [--speed-khz <speed>]
probetron-node reset
probetron-node connect --channel <usb|uart> [--baud <baud>] [--usb-wait-seconds <seconds>] [--rtt --chip <chip> [--speed-khz <speed>]] [--reset-on-exit]
probetron-node debug   [--reset-on-exit]
```

Make node `flash` and node `connect --rtt` consume the one bounded ELF from standard input, so no client path appears in the remote command.

Reject public-only options such as `--host`, `--local-port`, and `--pty` at the node boundary.

Write red-then-green public and node parser and operation-model tests for each command, environment fallback, precedence rule, invalid value, invalid RTT option combination, unknown node option, and the exact exit statuses.

### Enforce node ownership and process lifecycle

dependencies: establish-the-probetron-project-and-operation-model
check: cd probetron && mise exec -- bb test && mise exec -- bb lint

Implement the privileged shell in `probetron/src/probetron/node/` and keep the production `probetron-node` interface suitable for installation as `/usr/local/sbin/probetron-node`.

Use absolute paths for `/usr/bin/flock`, `/usr/bin/setsid`, `/bin/kill`, and every later appliance executable.

Put the one lock at `/run/probetron/target.lock`, acquire it nonblockingly around every hardware operation, and return the reserved busy exit status immediately on conflict.

Do not rely on a cooperative claim, daemon, token, TTL, or maximum session duration.

Write `/run/probetron/active.edn` atomically only after lock acquisition with the validated command name, root process PID, and UTC start time.

Make `status` distinguish a free lock from a held lock and report active metadata in text or EDN without opening hardware.

Remove stale metadata when the lock is free, and remove current metadata in normal cleanup.

Launch every owned long-running helper in a separate process group, register shutdown cleanup before starting it, and terminate then forcibly reap only that owned group on normal completion, SSH EOF, `SIGINT`, `SIGTERM`, or `SIGHUP`.

Run optional reset-on-exit only after child cleanup and before lock release.

Keep the default cleanup path reset-free.

Design the shell functions around injected filesystem, clock, and process functions for tests, while production wiring always selects the fixed absolute paths.

Write red-then-green integration tests through the public node runner with temporary lock and metadata paths and a fixture child process.

Prove that a second process gets the busy code without waiting, metadata names the owner, handled termination reaps the fixture child, stale metadata does not report a false owner, and reset-on-exit is ordered after child cleanup.

### Implement node information flash erase and reset

dependencies: enforce-node-ownership-and-process-lifecycle
check: cd probetron && mise exec -- bb test && mise exec -- bb lint

Add the short node operations to focused namespaces under `probetron/src/probetron/node/`.

Inline the fixed hardware constants in code: `/dev/spidev0.0`, selector `0:0:/dev/spidev0.0`, `/dev/gpiochip0`, GPIO26, `/dev/ttyAMA0`, `/dev/probetron-dut`, `/usr/local/bin/probe-rs`, `/usr/bin/gpioset`, `/usr/bin/socat`, byte port 5555, DAP port 50000, the 64 MiB ELF limit, and `/run/probetron/` paths.

Make `info` hold the transaction lock while it reports node identity, Probetron, Babashka, probe-rs, and OS versions and runs probe-rs target information with the explicit Linux SPI selector and SWD protocol.

Make text output concise and make EDN output contain stable keyword keys suitable for scripts.

Read flash input from standard input into a unique file under a bounded volatile upload directory.

Stop after the configured maximum plus one byte, reject an oversized input, validate the complete ELF32 little-endian header and RP2350-compatible ARM or RISC-V machine value, and remove the file on every exit path.

Require the ELF32 header, program-header entry, and section-header entry sizes defined by the ELF specification.

Reject truncated or out-of-range program and section tables, arithmetic overflow, and every load segment whose file range lies outside the uploaded file.

Perform all file validation before probe-rs opens the target.

Construct probe-rs argv vectors with the explicit probe selector, caller-supplied chip, SWD protocol, and caller-supplied speed.

Make flash call probe-rs download with verification enabled, preserve its output and status, and pulse RUN only after successful verification.

Make erase call probe-rs erase exactly once and preserve its status without retrying.

Make reset use the Debian Trixie `gpioset` interface to hold GPIO26 low for a documented pulse and release it, not a probe-rs reset command.

Return diagnostics that name a missing SPI device, GPIO chip, probe-rs executable, malformed ELF, or upload limit and state the corrective action.

Write red-then-green tests against fake absolute-command adapters for exact argv, truncated tables, out-of-range load segments, ELF rejection before process launch, maximum-size handling, temporary-file cleanup, flash-reset ordering, no reset after failed flash, single erase invocation, information formats, and subprocess status propagation.

### Implement client authentication and short operations

dependencies: implement-node-information-flash-erase-and-reset
check: cd probetron && mise exec -- bb test && mise exec -- bb lint

Implement the imperative client in `probetron/src/probetron/client/` for `info`, `status`, `flash`, `erase`, and `reset`.

Before every operation, fetch the image-generated private key from `http://<host>/probetron_key`.

Store one host-specific copy under `${XDG_CACHE_HOME:-$HOME/.cache}/probetron/keys/`, compare fetched bytes with the current copy, and atomically rename a changed file with mode `0600`.

Treat HTTP failure, empty content, invalid private-key framing, unsafe cache permissions, and atomic replacement failure as explicit operation failures even when an older cached key exists.

Construct every SSH argv with the cached key, `bench@<host>`, batch mode, no TTY for short commands, `StrictHostKeyChecking=no`, `UserKnownHostsFile=/dev/null`, `GlobalKnownHostsFile=/dev/null`, and an SSH log level that suppresses new-host and changed-host warnings without hiding remote stderr.

Invoke only `sudo -n /usr/local/sbin/probetron-node` remotely.

Serialize the validated node argv as one remote command with a focused pure POSIX shell-token quoting function instead of relying on validation alone.

Test spaces, single quotes, command substitutions, separators, redirections, and newlines so no accepted or rejected public value can add a remote shell token.

Stream flash ELF bytes to remote standard input without placing a client path in the remote command.

Preserve remote stdout, stderr, and exit status, including the node's busy code and probe-rs diagnostics.

Make client `info` combine local Probetron and Babashka versions with the node result in text or EDN, and report the host-specific cache path used for the refreshed key.

Use a local HTTP fixture and fake SSH executable in red-then-green tests.

Cover initial key creation, unchanged content, changed content, `0600` mode, fetch failure with an existing cache, host-specific cache separation, all required SSH options, exact remote argv, streamed ELF bytes, output/status propagation, and no persistent known-host file.

### Implement node byte and RTT sessions

dependencies: implement-node-information-flash-erase-and-reset
check: cd probetron && mise exec -- bb test && mise exec -- bb lint

Implement the locked `connect` node operation in `probetron/src/probetron/node/session.clj` on the lifecycle shell.

Bind the fixed byte service port only to `127.0.0.1`.

For `uart`, configure `/dev/ttyAMA0` as a raw byte stream at the validated requested baud, with 115200 as the default.

For `usb`, wait up to the validated bounded initial interval for `/dev/probetron-dut`, then let each accepted connection reopen that stable path so firmware re-enumeration is resolved again.

Run the node-side `socat` listener with reuse enabled, at most one active byte client, and sequential reconnect support for as long as the outer node operation lives.

Reject a missing or unreadable channel device with a diagnostic that names the expected path and the SPI/UART/USB wiring or image rule to check.

When `--rtt` is present, receive and validate its bounded ELF through the same volatile upload path before opening hardware, require chip and speed, and start probe-rs RTT decoding beside the byte listener with the explicit SPI selector and SWD protocol.

Keep structured bytes only on the TCP service and RTT text only on node stdout and stderr.

Remove the RTT ELF and both child groups when the outer process exits or receives a handled signal.

Preserve DUT state unless reset-on-exit was requested.

Write red-then-green tests with fake devices and process adapters for loopback-only binding, one-client/sequential listener argv, USB initial wait and timeout, path reopening, UART baud, concurrent bridge and RTT children, RTT argv, channel independence, signal cleanup, upload cleanup, and reset policy.

### Expose byte and RTT sessions on clients

dependencies: implement-client-authentication-and-short-operations implement-node-byte-and-rtt-sessions
check: cd probetron && mise exec -- bb test && mise exec -- bb lint

Implement client `connect` on the shared authentication and SSH shell.

Use an explicit validated `--local-port` or reserve a free ephemeral loopback port on the happy path.

Create one SSH local forward from `127.0.0.1:<local-port>` to the node's fixed loopback byte port, run the one validated remote node command, and print `tcp://127.0.0.1:<local-port>` before waiting for a host program.

Forward an optional RTT ELF on SSH standard input and preserve RTT and probe-rs diagnostics on the outer command output.

When `--pty` is present and client-side `socat` exists, create a private volatile local link path, bridge it to the TCP endpoint, and print the pseudo-terminal path.

When client-side `socat` is absent, print an actionable warning and continue the same outer session in TCP-only mode so the printed TCP endpoint remains usable.

Treat PTY helper exit as loss of only the PTY presentation unless the outer SSH command also exits.

On normal completion or handled client signal, stop the local PTY helper and outer SSH process, which in turn triggers remote cleanup and lock release.

Pass reset-on-exit only to the node and never add an implicit reset in client cleanup.

Write red-then-green public client tests with fake SSH and `socat` processes for exact loopback forwarding, endpoint output, RTT upload, PTY path output, missing-socat TCP fallback, client signal cleanup, remote status propagation, and default versus requested reset policy.

### Implement persistent remote DAP sessions

dependencies: expose-byte-and-rtt-sessions-on-clients
check: cd probetron && mise exec -- bb test && mise exec -- bb lint

Add node and client `debug` on the existing lifecycle and tunnel abstractions.

Make the node launch the pinned probe-rs DAP server on its fixed `127.0.0.1` port with multi-session mode enabled.

Use `/dev/spidev_swd*` discovery and do not expose arbitrary SPI buses or reinterpret DAP requests.

Keep chip, speed, ELF, SVD, source, launch, and attach configuration in the DAP client request so the probe-rs editor integration can upload client-local files and resolve local source.

Keep the node lock for the outer debug command across individual DAP disconnects and reconnects.

Make the client select or validate a local loopback port, establish the SSH local forward, print the DAP endpoint, and keep the outer SSH process alive until explicit termination.

Preserve target state on DAP disconnect and default outer-command cleanup.

Apply reset-on-exit only after the DAP process group has stopped.

Write red-then-green tests for exact multi-session and loopback DAP argv, SSH forwarding, endpoint output, lock retention when a fake DAP client disconnects, handled termination, child cleanup, and reset ordering.

### Build the release archive

dependencies: implement-persistent-remote-dap-sessions
check: cd probetron && mise exec -- bb test && mise exec -- bb lint && mise exec -- bb package

Implement package behavior in `probetron/src/probetron/package.clj`, keep `probetron/scripts/package.clj` as a thin entry point, and expose it as `bb package` and `mise run package`.

Use `probetron/VERSION` as the archive version and allow an explicit release tag only when it matches that version.

Create a deterministic `probetron/build/probetron-<version>.tar.gz` plus SHA-256 file containing `bin/probetron`, `bin/probetron-node`, the relocatable `lib/probetron/` source tree, `VERSION`, and the user documentation needed beside a release.

Do not bundle a platform Babashka binary or publish a release in this task.

Preserve executable modes and reject any archive path outside `probetron/build/`.

Add an integration test through the public `probetron.package` namespace that packages into a temporary directory, checks stable archive contents and checksums, extracts it, and runs both entry points' `--help` through the pinned Babashka runtime.

Do not add a test that targets the thin `probetron/scripts/package.clj` launcher itself.

Document the archive layout expected by a future mise GitHub tool without inventing the future owner or release URL.

### Build and configure the pinned immutable node image

dependencies: build-the-release-archive
check: cd probetron && mise exec -- bb test && mise exec -- bb lint && mise exec -- bb image --validate-only

Pin the exact rpi-image-gen revision and establish its configuration before authoring any layer against that revision.

Use `probetron/image/config/probetron.yaml` for the image configuration and these exact named layers and matching `.rootfs-overlay/` trees under `probetron/image/layer/`: `probetron-runtime.yaml`, `probetron-access.yaml`, `probetron-hardware.yaml`, `probetron-immutable.yaml`, and `probetron-offline.yaml`.

Make `bb image --validate-only` perform the platform gate, fetch or reuse only the pinned rpi-image-gen checkout, run rpi-image-gen's configuration and layer validation, print `Valid Probetron image configuration.`, and exit before keys, package staging, root privileges, or image construction.

Use the named layers to install the packaged code, Babashka as `/usr/local/bin/bb`, probe-rs as `/usr/local/bin/probe-rs`, OpenSSH, sudo, socat, gpiod, udev, and all other runtime libraries or tools into the image.

Create the locked, non-root `bench` account with no SPI, GPIO, dialout, or Probetron hardware-group membership.

Install the root-owned entry point at `/usr/local/sbin/probetron-node` and add one passwordless sudo rule for that exact immutable command, with no general sudo shell or package-management permission.

Generate one bench SSH authentication keypair per image build, authorize its public key, and expose only its private key as `/probetron_key` through a minimal unauthenticated HTTP service.

Permit the key an unrestricted non-root shell and SSH forwarding.

Disable password and keyboard-interactive authentication, root login, DNS lookups, and SSH host-key regeneration on the read-only target.

Configure SSH keepalives so dead connections are reaped and node cleanup can release locks.

Add udev rules that reserve `/dev/spidev0.0`, `/dev/gpiochip0`, `/dev/ttyAMA0`, and the selected USB CDC device from `bench`.

Create `/dev/spidev_swd0` from the fixed SPI device for safe probe-rs discovery.

Before writing the hardware layer, connect a CDC DUT to the one designated Pi 4B receptacle and record its board-relative receptacle label and exact `udevadm info --attribute-walk --name=/dev/ttyACM0` `KERNELS` ancestry as non-placeholder `:usb-port-label` and `:usb-kernels` values in `probetron/image/pins.edn`.

Do not complete the layer with an inferred or placeholder topology.

Create `/dev/probetron-dut` from those pinned values and the CDC tty class, without matching VID, PID, or serial descriptors.

Enable SPI0, enable UART0 on GPIO14 and GPIO15, disable its serial console, disable Bluetooth, and leave the fixed GPIO26 reset line available.

Mount root and boot read-only.

Put `/run`, `/tmp`, `/var/tmp`, the upload directory, network lease state, and any required service state on explicitly sized tmpfs or existing bounded volatile paths.

Configure the system journal as volatile.

Disable package update timers, time synchronization, Wi-Fi, Bluetooth, mDNS, and unused services that could make internet, DNS, or unnecessary network requests, without reducing wired DHCP, SSH, SPI, GPIO, UART, or USB reliability.

Do not add tests for these configuration files.

Keep each policy in a named layer file that rpi-image-gen can validate and that the README can map to the runtime invariant.

Add `probetron/image/pins.edn` and `probetron/scripts/image.clj` as the Babashka image build driver.

Pin an exact rpi-image-gen Git revision, a Debian Trixie arm64 snapshot where rpi-image-gen supports it, an exact Babashka aarch64 archive and SHA-256, and an exact probe-rs aarch64 archive and SHA-256.

Use probe-rs v0.32 initially unless the repository records completed hardware qualification of a later pin.

Select Raspberry Pi 4, minimal Debian 13 Trixie arm64, wired DHCP, OpenSSH, and the Probetron appliance layer.

Fail before downloads on any host other than Debian 13 arm64, and confirm the selected base satisfies the pinned probe-rs binary's GLIBC 2.39 requirement.

Make the driver fetch rpi-image-gen into ignored `probetron/.cache/` as a normal clone rather than a root submodule.

Fetch every external artifact on the build host, verify its checksum before use, stage the release archive and generated SSH keys, invoke the pinned image generator, and never fetch from the target image at first boot.

Expose the driver as exactly `bb image` and `mise run image`.

Write the final compressed image and checksum as `probetron/build/probetron-rpi4.img.xz` and `probetron/build/probetron-rpi4.img.xz.sha256`.

Make interrupted builds replace neither a valid final image nor its checksum.

Keep cache, work, key, and output artifacts ignored without modifying a root ignore file.

Do not add a mock test for image configuration or scripts.

Run rpi-image-gen's own configuration validation as an early build step, and make ordinary `bb test` avoid the privileged and multi-gigabyte image build.

The subtask check must print `Valid Probetron image configuration.` and exit 0 without constructing an image.

### Document operation and hardware qualification

dependencies: build-and-configure-the-pinned-immutable-node-image
check: cd probetron && mise exec -- bb test && mise exec -- bb lint && mise exec -- bb package

Complete `probetron/README.md` as the extraction-ready operator and developer guide.

Document local setup, `mise run check`, release packaging, Debian 13 arm64 image prerequisites, `mise run image`, checksum verification, SD-card writing, wired addressing, exact SWD and UART tables, the 1 kΩ orientation, and the one designated physical USB port.

Document every client command and environment fallback with an RP2350 example, the ELF size limit, default speed and UART baud, TCP and PTY use on macOS and Linux, RTT output separation, busy status, cleanup behavior, and reset policy.

Document the raw SSH path by downloading the same HTTP key, setting mode `0600`, disabling all known-host files, and invoking `sudo -n /usr/local/sbin/probetron-node` without an interactive login.

Document probe-rs editor remote-server setup for both launch and attach, local ELF and source ownership, the forwarded endpoint, multi-session reconnect behavior, and reset-on-exit.

Document the immutable-node threat model, open lab-LAN authentication, permissions, volatile paths, read-only mounts, offline runtime, and recovery after power loss.

Add a troubleshooting table for the expected SPI selector, GPIO26, `/dev/ttyAMA0`, `/dev/probetron-dut`, client `socat`, HTTP key endpoint, SSH forwarding, busy metadata, and probe-rs errors.

Give hardware qualification concrete commands to identify RP2350 at 1 MHz, step through candidate SWD speeds and record the stable pin, verify GPIO reset, reverify the recorded physical port's udev topology, verify the exact probe-rs and editor versions for DAP reconnect, and re-run flash, bridge, RTT, DAP, exclusion, cleanup, read-only, offline-boot, and power-cut checks.

State clearly that hardware qualification must update the pinned values or documentation when its measured SPI speed, USB topology, or editor compatibility differs, rather than hiding the discrepancy behind runtime inventory.

Keep future GitHub ownership and release publication explicitly unresolved.
