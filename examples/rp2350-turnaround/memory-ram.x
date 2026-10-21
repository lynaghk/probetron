/* FLASH names the code region required by cortex-m-rt; this region is SRAM. */
MEMORY {
    FLASH : ORIGIN = 0x20000000, LENGTH = 448K
    RAM : ORIGIN = 0x20070000, LENGTH = 64K
}

SECTIONS {
    .start_block : ALIGN(4) {
        __start_block_addr = .;
        KEEP(*(.start_block));
        KEEP(*(.boot_info));
    } > FLASH
} INSERT AFTER .vector_table;
_stext = ALIGN(ADDR(.start_block) + SIZEOF(.start_block), 8);

SECTIONS {
    .end_block : ALIGN(4) {
        __end_block_addr = .;
        KEEP(*(.end_block));
        __flash_binary_end = .;
    } > FLASH
} INSERT AFTER .uninit;
PROVIDE(start_to_end = __end_block_addr - __start_block_addr);
PROVIDE(end_to_start = __start_block_addr - __end_block_addr);
