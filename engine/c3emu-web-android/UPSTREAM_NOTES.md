# Android c3emu-web wrapper

This crate remains an Android-focused wrapper around the upstream c3emu-web
implementation at commit
`9b0d3ae2da326e109428f64d8760ea823ee1b648` from
https://github.com/androettop/c3emu.

The `c3emu` core crate is vendored locally at `engine/c3emu-core` from the
same pinned upstream revision so the RM-614-specific SoC MMIO dispatch can be
extended and tested without changing the upstream repository or relying on a
remote Git dependency at build time. The upstream GPL-3.0-or-later notices and
source identity are preserved in the vendored directory.

Local core changes in this branch:
- add a stateful, firmware-observed Nokia RAP3G SPI-master MMIO subset at
  `0x088D0000..0x088D0024`;
- expose an explicit `SpiTarget` endpoint for a future separately modelled
  WLAN gSPI device;
- deliberately return no RX response when no target is attached, rather than
  fabricating Broadcom register/data responses.

Existing wrapper deltas remain:
- catch Rust panics inside exported C ABI boot/run functions;
- synchronize PMU RTC registers in a running guest when the Android host clock changes;
- keep bounded access-by-access SPI MMIO tracing and rolling ring diagnostics.

This is a modified GPL-licensed upstream fork, not an independent clean-room
implementation and not yet a Broadcom WLAN or Internet implementation.
