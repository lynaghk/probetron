# Probetron architecture

Probetron gives a lab client remote control of one ARM SWD device under test through a Raspberry Pi 4B rig.

The client reaches the rig over SSH on the lab LAN or over the USB-C console link.

The rig locks the target before it starts a short operation or a long-lived service.

The rig exposes long-lived byte and DAP services on loopback only.

## Recommended formats

Use the Mermaid source in a README when the Markdown renderer supports Mermaid.

Use the Graphviz SVG in an HTML or rich-text newsletter.

Use the ASCII version in plain-text email and terminal output.

The Graphviz source keeps the SVG reproducible and gives future edits a stable layout source.

![Probetron system architecture](architecture.svg)

Source files:

- [Mermaid diagram](architecture.mmd)
- [Graphviz source](architecture.dot)
- [Plain-text diagram](architecture.txt)
- [Rendered SVG](architecture.svg)

## Mermaid

```mermaid
flowchart TB
    build["Build host\nDebian arm64"]
    image["Pinned rig image\nSD card"]
    build -->|bb package / bb image| image
    image -->|boot| rig

    client["Lab client\nmacOS or Linux\nprobetron CLI"]
    lan["Lab LAN\nSSH + key HTTP"]
    usb["USB-C console\nUSB Ethernet\n192.168.99.1"]

    subgraph rig["Raspberry Pi 4B rig"]
        access["Access layer\nprobetron-key.service\nsshd"]
        runner["probetron-rig\nrunner + target lock\nactive operation + DUT log"]
        byte["Byte service\nsocat\n127.0.0.1:5555"]
        dap["DAP service\nprobe-rs\n127.0.0.1:50000"]
        rtt["RTT decoder\nprobe-rs"]
        access --> runner
        runner -->|connect| byte
        runner -->|debug| dap
        runner -->|optional --rtt| rtt
    end

    client --> lan
    client --> usb
    lan --> access
    usb --> access
    client -->|SSH remote command\nELF over stdin| runner
    client -->|SSH local forward| byte
    client -->|SSH local forward| dap

    subgraph hw["Fixed target slot"]
        swd["SPI0 SWD\n/dev/spidev0.0"]
        reset["RUN reset\nGPIO26 / gpioset"]
        uart["UART0\n/dev/ttyAMA0"]
        dutusb["USB CDC\n/dev/probetron-dut"]
        dut["ARM SWD DUT"]
        swd --> dut
        reset --> dut
        uart --> dut
        dutusb --> dut
    end

    runner -->|info / flash / erase / reset| swd
    runner --> reset
    byte -->|channel usb or uart| dutusb
    byte -->|channel uart| uart
    rtt -->|SWD reads| swd

    classDef transport fill:#e8f1fb,stroke:#3973ac,color:#102a43
    classDef runtime fill:#eaf6ee,stroke:#39845a,color:#153b24
    classDef hardware fill:#fff3dc,stroke:#b7791f,color:#4a2c0a
    class client,lan,usb transport
    class access,runner,byte,dap,rtt runtime
    class swd,reset,uart,dutusb,dut hardware
```

## Design notes

Short operations open the target once and return their result over SSH.

Long sessions keep the target lock while the client uses the forwarded byte or DAP service.

The client closing SSH releases the rig-side process groups and target lock.

The USB-C console is an out-of-band path for recovery when the lab LAN is unavailable.

The DUT USB cable carries the optional native USB serial channel and is separate from the rig USB-C console.
