# Manual test plan: Probetron network access to an RP2350 DUT

## Test variables

Start in the repository checkout and set these values before running any section.

Replace the example address and ELF path with values for the qualification bench.

```sh
export REPO_ROOT="$(git rev-parse --show-toplevel)"
export PROBETRON_HOST='192.168.1.50'
export PROBETRON_CHIP='RP235x'
export ELF='/absolute/path/to/rp2350-firmware.elf'
test -f "$ELF"
```

## Automated project checks

Run these commands on a supported development host:

```sh
cd "$REPO_ROOT/probetron"
mise install
mise run check
mise run package
sha256sum -c build/probetron-*.tar.gz.sha256
```

Expected result:

- All Clojure tests and clj-kondo checks pass.
- `build/probetron-<version>.tar.gz` contains both executables, the library tree, `VERSION`, and documentation.
- The archive checksum passes.

## Qualify the designated USB receptacle

Use a temporary Debian image on the Raspberry Pi 4B before building the final appliance image.

Connect the CDC DUT only to the board-relative receptacle named in `probetron/image/pins.edn`.

Run on the Pi:

```sh
export DUT_TTY='/dev/ttyACM0'
test -c "$DUT_TTY"
udevadm info --attribute-walk --name="$DUT_TTY" | grep 'KERNELS=='
```

Expected result:

- The physical receptacle and its complete USB `KERNELS` ancestry match the non-placeholder `:usb-port-label` and `:usb-kernels` values in `probetron/image/pins.edn`.
- Moving the DUT to each other receptacle produces a different ancestry.

Do not build the final image until the recorded pin matches the designated physical receptacle.

## Build and boot the appliance

Use a Debian 13 arm64 build host with internet access for this section.

Run:

```sh
cd "$REPO_ROOT/probetron"
mise install
mise run image
xz -t build/probetron-rpi4.img.xz
sha256sum -c build/probetron-rpi4.img.xz.sha256
```

Expected result:

- The build reports its pinned rpi-image-gen revision, Debian snapshot, Babashka version, and probe-rs version.
- The build creates only `build/probetron-rpi4.img.xz` and its checksum as final image outputs.
- Compression and checksum verification pass.

Write the image to an SD card with Raspberry Pi Imager.

Connect the Pi to wired LAN without an internet route.

Wire SPI SWD, the GPIO26 RUN line, ground, the README-designated physical USB port, and optional UART exactly as shown in `probetron/README.md`.

Boot the Pi with one RP2350 DUT attached.

On a macOS or Linux client, run:

```sh
export PROBETRON="$REPO_ROOT/probetron/bin/probetron"
test -x "$PROBETRON"
"$PROBETRON" info --format edn
```

Expected result:

- The client downloads `http://$PROBETRON_HOST/probetron_key` without a credential prompt.
- SSH prints no new-host or changed-host warning.
- EDN output identifies Probetron, client and node Babashka, probe-rs, Debian, and the node identity.
- Probe information identifies the direct Linux SPI SWD probe and the RP2350.
- The Pi performs no package installation or internet request.

Inspect appliance policy through the supported unrestricted `bench` shell:

```sh
curl --fail --silent --show-error "http://$PROBETRON_HOST/probetron_key" -o /tmp/probetron_key
chmod 600 /tmp/probetron_key
ssh -i /tmp/probetron_key \
  -o BatchMode=yes \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null \
  -o GlobalKnownHostsFile=/dev/null \
  -o LogLevel=ERROR \
  "bench@$PROBETRON_HOST" '
    root_options=$(findmnt -n -o OPTIONS /) || exit
    boot_options=$(findmnt -n -o OPTIONS /boot/firmware) || exit
    case ",$root_options," in *,ro,*) ;; *) exit 1 ;; esac
    case ",$boot_options," in *,ro,*) ;; *) exit 1 ;; esac
    for device in /dev/spidev0.0 /dev/spidev_swd0 /dev/gpiochip0 /dev/ttyAMA0 /dev/probetron-dut; do
      test ! -r "$device" || exit 1
    done
    sudo -n /usr/local/sbin/probetron-node status --format edn || exit
    ! sudo -n /bin/sh -c true || exit 1
    ! sudo -n /usr/local/bin/probe-rs --version || exit 1
  '
```

Expected result:

- `/` and `/boot/firmware` include `ro` in their mount options.
- All five direct device-read checks pass for `bench`.
- The narrow sudo command succeeds and reports a free target.
- The explicit general-shell and probe-rs sudo attempts fail.

## Flash reset and erase

Run:

