#![no_std]
#![no_main]

use panic_halt as _;
use rp235x_hal as hal;
use rtt_target::{rprintln, rtt_init_print};

#[hal::entry]
fn main() -> ! {
    rtt_init_print!(rtt_target::ChannelMode::NoBlockSkip, 1024);
    rprintln!("TURNAROUND BOOT");
    let mut pac = hal::pac::Peripherals::take().unwrap();
    let mut watchdog = hal::Watchdog::new(pac.WATCHDOG);
    let _clocks = hal::clocks::init_clocks_and_plls(
        12_000_000,
        pac.XOSC,
        pac.CLOCKS,
        pac.PLL_SYS,
        pac.PLL_USB,
        &mut pac.RESETS,
        &mut watchdog,
    )
    .unwrap();
    let mut checksum = 0u32;
    for index in 0..PAYLOAD.len() {
        let byte = unsafe { core::ptr::read_volatile(PAYLOAD.as_ptr().add(index)) };
        checksum = checksum.rotate_left(5) ^ u32::from(byte);
    }
    rprintln!(
        "TURNAROUND READY bytes={} checksum={:08x}",
        PAYLOAD.len(),
        checksum
    );
    loop {
        cortex_m::asm::nop();
    }
}

#[link_section = ".start_block"]
#[used]
static IMAGE_DEF: hal::block::ImageDef = hal::block::ImageDef::secure_exe();

#[used]
static PAYLOAD: [u8; 416 * 1024] = *include_bytes!(concat!(env!("OUT_DIR"), "/payload.bin"));
