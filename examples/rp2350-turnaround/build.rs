use std::{env, fs, path::PathBuf};

fn main() {
    let out = PathBuf::from(env::var_os("OUT_DIR").unwrap());
    let memory = if env::var_os("CARGO_FEATURE_RAM").is_some() {
        "memory-ram.x"
    } else {
        "memory-flash.x"
    };
    fs::copy(memory, out.join("memory.x")).unwrap();
    println!("cargo:rustc-link-search={}", out.display());
    println!("cargo:rustc-link-arg=-Tlink.x");
    println!("cargo:rerun-if-changed={memory}");
    println!("cargo:rerun-if-changed=build.rs");

    let mut state = 0x2350_2026u32;
    let payload: Vec<u8> = (0..416 * 1024)
        .map(|_| {
            state ^= state << 13;
            state ^= state >> 17;
            state ^= state << 5;
            state as u8
        })
        .collect();
    fs::write(out.join("payload.bin"), payload).unwrap();
}