```sh
"$PROBETRON" flash --host "$PROBETRON_HOST" --chip "$PROBETRON_CHIP" --speed-khz 1000 "$ELF"
"$PROBETRON" reset --host "$PROBETRON_HOST"
"$PROBETRON" erase --host "$PROBETRON_HOST" --chip "$PROBETRON_CHIP" --speed-khz 1000
"$PROBETRON" reset --host "$PROBETRON_HOST"
"$PROBETRON" flash --host "$PROBETRON_HOST" --chip "$PROBETRON_CHIP" --speed-khz 1000 "$ELF"
```

Expected result:

- Flash downloads and verifies the ELF, then pulses GPIO26 and starts the firmware without BOOTSEL or a button press.
- Explicit reset restarts the application through GPIO26.
- Erase runs once and leaves the previous application absent after reset.
- Probe-rs diagnostics remain visible on any failure.
- The final flash restores the test firmware.

Repeat flash at the candidate speeds documented in `probetron/README.md`.

Record the highest repeatably stable speed and confirm that the documented pin is conservative on the actual Pi 4B wiring.

## USB bytes PTY and exclusive ownership

Flash firmware that exposes a known request-and-response protocol over USB CDC.

Install `socat` on the client.

Start and leave this command running:

```sh
"$PROBETRON" connect --host "$PROBETRON_HOST" --channel usb --pty
```

Expected result:

- Probetron prints `tcp://127.0.0.1:<port>` and a local pseudo-terminal path.
- A local host program can exchange the known request and response through either endpoint.
- Disconnecting and reconnecting the local host program works while the outer command remains active.

In a second terminal, run:

```sh
"$PROBETRON" status --host "$PROBETRON_HOST" --format edn
started=$(date +%s)
"$PROBETRON" reset --host "$PROBETRON_HOST"
busy_status=$?
elapsed=$(($(date +%s) - started))
test "$busy_status" -eq 75
test "$elapsed" -lt 2
```

Expected result:

- Status names the active `connect` command, PID, and start time.
- Reset returns busy status 75 in less than two seconds.

Stop the connect command with Ctrl-C, then run:

```sh
"$PROBETRON" status --host "$PROBETRON_HOST" --format edn
"$PROBETRON" reset --host "$PROBETRON_HOST"
```

Expected result:

- Status reports a free target with no stale listener metadata.
- Reset succeeds.
- Ending the default connect session did not itself reset the DUT.

## RTT beside the byte channel

Use an ELF that contains RTT metadata and also exposes CDC or UART for structured data.

Run:

```sh
"$PROBETRON" connect \
  --host "$PROBETRON_HOST" \
  --channel usb \
  --rtt "$ELF" \
  --chip "$PROBETRON_CHIP" \
  --speed-khz 1000
```

Expected result:

- Human RTT records appear on Probetron output.
- Structured traffic continues independently through the printed TCP endpoint.
- Disconnecting the byte client does not end RTT or the outer lock.

## DAP launch attach and reconnect

Install the documented probe-rs editor extension version on the client.

Use the local ELF, SVD if needed, and matching source tree.

Start and leave this command running:

```sh
"$PROBETRON" debug --host "$PROBETRON_HOST"
```

Configure the editor's remote-server mode to use the printed `127.0.0.1:<port>` endpoint, chip `RP235x`, core 0, and the local source and ELF paths.

Perform these actions:

1. Launch a DAP session.
2. Set a source breakpoint and stop on that line.
3. Step and inspect a local variable and memory.
4. Disconnect only the IDE session.
5. Confirm `"$PROBETRON" status --host "$PROBETRON_HOST" --format edn` still reports the active debug owner.
6. Reconnect with an attach request.
7. Stop the outer debug command.

Expected result:

- Source resolution and file upload remain client-side.
- The DAP server accepts launch and attach.
- The outer lock survives the IDE disconnect and reconnect.
- Neither reconnect nor default outer-command cleanup resets the DUT.
- Final status reports a free target.

## Reset-on-exit and handled termination

Use firmware with a visible boot counter or startup line.

Run and stop a default connect session, then run and stop:

```sh
"$PROBETRON" connect --host "$PROBETRON_HOST" --channel usb --reset-on-exit
```

Expected result:

- The default session does not increment the restart indicator.
- The reset-on-exit session increments it once after child cleanup.

Start another connect session and terminate its recorded local PID:

```sh
"$PROBETRON" connect --host "$PROBETRON_HOST" --channel usb &
probetron_client_pid=$!
sleep 2
kill -TERM "$probetron_client_pid"
wait "$probetron_client_pid" || true
"$PROBETRON" status --host "$PROBETRON_HOST" --format edn
"$PROBETRON" connect --host "$PROBETRON_HOST" --channel usb &
replacement_client_pid=$!
sleep 2
kill -INT "$replacement_client_pid"
wait "$replacement_client_pid" || true
```

Expected result:

- SIGTERM removes local and remote helper processes and loopback listeners.
- Status reports a free target.
- A new session starts and stops without manual cleanup.

