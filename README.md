# Probetron

Probetron turns one Raspberry Pi 4B into a network appliance for one physically connected RP2350 device under test.
Lab clients on macOS and Linux flash firmware, erase or reset the target, debug it over DAP, read RTT logs, and bridge the DUT serial channel without an interactive login shell on the Pi.
One USB-C cable carries a console of its own, so a rig that the lab network cannot reach is still diagnosed by hand.

The project stays project-agnostic, so a rig serves whatever RP2350 board is wired to it.
The first target is a piezo-driver setup where a Pico 2 W is the RP2350 DUT, but nothing here knows that.

## What an installation needs

| Part       | Requirement                                                                                             |
| ---------- | ------------------------------------------------------------------------------------------------------- |
| rig        | one Raspberry Pi 4B, one SD card of 4 GB or more, wired Ethernet, and a 5 V supply                      |
| target     | one RP2350 board with SWCLK, SWDIO, RUN, and ground reachable                                           |
| client     | macOS or Linux with `bb` (Babashka) and `ssh`, plus `socat` for `--pty` and `curl` for the raw SSH path |
| build host | a Debian 13 arm64 machine, and only when somebody builds the rig image                                  |

The Pi drives SWD directly over its own SPI0 bus, so the installation needs no separate debug probe.

## Quickstart

One path from nothing to firmware output, with a Pico 2 W as the DUT and a rig that answers to `probetron`.
Each step names the section that explains it.

