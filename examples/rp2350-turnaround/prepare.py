#!/usr/bin/env python3
"""Strip DWARF only and assert identical ELF LOAD contents and addresses."""
from pathlib import Path
import struct
import subprocess


def loads(path):
    data = path.read_bytes()
    assert data[:6] == b"\x7fELF\x01\x01", "expected little-endian ELF32"
    assert struct.unpack_from("<H", data, 18)[0] == 40, "expected ARM ELF"
    phoff = struct.unpack_from("<I", data, 28)[0]
    size, count = struct.unpack_from("<HH", data, 42)
    result = []
    for index in range(count):
        kind, offset, virt, phys, filesz, memsz, flags, align = struct.unpack_from(
            "<8I", data, phoff + index * size)
        if kind == 1:
            result.append((virt, phys, memsz, flags, data[offset:offset + filesz]))
    return result


def main():
    state, checksum = 0x23502026, 0
    for _ in range(416 * 1024):
        state ^= (state << 13) & 0xffffffff
        state ^= state >> 17
        state ^= (state << 5) & 0xffffffff
        checksum = (((checksum << 5) | (checksum >> 27)) & 0xffffffff) ^ (state & 255)
    assert checksum == 0x0da11840
    print(f"independent host payload checksum: {checksum:08x}")
    sysroot = subprocess.check_output(["rustc", "--print", "sysroot"], text=True).strip()
    strip = next(Path(sysroot).glob("lib/rustlib/*/bin/llvm-strip"))
    for mode in ("flash", "ram"):
        original = Path(f"target/{mode}/thumbv8m.main-none-eabihf/release/rp2350-turnaround")
        stripped = Path(f"target/{mode}.elf")
        subprocess.run([str(strip), "--strip-debug", "-o", str(stripped), str(original)], check=True)
        assert loads(original) == loads(stripped), "stripping changed loaded bytes"
        if mode == "ram":
            for virt, phys, memsz, _, contents in loads(stripped):
                assert 0x20000000 <= virt <= virt + memsz <= 0x20082000
                assert 0x20000000 <= phys <= phys + len(contents) <= 0x20082000
        symbols = subprocess.check_output([str(strip.with_name("llvm-nm")), str(stripped)], text=True)
        assert any(line.endswith(" _SEGGER_RTT") for line in symbols.splitlines())
        print(f"{mode}: {original.stat().st_size} -> {stripped.stat().st_size} bytes; "
              f"{sum(len(part[-1]) for part in loads(original))} LOAD bytes unchanged")


if __name__ == "__main__":
    main()
