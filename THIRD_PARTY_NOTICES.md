# Third-party notices

No emulator source code or binary is vendored in this branch.

The first Java ME integration calls the separately installed J2ME Loader application through its exported Android file-opening activity. Retro Mobile World does not copy, bundle or modify J2ME Loader code or binaries. Users must install the runtime separately. If J2ME Loader is ever vendored or linked into this app, copy its unmodified licence and required notice files into a dedicated directory under `third_party/j2me-loader/`, pin the exact upstream commit and review all dependency licences.

Candidates researched on 2026-11-04:

- `nikita36078/J2ME-Loader` — Apache-2.0: https://github.com/nikita36078/J2ME-Loader
- `EKA2L1/EKA2L1` — GPL-3.0: https://github.com/EKA2L1/EKA2L1
- `hex007/freej2me` — GPL-3.0-or-later for FreeJ2ME; the bundled ObjectWeb ASM code has its own licence: https://github.com/hex007/freej2me

This file is an integration checklist, not a substitute for the original licences.

## Gradle Wrapper

The Gradle Wrapper launcher scripts and bootstrap JAR are distributed with Gradle 9.6.0 under the Apache License 2.0. The scripts retain their upstream copyright and licence headers. Upstream source: https://github.com/gradle/gradle/tree/v9.6.0.


## Evaluated emulator reference (not yet bundled)

- `androettop/c3emu` — GPL-3.0-or-later, upstream snapshot reviewed at `9b0d3ae2da326e109428f64d8760ea823ee1b648`: https://github.com/androettop/c3emu
- c3emu is a working Nokia C3-00 / RM-614 Series 40 firmware emulator. Its ARM interpreter, FPSX parser and host-frame/key/audio boundary are useful research references.
- Its RM-614 address map, LCD, keypad, storage hooks, PMIC/I2C and DSP models are device-specific and must not be represented as an RM-217 implementation.
- No c3emu source files or binary have been copied into this repository yet. Before incorporation, preserve the exact GPL text, copyright/attribution notices, source revision and notices for all dependencies. The application's existing GPL-3.0-only project licence is compatible with GPL-3.0 code, but every third-party notice remains in force.


## RM-217 desktop boot probe dependency

- The diagnostic program under `engine/rm217-boot-probe` depends on `androettop/c3emu` pinned to commit `9b0d3ae2da326e109428f64d8760ea823ee1b648`, GPL-3.0-or-later.
- The dependency is fetched by Cargo at build time; no c3emu source or firmware image is copied into the Android APK. The probe is a separate desktop diagnostic executable.
- The probe uses c3emu's ARM926 interpreter, MMU helpers and FPSX parsing utilities. It does not instantiate c3emu's RM-614 `Machine`, address map or device models. The probe's RM-217 memory/peripheral setup remains explicitly diagnostic and is not yet a complete RM-217 emulator.
- The probe's complete corresponding source is in this repository; preserve this project's GPL-3.0 license and the upstream c3emu licence/attribution when distributing the executable.


## RM-217 emulator-reuse research

Research reviewed on 2026-11-07 is recorded in `docs/experiments/RM217_EMULATOR_REUSE_RESEARCH.md`. It compares c3emu, the Nokia DCT3 emulator, EmuGII/QEMU and FreeJ2ME, and distinguishes reusable CPU/MMU/image utilities from model-specific SoC devices. No source from those projects has been copied into this repository.
