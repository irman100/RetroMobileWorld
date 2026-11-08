# Android c3emu-web wrapper

This crate is a local Android-focused copy of upstream c3emu-web at
9b0d3ae2da326e109428f64d8760ea823ee1b648 from https://github.com/androettop/c3emu.

Local deltas are intentionally narrow:
- catch Rust panics inside the exported C ABI boot/run functions;
- synchronize PMU RTC registers in a running guest when the Android host clock changes.

The CPU/SoC emulator remains the pinned upstream c3emu crate. The upstream
SIM model is no-card and no packet/modem transport is implemented here.
