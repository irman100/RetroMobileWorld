# Implementation status

Snapshot: 5 November 2026. The RM-217 07.30 firmware archive has been inspected locally; no RM-217 boot has been achieved yet.

## Implemented

- Android application entry point and interactive phone-shell prototype.
- Three device profiles: Nokia 6300/S40 style, Sony Ericsson K750i style and Nokia N73/S60 style.
- Versioned JSON device-profile registry with logical display dimensions, display aperture and 22 canonical keypad controls.
- All three device profiles start in Java ME. The N73 profile retains its Symbian backend declaration for a later milestone; Symbian is not selectable in the current interface.
- Local Java ME package library:
  - imports `.jar` and `.jad` through Android's document picker;
  - copies packages into app-private storage and identifies them by SHA-256;
  - inspects JAR manifests and JAD descriptors without extracting or executing the package;
  - rejects malformed descriptors, unsupported file types and oversized packages;
  - presents imported MIDlets inside the virtual phone's Java applications screen.
- Read-only FileProvider handoff to the separate J2ME Loader Android app when the user selects RUN.
- Local-only RM-217 firmware intake:
  - accepts an RM-217 Phoenix ZIP or individual BB5 FPSX `.mcusw`, `.ppm_*` and `.image_*` components;
  - for ZIP archives, selects the version-matched MCU, PPM and canonical `image_c_tr` components and ignores installer executables and alternate colour images;
  - validates FPSX record/data-block boundaries and checks MCU image naming;
  - stores selected components under app-private storage using SHA-256-addressed names;
  - never uploads firmware, adds it to APK assets, or executes installer files during import.
- A read-only Python firmware inspection tool and CI tests for FPSX named regions, continuation blocks, BB5 special addresses and VPL metadata.
- A separate PhoneSystemEngine contract for CPU/SoC/firmware emulation, video frames, PCM audio and key-down/key-up events. No RM-217 engine implementation is registered yet.
- Unit tests for JAR/JAD parsing and the BB5 FPSX firmware inspector.
- Versioned catalogue source registry, registry validators and Android CI workflow.
- Product, architecture, engine, catalogue, legal, roadmap and decision documents.

## Implemented but not yet device-verified

- External J2ME Loader handoff. It requires the separate `ru.playsoftware.j2meloader` package to be installed. The integration uses its documented Android `ACTION_VIEW` file-handling entry point, but this branch still needs an end-to-end test on the target phone.
- JAD handoff for descriptors with absolute HTTPS `MIDlet-Jar-URL`. Relative/local JAD references are intentionally not launched by the current prototype.

## Not implemented

- J2ME Loader is not embedded in the Retro Mobile World APK. The game display, canonical keypad events, MIDlet lifecycle and RMS storage are not yet connected to the shell.
- Symbian/EKA2L1 is deferred; no Symbian firmware or SIS/SISX execution is part of the current milestone.
- WAP browser, OTA catalogue and website.
- Manufacturer-specific finished phone artwork and original sound packs; current color LCD/menu rendering is an original approximation, not original firmware UI.
- The RM-217 hardware model and native Android engine are not implemented. c3emu is a feasibility/reference project for RM-614 (C3-00), not a drop-in RM-217 emulator.
- The current RM-217 ZIP import validates the file containers but does not yet enforce VPL CRC equality in the Android app. The supplied 07.30 package has a PPM CRC mismatch against its VPL and must not be treated as a verified boot set.
- The current app importer does not execute Phoenix installer executables or flash a physical handset.
- Historically faithful Nokia 6300 standby/menu/submenu screen flow remains available as a temporary fallback, not the main long-term emulation target.
- Per-title network permissions and runtime isolation beyond the import library's private storage.

## RM-217 firmware findings

- VPL selected: `RM217_0537625_07.30_018.vpl`, product code 0537625, described as RM-217 6300 EURO-C TR Turkey / Latin / black-silver.
- MCU and canonical IMAGE CRC-32 values match the selected VPL. The included PPM CRC-32 is `613D565F`, while the VPL requires `C585B71C`; this remains an unresolved integrity discrepancy.
- The MCU contains two named `MCUSW` regions at flash address `0x00320000`: a 1,024-byte header segment and a 13,656,960-byte main segment. The read-only boot probe now selects the larger segment. Its BB5 header points to offset `0x3D0`; the first ARM word is `0xE24F4008`, not the LDR reset-vector instruction expected by c3emu's RM-614 loader.
- Static disassembly of the first-stage stub suggests an XIP base of `0xF0100000`, candidate ARM entry `0xF01003D0`, and Thumb continuation `0xF0100410`. The early stub loads SP=`0x08004000`, reserves `0x1800` bytes and writes marker `0x067308CA` at `0x08002800`. These are candidate boot-state values, not yet verified by execution.
- PPM data begins at flash address `0x01080000`; the canonical IMAGE data begins at `0x01600000`.
- The factory IMAGE contains Opera Mini 4.0.9 MIDlet metadata (MIDP 2.0 / CLDC 1.0; JAR size 207,413 bytes) and matching 8.3 directory entries. The application has not yet been installed or launched.

## Next engineering steps

1. Resolve the PPM/VPL CRC discrepancy or obtain a matching PPM component before treating this as a bootable firmware set.
2. Build a dedicated RM-217 boot probe: choose the large MCUSW segment, reconstruct its XIP mapping and entry semantics, and record every missing MMIO access.
3. Compare the RM-217 memory map, display controller, keypad, timer/interrupt model, PMIC and flash map with c3emu's RM-614-specific implementation.
4. Bring the RM-217 firmware to its first stable LCD frame on a desktop diagnostic harness.
5. Port only the validated machine core to Android through Rust JNI.
6. Integrate the emulated LCD frame, keypad press/release, PCM audio and persistent flash into the phone shell.
7. After the OS reaches standby, validate its bundled Java ME manager and Opera Mini; then address the network bridge.