## Key rotation and power-loss recovery

Complete one operation, then copy the cache path from the client diagnostics and record its fingerprint:

```sh
export KEY_CACHE='/absolute/path/reported/by/probetron'
test -f "$KEY_CACHE"
ssh-keygen -y -f "$KEY_CACHE" | ssh-keygen -lf -
```

Build and flash a newly generated Probetron image, or replace the Pi with one built from that image, while retaining the same IP address.

Run:

```sh
"$PROBETRON" info --host "$PROBETRON_HOST"
ssh-keygen -y -f "$KEY_CACHE" | ssh-keygen -lf -
```

Expected result:

- The cached private key changes automatically to the new image key.
- Its mode remains `0600`.
- SSH needs no known-host edit and prints no changed-host warning.

Start a connect session and cut Pi power while it is active.

Restore power, wait for wired SSH, and run:

```sh
"$PROBETRON" info --host "$PROBETRON_HOST"
"$PROBETRON" flash --host "$PROBETRON_HOST" --chip "$PROBETRON_CHIP" --speed-khz 1000 "$ELF"
```

Expected result:

- The interrupted client exits with a clear connection failure.
- The Pi boots without filesystem repair.
- Root and boot remain read-only.
- Target identification and flash work without stale lock cleanup.

## Additional error and channel checks

### UART

Wire UART0 as documented and flash firmware that exchanges known bytes at 115200 baud.

Run:

```sh
"$PROBETRON" connect --host "$PROBETRON_HOST" --channel uart --baud 115200 --pty
```

Expected result:

- Data is bidirectional through the reported PTY and TCP endpoint.
- `/dev/ttyAMA0` is the PL011 and is not owned by Bluetooth or a serial console.

### Missing client socat

Run PTY mode from a client environment where `socat` is absent from `PATH`:

```sh
"$PROBETRON" connect --host "$PROBETRON_HOST" --channel usb --pty
```

Expected result:

- Probetron warns that PTY mode needs client-side `socat`.
- The command remains active in TCP-only mode and prints a usable endpoint.
- Stopping it releases the remote lock normally.

### Invalid and oversized ELF

Run:

```sh
printf 'not an elf\n' > /tmp/not-elf.bin
dd if=/dev/zero of=/tmp/oversized.elf bs=1M count=65
"$PROBETRON" flash --host "$PROBETRON_HOST" --chip "$PROBETRON_CHIP" /tmp/not-elf.bin
"$PROBETRON" flash --host "$PROBETRON_HOST" --chip "$PROBETRON_CHIP" /tmp/oversized.elf
"$PROBETRON" status --host "$PROBETRON_HOST" --format edn
rm -f /tmp/not-elf.bin /tmp/oversized.elf
```

Expected result:

- Both flash commands fail before probe-rs opens the target.
- Diagnostics distinguish malformed ELF from the 64 MiB limit.
- Status reports no active transaction after each failure.

## Verify absence of outbound runtime requests

Connect the Pi through a managed switch that mirrors the Pi port to a Linux observer.

Set the observer interface and the Pi Ethernet MAC address, then start capture before powering the Pi on:

```sh
export OBSERVER_IF='enp2s0'
export PI_MAC='dc:a6:32:00:00:00'
sudo tcpdump -U -n -i "$OBSERVER_IF" "ether host $PI_MAC" -w /tmp/probetron-offline.pcap &
capture_pid=$!
```

Power on the Pi with no internet route.

After wired SSH becomes available, run representative operations from the client:

```sh
"$PROBETRON" info --host "$PROBETRON_HOST"
"$PROBETRON" flash --host "$PROBETRON_HOST" --chip "$PROBETRON_CHIP" --speed-khz 1000 "$ELF"
"$PROBETRON" connect --host "$PROBETRON_HOST" --channel usb &
connect_pid=$!
sleep 5
kill -INT "$connect_pid"
wait "$connect_pid" || true
sudo kill -INT "$capture_pid"
wait "$capture_pid" || true
```

Inspect Pi-originated requests on the observer:

```sh
request_count=$(tshark -r /tmp/probetron-offline.pcap \
  -Y "eth.src == $PI_MAC && (dns.flags.response == 0 || http.request || ntp || tls.handshake.type == 1)" \
  -T fields -e frame.number | wc -l)
test "$request_count" -eq 0
tshark -r /tmp/probetron-offline.pcap \
  -Y "eth.src == $PI_MAC" \
  -T fields -e ip.dst -e ipv6.dst | sort -u
```

Expected result:

- No Pi-originated DNS query, HTTP request, NTP request, or TLS client handshake appears.
- All Pi-originated destinations are the configured lab subnet, link-local traffic, or DHCP broadcast.
- Expected inbound key HTTP, SSH, and forwarding requests still work.