**1. Build the image and write the card.**
This step alone wants a Debian 13 `aarch64` host with root and 10 GiB free in `/var/tmp`.
See [Build the rig image](#build-the-rig-image).

```sh
bb image
cd build
sha256sum -c probetron-rpi4.img.xz.sha256
lsblk                                    # find the card, for example /dev/sdX
xz -dc probetron-rpi4.img.xz | sudo dd of=/dev/sdX bs=4M conv=fsync status=progress
sync
```

**2. Wire the Pico 2 W and boot the rig.**
See [Wire the target](#wire-the-target), and read the resistor note there before you solder.

```text
Pi pin 23  ->  SWCLK
Pi pin 19  ->  1 kΩ  ->  SWDIO
Pi pin 21  ->  the DUT side of that resistor
Pi pin 37  ->  RUN
Pi pin 20  ->  GND
```

Put the card in the Pi, plug the DUT USB cable into any Pi receptacle, connect wired Ethernet, and power up.
Give the Pi a DHCP reservation on the lab router so that the name `probetron` resolves, which the default hostname of the image already matches.

**3. Install the client.**
The rig needs nothing from this step, which runs on your own machine.

```sh
bb package
mkdir -p ~/.local/probetron ~/.local/bin
tar -xzf build/probetron-0.1.0.tar.gz -C ~/.local/probetron
ln -sf ~/.local/probetron/bin/probetron ~/.local/bin/probetron
export PROBETRON_HOST=probetron
export PROBETRON_CHIP=RP2350
```

**4. Prove the link.**

```sh
probetron info
```

The `target:` block must show a debug port and its components, and the `probe:` line must read `0:0:/dev/spidev0.0 swd`.
probe-rs reads the debug port over SWD but names the exact part only for some families, so the block identifies an RP2350 by name yet identifies many other chips by vendor and core alone.
A target that reads as absent is most often the resistor orientation of step 2, or an SWD clock too fast for the wiring, which `--speed-khz` lowers.

**5. Build a hello world.**
Nothing here is specific to Probetron: it is the ordinary Pico SDK path, and it runs on your own machine.

```sh
git clone --recurse-submodules https://github.com/raspberrypi/pico-sdk
git clone https://github.com/raspberrypi/pico-examples
cmake -S pico-examples -B build-pico -DPICO_BOARD=pico2_w -DPICO_SDK_PATH=$PWD/pico-sdk
cmake --build build-pico --target hello_usb
```

`hello_usb` prints over the native USB of the DUT, so the quickstart needs no wire beyond the cable of step 2.

**6. Flash it and read it.**

```sh
probetron flash build-pico/hello_world/usb/hello_usb.elf
probetron connect --channel usb --local-port 45678
```

`flash` pulses RUN after a verified download, so the firmware is already running when `connect` prints its endpoint.
Read the DUT in a second terminal, where `Hello, world!` arrives once a second.

```sh
nc 127.0.0.1 45678
```

End the `connect` session with Ctrl-C to release the target lock, which no other operation can take while that session holds it.

**7. Pin the receptacle, if you ever need to.**
This step is optional, and most benches never take it.

A rig owns one DUT and runs no console, so the udev rule matches the one CDC serial device that the rig can see, and that device is the DUT.
A board in BOOTSEL enumerates as mass storage and a USB-serial adapter binds another driver, so neither is ever mistaken for one.
Take this step only on a bench that really does present a second CDC device, where the rule must narrow to one physical socket.

Read the receptacle from the running rig over the raw SSH path of [Raw SSH access](#raw-ssh-access).

```sh
curl -fsS -o /tmp/probetron_key http://probetron/probetron_key
chmod 0600 /tmp/probetron_key
ssh -i /tmp/probetron_key -T \
  -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null -o GlobalKnownHostsFile=/dev/null -o LogLevel=ERROR \
  probetron@probetron 'udevadm info --attribute-walk --name=/dev/ttyACM0 | grep -m5 KERNELS'
```

Record the nearest `KERNELS` value as `:usb-kernels` in `image/manifest.edn`, for example `"1-1.3"`, and describe the socket in `:usb-port-label`, for example `"lower USB 2.0 receptacle nearest the Ethernet jack"`.
Build and write the card again, and a board in any other receptacle then stops being the DUT.
Nothing defaults that receptacle, because a guessed one would quietly make some other socket the DUT.

## Wire the target

The SWD wiring is fixed, and the rig inlines it.

| Pi 4B signal | GPIO   | Header pin    | DUT signal         |
| ------------ | ------ | ------------- | ------------------ |
| SPI0 SCLK    | GPIO11 | 23            | SWCLK              |
| SPI0 MISO    | GPIO9  | 21            | SWDIO directly     |
| SPI0 MOSI    | GPIO10 | 19            | SWDIO through 1 kΩ |
| reset        | GPIO26 | 37            | RUN                |
| GND          | —      | 20, 25, or 39 | GND                |

The 1 kΩ resistor sits in the MOSI leg alone: header pin 19 goes to one end of the resistor, and the other end goes to DUT SWDIO.
Header pin 21 joins that same DUT-side end, so MISO reads the wire that the DUT drives.
Wiring MISO to the Pi side of the resistor reads back what the Pi wrote and never sees the target, which is the one orientation mistake that looks like a dead target.
Keep all four wires short, and give SWCLK and SWDIO a ground return on pin 20 or pin 25.

The UART wiring is optional and serves `--channel uart` alone.

| Pi 4B signal | GPIO   | Header pin | DUT signal |
| ------------ | ------ | ---------- | ---------- |
| UART0 TXD    | GPIO14 | 8          | DUT RX     |
| UART0 RXD    | GPIO15 | 10         | DUT TX     |
| GND          | —      | 6          | GND        |

The image removes the serial console from the kernel command line and masks the serial getty, so the DUT owns `/dev/ttyAMA0` alone.

The DUT USB cable goes into any receptacle of the Pi, and the image turns the DUT into `/dev/probetron-dut`.
That udev rule matches the CDC tty class, and never a vendor ID, a product ID, or a serial descriptor, because those belong to the firmware: a DUT reflashed with a different USB identity would otherwise stop matching at the worst moment.
A rig owns one DUT and runs no console, so the one CDC serial device it can see is that DUT, and neither a board in BOOTSEL nor a USB-serial adapter matches the rule.
A bench that really does present a second CDC device records a receptacle in `image/manifest.edn`, and the rule then narrows to that physical socket, so a board anywhere else is not the DUT.
`--channel usb` needs that cable only when the firmware exposes native USB CDC; SWD, RTT, and DAP all work without it.

## Target boards

The Pi side of [Wire the target](#wire-the-target) never changes, so wiring a new board is a matter of finding SWCLK, SWDIO, RUN, and GND on it.
Each board below names those four points, and the official pinout carries the rest.

Two references cover every Raspberry Pi board: the [Pico-series documentation](https://www.raspberrypi.com/documentation/microcontrollers/pico-series.html) holds the pinout figures, and each board's datasheet holds the same figure as a PDF.

### Raspberry Pi Pico 2 W

The wireless antenna owns the bottom edge, so the RP2350 debug port moves to three pads in the middle of the board rather than the edge where a plain Pico 2 carries it.
Hold the board with the USB connector at the top, and the three pads read **SWCLK, GND, SWDIO** from left to right; the square pad is SWCLK.
RUN is not on those pads: it is pin 30 on the right side of the 40-pin header, the third pin up from the bottom-right corner.
A bare Pico 2 W ships those three pads unpopulated, so a breakout board that already presents them as pins saves you the soldering.

| Probetron wire            | Pico 2 W point                     |
| ------------------------- | ---------------------------------- |
| SWCLK — pin 23            | SWCLK, the left debug pad (square) |
| SWDIO — pin 19 through 1 kΩ, joined by pin 21 | SWDIO, the right debug pad |
| RUN — pin 37              | RUN, header pin 30                 |
| GND — pin 20, 25, or 39   | GND, the middle debug pad          |

The three debug pads carry no power, so the DUT still needs its own supply over the USB cable or VSYS.
The datasheet figure that fixes this order is Figure 4 of the [Pico 2 W datasheet](https://datasheets.raspberrypi.com/picow/pico-2-w-datasheet.pdf).

## Give the rig an address

The rig uses wired Ethernet, brings `eth0` up with DHCP, and announces nothing over multicast.
Give it a fixed address with a DHCP reservation on the lab router, keyed to the Ethernet MAC of the Pi, because a reservation needs no change to the image and survives every reflash.
A hostname belongs to the image: `bb image IGconf_device_hostname=probetron-02` renames one rig at build time, and the default hostname is `probetron`.
A static address without a reservation needs a rebuilt image that carries its own `systemd-networkd` drop-in, because the root filesystem of a running rig is read-only.

Every client command then names that address with `--host` or `PROBETRON_HOST`.

## Reach a rig over USB

Every rig answers on its USB-C receptacle as well as on the LAN.
That receptacle runs in peripheral mode, so the rig presents itself to a laptop as one USB Ethernet device, holds `192.168.99.1` on that link, and leases the client an address of its own.
One cable is therefore the whole path: no lab network, no DHCP server of yours, no address to look up, and no way to reach the wrong Pi, because the link has exactly two ends.

```sh
probetron shell --usb          # an interactive login on the rig
probetron info --usb           # or any other command, over the same cable
```

`--usb` replaces `--host` on every command.
The rig never routes for the client: its DHCP server emits no router and no name server, so the internet of the client stays where it was.

```sh
probetron shell --usb
probetron@probetron:~$ networkctl status eth0
probetron@probetron:~$ journalctl -b -u systemd-networkd
```

`shell` is the one command that hands the terminal of the operator straight to the rig, and it exists for the case that every other command cannot serve: a rig that answers nothing on the LAN.
Reading `journalctl` there is the only post-mortem a rig has, because the journal lives in memory and dies with its boot.

The cable carries power as well as data.
A laptop port that holds up a Raspberry Pi 4 runs the rig on its own, and a port that browns out asks for 5 V on header pins 2 and 6 from the ordinary supply, with the USB-C cable left for data.
A cable that carries power alone reaches nothing, which looks exactly like a rig that never booted.
The four USB-A receptacles belong to another controller, so the DUT keeps its own cable and its own rules throughout.

### The report of every boot

A rig that answers on neither the LAN nor the console has one thing left to say, and it says it on the card.
`probetron-report.service` writes `probetron-report.txt` to the boot filesystem half a minute into every boot and again at every shutdown: the units that failed, every link and its addresses, whether a USB device controller exists at all, the kernel messages about the link and the gadget, and the journal of that boot.

Power the rig, wait a minute, power it down, and read that file from the card on any machine, because the boot filesystem is FAT and every laptop mounts it.

```text
probetron report: boot
2026-08-25T18:41:02+00:00
Linux probetron 6.18.39+rpt-rpi-v8 ...
```

An absent report is itself the answer: the rig never reached userspace, and the fault is the card, the power, or the firmware rather than anything above them.
This is the one thing a rig ever writes to persistent storage, and it arrives through a temporary neighbour and one rename, so a power loss during the write keeps the report of the boot before it.

## Install the client

The client is the release archive plus Babashka, and it never needs privilege.

```sh
mkdir -p ~/.local/probetron ~/.local/bin
tar -xzf probetron-0.1.0.tar.gz -C ~/.local/probetron
ln -sf ~/.local/probetron/bin/probetron ~/.local/bin/probetron
```

`bin/probetron` resolves its own symlinks and loads `../lib`, so the tree works wherever it is unpacked.
Install Babashka once with `brew install borkdude/brew/babashka` on macOS, with the Babashka install script or mise on Linux, and install `socat` from Homebrew or `apt` if you want `--pty`.
A checkout runs the same client from `bin/probetron`, which loads `../src` instead.

## Public commands

```text
probetron info    --host <host> [--speed-khz <speed>] [--format <text|edn>]
probetron status  --host <host> [--format <text|edn>]
probetron flash   --host <host> --chip <chip> [--speed-khz <speed>] <elf>
probetron erase   --host <host> --chip <chip> [--speed-khz <speed>]
probetron reset   --host <host>
probetron connect --host <host> --channel <usb|uart> [--baud <baud>] [--usb-wait-seconds <seconds>] [--local-port <port>] [--rtt <elf> --chip <chip> [--speed-khz <speed>]] [--pty] [--reset-on-exit]
probetron debug   --host <host> [--local-port <port>] [--reset-on-exit]
probetron shell   --host <host> | --usb
```

`--usb` replaces `--host` on every command and names the rig on its USB console cable, which needs no lab network at all.

Target-specific values also come from ordinary environment variables, and an explicit option always wins.

| Variable                     | Default for          | Value  | Accepted range                                  |
| ---------------------------- | -------------------- | ------ | ----------------------------------------------- |
| `PROBETRON_HOST`             | `--host`             | —      | a DNS name, an IPv4 literal, or an IPv6 literal |
| `PROBETRON_CHIP`             | `--chip`             | —      | a probe-rs chip name such as `RP2350`           |
| `PROBETRON_SPEED_KHZ`        | `--speed-khz`        | 20     | 1 to 50000 kHz                                  |
| `PROBETRON_UART_BAUD`        | `--baud`             | 115200 | 50 to 4000000 bit/s                             |
| `PROBETRON_USB_WAIT_SECONDS` | `--usb-wait-seconds` | 10     | 0 to 60 seconds                                 |

A bench session usually exports the two values that never change and then names nothing else.

```sh
export PROBETRON_HOST=probetron.lab
export PROBETRON_CHIP=RP2350
```

`--chip` is the name that the pinned probe-rs knows, which `probe-rs chip list | grep -i rp2` prints on the rig, and hardware qualification records the exact spelling for RP2350.
`--speed-khz` is the SWD clock, and 20 kHz is the conservative default that reaches a target on almost any wiring; a qualification run raises it to the fastest speed one rig holds.
`info` clocks the bus at this speed too, so a target that auto-detection cannot pin still reads reliably.

Exit status 0 reports success, 1 reports a failed operation, 64 reports a usage error, 69 reports a missing rig resource, and 75 reports a rig that is busy with another operation.
The client returns exactly what the rig returned, and 255 means that SSH never reached the rig at all.

### info

`info` identifies the client, the rig, and whatever sits on the SWD bus, and it takes the target lock while it asks.

```sh
probetron info --host probetron.lab
```

```text
client probetron: 0.1.0
client babashka: 1.13.219
client key: /Users/bench/.cache/probetron/keys/probetron.lab.key
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

`--format edn` prints the same facts as `{:client {:probetron ... :babashka ... :key ...} :rig {...}}`, where the rig map carries `:probetron`, `:babashka`, `:probe-rs`, `:os`, `:hostname`, `:machine-id`, `:probe`, and `:target`.
Only `:probe` nests, because `:selector` and `:protocol` say nothing without it.
Use it whenever a script must compare versions rather than read them.

### status

`status` reports who owns the target and never opens the hardware, so it answers even while a session runs.

```sh
probetron status --host probetron.lab
```

```text
lock: held
active command: connect
active pid: 4213
active since: 2026-08-25T11:23:28.036818843Z
```

`--format edn` prints `{:lock :held :active {:command :connect :pid 4213 :started-at "..."}}`, and a free target prints `{:lock :free}`.

### flash

`flash` uploads one ELF file, downloads it through probe-rs with verification, and starts the firmware by pulsing RUN.

```sh
probetron flash --host probetron.lab --chip RP2350 \
  firmware/target/thumbv8m.main-none-eabihf/release/piezo-driver
```

The ELF travels over standard input, so the rig never learns a client path, and the client refuses a file that is missing, not a regular file, larger than 64 MiB, or without the ELF magic number.
The rig repeats those checks on the upload it received, reads the ELF header, and refuses a table or a load segment that reads outside the file, all before probe-rs opens the target.
Only a verified download pulses RUN, so a failed flash leaves the target where it was, and nobody has to press BOOTSEL or a reset button to start the firmware.

### erase

`erase` delegates once to probe-rs and gives back exactly what probe-rs said, with no second destructive attempt.

```sh
probetron erase --host probetron.lab --chip RP2350
```

### reset

`reset` pulses GPIO26 and never asks probe-rs, so it restarts the firmware that is already on the target.

```sh
probetron reset --host probetron.lab
```

The pulse holds RUN low for 100 ms and then releases the line to its pull-up.

### connect

`connect` bridges one DUT byte channel to a TCP endpoint on the client and holds the target until the client lets go.

```sh
probetron connect --host probetron.lab --channel uart
```

```text
tcp://127.0.0.1:45678
```

The endpoint appears as soon as SSH starts, so a host program has somewhere to go while the forward and the rig listener are still opening.
`--local-port 45678` names that port, and a session without one reserves a free ephemeral port on the client loopback, so ordinary use needs no port bookkeeping.
The endpoint listens on client loopback alone, and the rig service listens on Pi loopback alone, so no third machine reaches either.

`--channel uart` carries the Pi UART at `--baud`, which defaults to 115200, and `--channel usb` carries the native USB CDC device of the DUT and takes no bit rate.
The USB device appears only after the DUT enumerates, so `--usb-wait-seconds` bounds how long the rig waits for it before it gives up.

One byte client is active at a time, and the next one connects as soon as that client leaves, so reconnecting a terminal needs no new session.

```sh
nc 127.0.0.1 45678             # macOS and Linux
socat -,raw,echo=0 TCP:127.0.0.1:45678
```

### PTY mode

`--pty` presents the same endpoint as a pseudo-terminal, which is what `screen`, `minicom`, `picocom`, and every other program that wants a serial device asks for.

```sh
probetron connect --host probetron.lab --channel usb --pty
```

```text
tcp://127.0.0.1:45678
/run/user/1000/probetron-pty14807993/tty
```

```sh
screen /run/user/1000/probetron-pty14807993/tty     # Linux
screen /var/folders/.../probetron-pty14807993/tty   # macOS
```

The client builds that terminal with `socat`, and the link lives in a private volatile directory that only the client account may enter: `$XDG_RUNTIME_DIR` on Linux and the private temporary directory of the account on macOS.
socat creates the link at once but connects only when a program opens the terminal, so nothing occupies the one rig byte client until somebody reads DUT bytes.
Install socat with `brew install socat` on macOS or `apt install socat` on Debian and Ubuntu.
A client without socat keeps the whole session, prints that warning with those two commands, and leaves the printed TCP endpoint usable.
A pseudo-terminal that closes later is a lost presentation alone: the client says so, and the session keeps the target and the TCP endpoint.

### RTT beside the bridge

`--rtt <elf>` decodes the RTT logs of the firmware that the target already runs, next to a bridge that stays usable.

```sh
probetron connect --host probetron.lab --channel usb --rtt firmware.elf
```

The ELF names the RTT control block and the log format, it travels over the same bounded upload path as a flash, and it validates before any hardware opens.
`--rtt` needs `--chip`, which `PROBETRON_CHIP` usually supplies, and it takes `--speed-khz` like every other SWD operation.
probe-rs attaches rather than downloads, so RTT joins a running target and never resets it or rewrites its flash.

The two streams stay apart: DUT bytes go to the TCP endpoint, and decoded RTT text goes to the standard output and standard error of the `probetron connect` command itself.
A terminal reading the bridge and this terminal printing logs therefore never mix, and a decoder that stops leaves the bridge running.

### debug

`debug` forwards a multi-session probe-rs DAP server, which is what gives an editor source-level debugging of the DUT.

```sh
probetron debug --host probetron.lab
```

```text
tcp://127.0.0.1:45678
probe: 0:0:/dev/spidev_swd0 swd
```

The client prints the endpoint as soon as SSH starts, and the rig then announces the probe selector that a DAP request repeats before it serves DAP on Pi loopback, which the client forwards to one client loopback port exactly as `connect` does.
The server accepts one DAP client after another, so an editor disconnects and connects again as often as it likes while the outer command keeps the target lock and the DUT keeps its state.
Only ending `probetron debug` releases the target.

Everything project-specific travels inside the DAP request rather than on the rig command line, because the rig knows no project: the chip, the SWD speed, the ELF, the SVD, and the source layout all belong to the editor configuration on the client.

### shell

```sh
probetron shell --usb
probetron shell --host probetron.lab
```

`shell` opens one interactive login on the rig and gives back the status of that login.
It fetches the rig key exactly as every other command does, asks SSH for a terminal, and names no remote command, so the login shell of the `probetron` account answers.
It takes no target lock, so a running session of somebody else keeps the DUT while an operator reads the rig.

The other commands on this page are the whole of the operating surface, and this one is the diagnostic surface: it reaches the rig and never the target.

## Debug from an editor

The probe-rs editor integration connects to an already-running DAP server when the configuration names one, so point it at the endpoint that `probetron debug` printed.

```jsonc
// .vscode/launch.json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "probe-rs-debug",
      "request": "launch",
      "name": "Probetron: flash and debug",
      "server": "127.0.0.1:45678",
      "cwd": "${workspaceFolder}",
      "chip": "RP2350",
      "probe": "0:0:/dev/spidev_swd0",
      "wireProtocol": "Swd",
      "speed": 1000,
      "flashingConfig": { "flashingEnabled": true, "haltAfterReset": false },
      "coreConfigs": [
        {
          "coreIndex": 0,
          "programBinary": "target/thumbv8m.main-none-eabihf/debug/piezo-driver",
          "rttEnabled": true,
        },
      ],
    },
    {
      "type": "probe-rs-debug",
      "request": "attach",
      "name": "Probetron: attach to running firmware",
      "server": "127.0.0.1:45678",
      "cwd": "${workspaceFolder}",
      "chip": "RP2350",
      "probe": "0:0:/dev/spidev_swd0",
      "wireProtocol": "Swd",
      "speed": 1000,
      "flashingConfig": { "flashingEnabled": false },
      "coreConfigs": [
        {
          "coreIndex": 0,
          "programBinary": "target/thumbv8m.main-none-eabihf/debug/piezo-driver",
        },
      ],
    },
  ],
}
```

`launch` downloads the ELF and starts it, and `attach` joins firmware that is already running, which is the configuration to use after `probetron flash`.
`server` is what keeps the editor from starting its own local `dap-server`, and `probe` repeats the selector that `probetron debug` announced, so the request reaches the one SPI bus that carries SWD.
The ELF, the source tree, the SVD, and every editor extension stay on the client, and the rig stores none of them and keeps nothing after the session.

Disconnecting the editor ends one DAP session alone.
The target lock, the target state, and the forwarded endpoint all survive it, so reconnecting needs no new `probetron debug`.
`probetron debug --reset-on-exit` asks for one best-effort reset after the whole session ends, and a session without it leaves the DUT exactly as the last DAP client left it.

The exact configuration keys belong to the editor extension and the probe-rs release, so hardware qualification records the versions it tested and corrects this example when they differ.
Qualification also records how the pinned probe-rs resolves `programBinary` across a forwarded connection: a server that resolves that path on the rig cannot see a client ELF, and that finding belongs here rather than in a workaround that nobody wrote down.

## Client authentication

The rig image generates one SSH keypair at build time and serves its private half over plain HTTP, so the lab LAN is the whole trust boundary.
Every operation fetches that key again before it opens SSH, which is why a rebuilt or replaced Pi at the same address needs no cleanup on any client.

```text
http://<host>/probetron_key  ->  ${XDG_CACHE_HOME:-$HOME/.cache}/probetron/keys/<host>.key
```

Each host keeps its own cache file, and unchanged bytes leave that file alone.
Changed bytes arrive through a temporary neighbour with mode `0600` and one atomic rename, so SSH never reads half a key.
An HTTP failure, an empty answer, an answer that is not a private key, a cache file that anyone else can read, and a failed replacement all stop the operation with a named repair, even when an older copy is still there, because a stale key hides a rig that no longer answers.

The client then wraps the validated rig command in one SSH invocation that trusts no persistent host key.

```sh
ssh -i <cache> -T \
  -o BatchMode=yes -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null -o GlobalKnownHostsFile=/dev/null \
  -o LogLevel=ERROR \
  probetron@<host> 'sudo -n /usr/local/sbin/probetron-rig flash --chip RP2350 --speed-khz 20'
```

Both known-host files are `/dev/null`, and the log level drops the new-host and changed-host warnings that this provokes, while every SSH error and all remote stderr still reach the client.
Every token of the remote command is quoted for a POSIX shell, so no public value can become a second token however it passed validation, and each ELF file travels on standard input rather than as a client path.
A long session adds `-L 127.0.0.1:<local>:127.0.0.1:<rig>` and `-o ExitOnForwardFailure=yes`, so a session whose forward never appeared ends instead of printing an endpoint that reaches nothing.

## Raw SSH access

Every operation is one ordinary SSH command, so a script that cannot install the client reaches the same rig entry point.

```sh
curl -fsS -o /tmp/probetron_key http://probetron.lab/probetron_key
chmod 0600 /tmp/probetron_key
ssh -i /tmp/probetron_key -T \
  -o BatchMode=yes -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null -o GlobalKnownHostsFile=/dev/null \
  -o LogLevel=ERROR \
  probetron@probetron.lab 'sudo -n /usr/local/sbin/probetron-rig info'
```

Download the key again before every operation, because the rig generates a new keypair on every image build.
Mode `0600` is what SSH insists on, and both known-host files are `/dev/null`, so replacing a Pi at the same address needs no cache cleanup on any client.
`sudo -n /usr/local/sbin/probetron-rig` needs no interactive login shell and takes the same target lock as every client operation.

```text
probetron-rig info    [--speed-khz <speed>] [--format <text|edn>]
probetron-rig status  [--format <text|edn>]
probetron-rig flash   --chip <chip> [--speed-khz <speed>]
probetron-rig erase   --chip <chip> [--speed-khz <speed>]
probetron-rig reset
probetron-rig connect --channel <usb|uart> [--baud <baud>] [--usb-wait-seconds <seconds>] [--rtt --chip <chip> [--speed-khz <speed>]] [--reset-on-exit]
probetron-rig debug   [--reset-on-exit]
```

`flash` and `connect --rtt` read one bounded ELF file from standard input, so a raw flash pipes the file in.

```sh
ssh -i /tmp/probetron_key -T -o BatchMode=yes -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
  -o GlobalKnownHostsFile=/dev/null -o LogLevel=ERROR \
  probetron@probetron.lab 'sudo -n /usr/local/sbin/probetron-rig flash --chip RP2350 --speed-khz 20' \
  < firmware.elf
```

A raw long session forwards the rig service itself: add `-L 127.0.0.1:45678:127.0.0.1:5555` for `connect` and `-L 127.0.0.1:45678:127.0.0.1:50000` for `debug`, both with `-o ExitOnForwardFailure=yes`.
The rig rejects `--host`, `--local-port`, and `--pty`, which belong to the client alone.

## One operation at a time

One `flock` transaction owns the target hardware at a time.
`info`, `flash`, `erase`, and `reset` hold that lock for one hardware operation, `connect` and `debug` hold it until their outer SSH command ends, and `status` never takes it.

A second operation never waits: it fails at once with status 75 and names the operation that already owns the target.

```text
probetron-rig: the rig is busy with connect (pid 4213) since 2026-08-25T11:23:28.036818843Z: wait for that operation to end
```

`probetron status` reports the same record without touching the hardware, which is how a script tells a busy bench from an unreachable one.

Cleanup runs once, whether the operation finished, the client disconnected, or `SIGINT`, `SIGTERM`, or `SIGHUP` arrived.
It reaps only the process groups that this operation started, drops the active record, and releases the lock, in that order, so a closed laptop lid frees the bench within the minute that sshd needs to notice.
`SIGKILL`, kernel failure, and power loss are not part of that guarantee, but the lock lives in volatile storage and disappears with the rig, so a reboot always leaves a free target.

Cleanup preserves DUT state by default.
`flash` resets after a verified download because starting the firmware is the point of flashing, and `--reset-on-exit` asks a long session for one best-effort reset after cleanup has reaped every child.
Nothing else touches the target on the way out, so a session that ended by accident leaves the DUT where the operator left it.

## Build the rig image

The rig is one immutable appliance: it builds nothing, stores no project state, and asks the internet for nothing.
Building its image is a deliberate bench operation on one kind of host.

| Prerequisite                                                         | Why                                                                                                                        |
| -------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| Debian 13 (trixie) on `aarch64`                                      | rpi-image-gen supports native Debian arm64 alone, and the pinned probe-rs is an aarch64 GNU binary                         |
| root, or `podman` for an ordinary account                            | the build creates a chroot and mounts pseudo-filesystems in a private mount namespace                                      |
| `git`, `curl`, `tar`, `xz-utils`, `dpkg-dev`, `openssh-client`       | fetching pinned inputs, unpacking them, generating the keypair, and publishing the image                                   |
| rpi-image-gen build dependencies                                     | `bb image --validate-only` fetches the pinned checkout, and `.cache/rpi-image-gen/install_deps.sh` then installs them once |
| 10 GiB of free space in `/var/tmp` and a network path to the pinned archives | the chroot, the package cache, and the raw image, which the build writes under `/var/tmp/probetron-work` |

```sh
bb image
```

```text
probetron: 0.1.0
image: build/probetron-rpi4.img.xz
checksum: build/probetron-rpi4.img.xz.sha256
sha256: <64 hexadecimal digits>
```

The build does every scratch step in `/var/tmp/probetron-work`, which `TMPDIR` moves, because a chroot carries device nodes and files that only root may open, and a project directory on a network share or a virtual-machine share grants neither.
It checks that directory for 10 GiB of free space before it fetches anything, so a filesystem too small for one image stops the build in a second rather than an hour.
That directory is also the package cache of every later build, so removing it costs the next build a fresh download and nothing else.

Both outputs arrive through a temporary neighbour and one rename each, so an interrupted build replaces neither a valid image nor a valid checksum.
`bb image --validate-only` stops as soon as rpi-image-gen has accepted the configuration and resolved every layer, which is the whole check that a checkout without build dependencies, privilege, or a spare hour can run.
Any `key=value` argument reaches rpi-image-gen unchanged, so `bb image IGconf_device_hostname=probetron-02` renames one rig without editing a tracked file.

### Verify and write the card

```sh
cd build
sha256sum -c probetron-rpi4.img.xz.sha256      # Linux
shasum -a 256 -c probetron-rpi4.img.xz.sha256  # macOS
```

Write the verified image to the card, and be certain of the device name before you do.

```sh
lsblk                                          # Linux: find the card, for example /dev/sdX
xz -dc probetron-rpi4.img.xz | sudo dd of=/dev/sdX bs=4M conv=fsync status=progress
sync
```

```sh
diskutil list                                  # macOS: find the card, for example /dev/disk4
diskutil unmountDisk /dev/disk4
xz -dc probetron-rpi4.img.xz | sudo dd of=/dev/rdisk4 bs=4m
diskutil eject /dev/disk4
```

Raspberry Pi Imager also writes the file, with customisation turned off, because the image already carries its identity, its key, and its network configuration.

### First boot

The rig names root and the boot filesystem by slot, and rpi-image-gen fills those slots from what the bootloader of the Pi publishes about the device it booted from.
Firmware old enough to publish neither leaves both slots empty, and a rig whose root device never appears waits in its initramfs with nothing to say: the activity LED blinks, the Ethernet LEDs light from the bootloader alone, and no network, no console, and no report ever arrive.
The image therefore fills the same two slots from the partition labels as well, so no rig depends on the age of the firmware on the board it happens to run on.

Put the card in the Pi, connect the wired LAN, connect the DUT, and power up.
The rig asks for no APT repository, no DNS name, and no time server at first boot or later, so it comes up on a lab LAN with no route to the internet.
There is no setup wizard and no first-boot expansion: `probetron info --host <host>` is the whole acceptance check.

## Rig design reference

### Target ownership

`/usr/bin/flock` takes `/run/probetron/target.lock` without waiting and holds it through a `/bin/cat` that lives exactly as long as the operation, so the lock also disappears when the rig dies.
After it takes the lock, the rig writes `/run/probetron/active.edn` through a temporary neighbour, so a reader sees the whole record or none of it.

```edn
{:command :connect :pid 4213 :started-at "2026-08-25T11:23:28.036818843Z"}
```

Every long-running helper starts through `/usr/bin/setsid`, which gives it its own process group, and cleanup sends `SIGTERM` and then `SIGKILL` to those groups alone.

### The fixed slot

The one DUT slot never moves, so the rig inlines it.

| Resource               | Value                                             |
| ---------------------- | ------------------------------------------------- |
| SPI device             | `/dev/spidev0.0`                                  |
| probe selector         | `0:0:/dev/spidev0.0`                              |
| SWD SPI alias          | `/dev/spidev_swd0`, matched as `/dev/spidev_swd*` |
| GPIO chip and RUN line | `/dev/gpiochip0`, GPIO26                          |
| UART device            | `/dev/ttyAMA0`                                    |
| DUT USB device         | `/dev/probetron-dut`                              |
| probe-rs               | `/usr/local/bin/probe-rs`                         |
| gpioset                | `/usr/bin/gpioset`                                |
| socat                  | `/usr/bin/socat`                                  |
| byte service           | `127.0.0.1:5555`                                  |
| DAP service            | `127.0.0.1:50000`                                 |
| upload directory       | `/run/probetron/uploads`                          |
| ELF limit              | 64 MiB                                            |

The rig commands are equally fixed.

```text
probe-rs info     --probe 0:0:/dev/spidev0.0 --protocol swd --speed 20 --verbose
probe-rs download --probe 0:0:/dev/spidev0.0 --protocol swd --chip RP2350 --speed 20 --verify <upload>
probe-rs erase    --probe 0:0:/dev/spidev0.0 --protocol swd --chip RP2350 --speed 20
probe-rs attach   --probe 0:0:/dev/spidev0.0 --protocol swd --chip RP2350 --speed 20 <upload>
probe-rs dap-server --port 50000 --ip 127.0.0.1
gpioset --chip /dev/gpiochip0 --hold-period 100ms 26=0
socat TCP-LISTEN:5555,bind=127.0.0.1,reuseaddr,fork,max-children=1 FILE:/dev/ttyAMA0,raw,echo=0,b115200
```

`max-children=1` allows one byte client at a time, and `fork` accepts the next client as soon as that one leaves and opens the channel address again, so a DUT that re-enumerated over USB resolves the stable path once more.
A missing SPI device, GPIO chip, UART, USB device, or executable stops the operation before any process starts, and the diagnostic names the resource and its repair.

### The image

`image/config/probetron.yaml` selects the Raspberry Pi 4 device, the `image-rpios` layout, and seven named layers, and each layer owns exactly one runtime invariant.

| Layer                 | Runtime invariant                                                                                                                                                      |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `probetron-runtime`   | The appliance exists: the release archive under `/usr/local`, `bb`, `probe-rs`, `socat`, `gpiod`, `procps`, `sudo`, and `udev`, and the volatile upload directory.     |
| `probetron-access`    | The lab LAN is the trust boundary: one generated key, an unrestricted `probetron` shell with forwarding, sudo for the one immutable command, and the HTTP key service. |
| `probetron-hardware`  | The one DUT slot exists and belongs to nobody else: SPI0, UART0 on GPIO14 and GPIO15 with no console, GPIO26 free, and udev rules that reserve every target device.    |
| `probetron-immutable` | The rig stores nothing: read-only root and boot, sized tmpfs for every writable path, and a journal that dies with its boot.                                           |
| `probetron-offline`   | The rig asks the internet for nothing: no package timer, no time synchronisation, no radio, and no multicast discovery.                                                |
| `probetron-console`   | The rig answers whatever the lab network does: the USB-C receptacle in peripheral mode, one Ethernet gadget, a fixed address, a DHCP server for the client, and a report of every boot on the card. |
| `probetron-boot`      | The rig boots on any Raspberry Pi 4: the by-slot names of root and the boot filesystem come from the partition labels as well as from the bootloader.                    |

| Volatile path    | Bound                                                                                      |
| ---------------- | ------------------------------------------------------------------------------------------ |
| `/tmp`           | 128 MiB tmpfs                                                                              |
| `/var/tmp`       | 32 MiB tmpfs                                                                               |
| `/var/log`       | 32 MiB tmpfs                                                                               |
| `/run/probetron` | 256 MiB tmpfs, which carries the target lock, the active record, and the one 64 MiB upload |
| journal          | volatile, at most 32 MiB of `/run`                                                         |

`image/manifest.edn` is the only file in the project that names a revision, an archive, or a digest.

| Pin            | Value                                                                                            |
| -------------- | ------------------------------------------------------------------------------------------------ |
| rpi-image-gen  | `v2.8.0`, revision `262d4df5a9f9d4133370465399a7958a7c22cdc7`                                    |
| base           | Debian 13 Trixie arm64, `debian-trixie-arm64-minbase-snapshot` at snapshot `20260801T000000Z`    |
| Babashka       | 1.13.219, `linux-aarch64-static`, digest pinned, installed as `/usr/local/bin/bb`                |
| probe-rs       | 0.32.0, `aarch64-unknown-linux-gnu`, digest pinned, installed as `/usr/local/bin/probe-rs`       |
| DUT receptacle | `:usb-port-label` and `:usb-kernels`, both unrecorded unless a bench needs the rule narrowed to one socket |

The build spends nothing before it knows it can finish: the platform gate, the GLIBC check, the pinned checkout, and rpi-image-gen's own validation all run before one byte is fetched, one key is generated, or one image is constructed.
The staging step then verifies every pinned archive against its digest before use, so a mirror that answers with something else stops the build rather than the appliance.

probe-rs 0.32 is the initial pin, and a later pin belongs in `manifest.edn` only once hardware qualification records that probe-rs identifies an RP2350 over this SWD wiring.

## Security model

The lab LAN is the whole authentication trust boundary, and the rig states that plainly rather than pretending otherwise.
Anybody who reaches the rig over the LAN downloads its private key from `http://<host>/probetron_key` and becomes `probetron`.
Man-in-the-middle protection and protection from other lab-LAN users are deliberately absent, and so is any defence against a hostile authenticated user.
Put the rig on a lab network you trust, and do not route it to a network you do not.

The USB console holds the same boundary at the same height: a machine plugged into the USB-C receptacle fetches that key over the gadget link and becomes `probetron` too.
Physical access to the receptacle is therefore access to the rig, which is the trade this appliance already makes for the LAN, and it is what lets an operator reach a rig that answers nothing else.

What the rig does protect is the target and itself.

- `probetron` is a locked, non-root account in no SPI, GPIO, dialout, or `probetron-hardware` group, so it cannot open `/dev/spidev0.0`, `/dev/gpiochip0`, `/dev/ttyAMA0`, or `/dev/probetron-dut` directly.
- `probetron-hardware` owns all four of those devices at mode 0660 and has no members at all, which is the point: the group takes them out of every default group that an account could join, so root is the only reader and writer.
- `probetron` keeps an ordinary shell and TCP forwarding, because the target lock and not the login shell owns the hardware, so a `probetron` shell still cannot bypass locking.
- sshd accepts public keys alone, refuses root, resolves no name, forwards no agent and no X11, and reaps a client that stops answering within a minute.
- The key service answers `GET /probetron_key` and nothing else, and it runs as the unprivileged `probetron-key` account, so no unauthenticated request is ever answered by root.
- Every service that carries DUT bytes or DAP binds Pi loopback alone and exists only while an operation holds the lock, so nothing on the LAN reaches the DUT except through SSH.
- Uploaded ELF files are bounded at 64 MiB and validated structurally before probe-rs opens the target, and every public argument is validated and then quoted as one shell token.
- Root and `/boot/firmware` mount read-only, every writable path is a sized tmpfs, and the journal is volatile, so the rig keeps nothing between boots and power loss needs no filesystem repair.
- The rig identity, the host keys, and the client key are generated at build time, because a read-only `/etc` cannot make them at first boot and every client refuses to cache host keys anyway.

Local-port allocation races, resource exhaustion, `SIGKILL`, kernel failure, and power loss during an individual operation are explicitly not hard-cleanup guarantees.
The recovery from all of them is the same: reboot the rig, and the volatile lock, the volatile record, and every volatile upload are gone.

## Troubleshooting

| Symptom                                                          | Cause                                                                                                   | Repair                                                                                                                                                                   |
| ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `missing SPI device /dev/spidev0.0`                              | SPI0 is off, or the rig booted an image without the hardware layer                                      | check `dtparam=spi=on` in `/boot/firmware/config.txt`, reboot, and confirm `ls -l /dev/spidev0.0` and the `0:0:/dev/spidev0.0` selector that `probetron info` prints     |
| `missing SWD SPI device /dev/spidev_swd*`                        | the udev rule that names the SWD bus is absent                                                          | reinstall the rig image, then confirm `ls -l /dev/spidev_swd0` points at `spidev0.0`; only that alias reaches DAP discovery                                              |
| probe-rs finds no target, or reports an ARM DP error             | SWD wiring, the 1 kΩ orientation, ground, or the SWD clock                                              | check pins 23, 21, and 19 against the wiring table, confirm MISO taps the DUT side of the 1 kΩ resistor, add a ground return on pin 20, then retry, lowering `--speed-khz` when the debug port answers but the memory read does not |
| `the reset of the target failed with gpioset exit ...`           | GPIO26 cannot drive RUN                                                                                 | check the wire from header pin 37 to RUN, check that nothing else claims GPIO26 with `gpioinfo`, and confirm `/dev/gpiochip0` exists                                     |
| firmware flashes but never starts                                | RUN is not wired, so only a verified download and no reset reached the DUT                              | wire pin 37 to RUN; `probetron reset` must restart the firmware on its own                                                                                               |
| `missing UART device /dev/ttyAMA0`                               | UART0 is off, or the serial console still owns it                                                       | check `enable_uart=1` and `dtoverlay=disable-bt` in `config.txt`, confirm no `console=serial0` in `cmdline.txt`, and confirm `serial-getty@ttyAMA0` is masked            |
| UART bytes are missing or garbled                                | the bit rate, the wire pairing, or a missing ground                                                     | match `--baud` to the firmware, cross TX and RX as the table shows, and share ground on pin 6                                                                            |
| `missing DUT USB device /dev/probetron-dut`                      | the firmware exposes no CDC, the board sits in BOOTSEL, or a recorded topology in `manifest.edn` is stale   | confirm the firmware enumerates, leave BOOTSEL, and re-run the USB topology check below if `manifest.edn` names a receptacle                                                 |
| `cannot find socat on this client`                               | the client has no socat, so `--pty` has no pseudo-terminal                                              | `brew install socat` or `apt install socat`; the session and its TCP endpoint keep working meanwhile                                                                     |
| `cannot fetch the rig key from http://<host>/probetron_key`      | the rig is unreachable, or the key service is down                                                      | ping the address, check the wired LAN and the DHCP reservation, and check `probetron-key.service` on the rig                                                             |
| `the cached rig key ... has unsafe permissions`                  | something widened the cache file                                                                        | `chmod 600` that file or remove it; the next operation fetches the key again                                                                                             |
| `the SSH connection to <host> failed` (status 255)               | the address, the LAN, or a rig that has not booted                                                      | confirm the address, retry after boot, and download the key again in case the rig was reflashed                                                                          |
| the rig takes no address and answers nothing on the LAN           | the DHCP server was not up, leases only known hardware, or the link is dead                             | plug one USB-C cable into the rig and run `probetron shell --usb`, then read `networkctl status eth0` and `journalctl -b -u systemd-networkd` there                      |
| the USB console itself never appears                             | the receptacle carries power alone, the port browns out, or the rig never reached userspace              | check that the cable carries data, feed 5 V into header pins 2 and 6 instead, and treat a silent gadget as a rig that is not booting                                     |
| neither the LAN nor the USB console answers                       | the rig is not reaching userspace, or it flashed an image without the console layer                     | read `probetron-report.txt` on the card; an absent report means the boot stopped below userspace, and `config.txt` there must end with `dtoverlay=dwc2,dr_mode=peripheral` |
| the endpoint prints but nothing connects                         | the forward never came up, or nothing listens behind it                                                 | the session ends by itself when the forward fails; otherwise check `--local-port` for a port already in use on the client                                                |
| `the rig is busy with <command> (pid ...) since ...` (status 75) | another operation owns the target                                                                       | run `probetron status`, wait for that operation, or end it on the client that started it                                                                                 |
| status 69 with a named missing resource                          | the rig image or the wiring lacks that resource                                                         | follow the repair in the diagnostic; every one of them names the resource and the fix                                                                                    |
| an editor connects but has no source or symbols                  | the DAP request carries the wrong ELF, or the pinned probe-rs resolved it on the rig                    | check `programBinary` and `cwd` in the editor configuration, and re-read the DAP note under "Debug from an editor"                                                       |

## Hardware qualification

Nothing below is optional for a new bench, and every step records what it measured.
Every `ssh probetron@<host>` command below is the raw SSH path of the previous section, with `-i /tmp/probetron_key` and the same options.
When a measurement differs from what this document or `image/manifest.edn` states, change the pin or the documentation.
A discrepancy that hides behind runtime inventory is a defect, because the rig deliberately owns no inventory.

1. **Identify the target.** Wire SWD as the table above says, then run `probetron info --host <host>`. The `target:` block must show the debug port and its components, and the `probe:` line must read `0:0:/dev/spidev0.0 swd`. Record the exact chip name that `ssh probetron@<host> 'probe-rs chip list' | grep -i rp2` prints, and correct every `--chip RP2350` example here if it differs.
2. **Prove the link at the default speed.** `probetron info` reads the bus at 20 kHz, so run one write as well: `probetron flash --host <host> --chip RP2350 --speed-khz 20 blinky.elf`. It must verify and start the firmware.
3. **Step through the candidate speeds.** Run the same flash five times at each of 20, 100, 250, 500, 1000, 2000, 4000, 8000, 12000, 16000, and 24000 kHz, and stop at the first speed that fails once.

   ```sh
   for speed in 20 100 250 500 1000 2000 4000 8000 12000 16000 24000; do
     for attempt in 1 2 3 4 5; do
       probetron flash --host <host> --chip RP2350 --speed-khz "$speed" blinky.elf ||
         { echo "FAILED at $speed" ; break 2 ; }
     done
   done
   ```

   Record the highest speed that passed every attempt, take one step back from it, and pin that value: change `default-speed-khz` in `src/probetron/operation.clj` and the default in the environment table above, or document why the bench keeps 20 kHz.

4. **Verify GPIO reset.** Flash firmware whose startup is visible from outside, such as a blink pattern that begins with three fast pulses, then run `probetron reset --host <host>`. The startup pattern must begin again, with no BOOTSEL and no physical reset press, and the command must exit 0. A reset that only works when somebody touches the board means GPIO26 never reaches RUN. Reset takes the short lock, so run it while no session holds the target.
5. **Reverify the DUT slot.** Plug the DUT in and read its ancestry on the rig.

   ```sh
   ssh probetron@<host> 'udevadm info --attribute-walk --name=/dev/ttyACM0 | grep -m5 KERNELS'
   ssh probetron@<host> 'udevadm info --name=/dev/probetron-dut | head'
   ```

   `/dev/probetron-dut` must resolve to the CDC tty of the DUT. Where `image/manifest.edn` records a receptacle, the `KERNELS` ancestry must equal `:usb-kernels`, a board in any other receptacle must not resolve, and the pins want an update whenever the receptacle, the cable, or the Pi changes. Where it records none, any second CDC device on the rig makes the slot ambiguous, which is the measurement that decides whether this bench needs a receptacle recorded at all.

6. **Record the versions that DAP needs.** Note `probe-rs:` from `probetron info`, the editor version, and the probe-rs extension version. Open `probetron debug --host <host>`, connect the editor, hit a breakpoint, disconnect, and connect again twice. The lock must stay held (`probetron status` reports `debug`), the DUT must keep its state, and no reset may happen. Correct the editor example above, including how `programBinary` resolves, when the tested versions behave differently, and record the versions that were tested.
7. **Re-run the whole operation set.** `probetron flash`, `probetron erase`, `probetron reset`, `probetron connect --channel uart`, `probetron connect --channel usb`, `--pty` on both macOS and Linux, `--rtt` beside a bridge, and `probetron debug` must each succeed once. RTT text must arrive on the client terminal while the bridge still carries DUT bytes.
8. **Check exclusion.** With `probetron connect` running, a second `probetron flash` must fail immediately with status 75 and name the connect operation and its pid, and `probetron status` must report the same record.
9. **Check cleanup.** Interrupt the session with `Ctrl-C`, close the client terminal, and drop the client from the LAN, one at a time. After each, `probetron status` must report `lock: free` within about a minute, and the rig must carry no leftover children.

   ```sh
   ssh probetron@<host> 'pgrep -a probe-rs ; pgrep -a socat ; ls /run/probetron'
   ```

   The only surviving socat is the key service on port 80, and `/run/probetron/uploads` is empty.

10. **Check the reset policy.** A default session exit must leave the DUT running whatever it ran, and `--reset-on-exit` must restart it once, best effort.
11. **Check the read-only root.** `ssh probetron@<host> 'findmnt -no OPTIONS / ; findmnt -no OPTIONS /boot/firmware ; touch /etc/probetron-test'` must show `ro` for both mounts and refuse the write.
12. **Check offline boot.** Boot the rig on a LAN with no route to the internet and no DNS. `probetron info`, `flash`, `connect`, and `debug` must all work, and `ssh probetron@<host> 'systemctl list-units --failed'` must list nothing that a missing network caused.
13. **Check power loss.** Pull the power during a `probetron connect` session. On the next boot, `ssh probetron@<host> 'journalctl -b | grep -i -e ext4 -e fsck'` must show no filesystem recovery, `probetron status` must report `lock: free`, and every supported operation must work again.

## Develop

`mise.toml` and `mise.lock` pin Babashka and clj-kondo, and Babashka runs every task, so the project needs no other tool.

```sh
mise install            # once, to get the pinned Babashka and clj-kondo
mise exec -- bb check   # lint and test
mise exec -- bb tasks   # every task with its description
```

Drop the `mise exec --` prefix once the pinned tools are on your path.
`bb tasks` is the whole task list, so no command lives in two places.

`bb test` discovers every `test/**/*_test.clj` namespace, so a new test file needs no registration.
It never builds an image, because that build wants a Debian 13 arm64 host, elevated privilege, and several gigabytes; `bb image --validate-only` is the cheap check that any checkout can run.

`src/probetron/` holds one directory per role: `client/` runs on your machine, `rig/` runs on the Pi, and `provisioning/` builds releases and images.
A namespace that all three share, such as `operation` or `version`, sits at the root instead.
A release archive carries every source file except `provisioning/`, because a client installs neither builder.

| Path                                     | Contents                                                           |
| ---------------------------------------- | ------------------------------------------------------------------ |
| `bin/probetron`                          | public client entry point                                          |
| `bin/probetron-rig`                      | rig entry point that the client reaches over SSH                   |
| `src/probetron/operation.clj`            | pure operation model, validators, and rig command construction     |
| `src/probetron/frontend.clj`             | pure command-line dispatch that both entry points share            |
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
| `image/manifest.edn`                         | every pinned revision, archive, and digest of the rig image        |
| `image/config/probetron.yaml`            | the one rpi-image-gen configuration of the appliance               |
| `image/layer/`                           | the seven named appliance layers and their `.rootfs-overlay/` trees |
| `test/probetron/`                        | `clojure.test` namespaces that the runner discovers                |
| `VERSION`                                | the release version, which `probetron.version` repeats             |

Both entry points resolve their own symlinks, then load `../src` in a development checkout or `../lib` in an installation.

## Release packaging

```sh
bb package
bb package --tag v0.1.0
```

```text
probetron: 0.1.0
archive: build/probetron-0.1.0.tar.gz
checksum: build/probetron-0.1.0.tar.gz.sha256
sha256: <64 hexadecimal digits>
```

The `VERSION` file names the release and `probetron.version` repeats it, so one edit can never leave a release half renamed, and `--tag` passes only when it names that version, with or without its leading `v`.
The archive is deterministic: every member carries mode 0644 or 0755, owner 0, and modification time 0, members arrive in one sorted order, and gzip records no name and no timestamp.
Two runs over the same sources therefore write identical bytes, so anybody rebuilds a tag and compares its digest with the published `probetron-<version>.tar.gz.sha256`, whose one line is what `sha256sum -c` reads.

| Archive path        | Contents                                                |
| ------------------- | ------------------------------------------------------- |
| `bin/probetron`     | the public client entry point, mode 0755                |
| `bin/probetron-rig` | the rig entry point, mode 0755                          |
| `lib/probetron/`    | the relocatable source tree that both entry points load |
| `VERSION`           | the release version                                     |
| `README.md`         | this documentation                                      |

`bin/` sits at the archive root, which is where a mise GitHub tool looks for the programs it puts on `PATH`: such a tool downloads `probetron-<version>.tar.gz`, unpacks it into one installation directory, and needs neither a strip nor a rename.
The archive carries no Babashka binary and no test tree, so one archive serves macOS and Linux alike and a client installs Babashka once by itself.
The rig image installs the same archive under `/usr/local`, so the client and the rig always run the same release.

## Open questions

Where releases are published is deliberately unresolved.
The release archive and its checksum are ready to attach to a GitHub release, and a mise tool can install them on `PATH`, but nothing in this project names a publication step.
That decision can wait until somebody needs the second bench.
