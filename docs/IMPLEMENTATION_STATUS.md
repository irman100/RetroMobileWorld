# Implementation status

Snapshot: 6 November 2026. The Android firmware-intake app now has a successful CI debug APK that can be installed on a phone. The app imports and inspects user-supplied Nokia 6300 / RM-217 firmware, but it does not execute or boot the original OS. No successful RM-217 OS boot has been achieved.

## Implemented

- Android application entry point and a minimal RM-217 device shell.
- The phone LCD aperture, display aspect ratio, navigation assembly and physical keypad are retained.
- The app opens directly on the firmware-loading screen. The temporary hand-drawn standby screen, launcher grid and placeholder Series 40 category menus are no longer shown.
- Local-only RM-217 Phoenix ZIP intake:
  - selects the version-matched MCU, PPM and canonical image_c_tr components;
  - streams past installer executables and other archive entries without extracting or executing them;
  - validates ZIP entry integrity and BB5 FPSX block boundaries;
  - reads the matching VPL manifest and compares component CRC-32 values;
  - stores the selected files in app-private storage under SHA-256-addressed names;
  - persists a local checksum report so the UI can show the result after the app restarts.
- Individual BB5 FPSX component import remains supported, but an individual component is not a complete firmware set.
- A separate PhoneSystemEngine contract for CPU/SoC/firmware emulation, video frames, PCM audio and key-down/key-up events.
- A read-only Python firmware inspection tool and CI tests for FPSX blocks, BB5 special addresses and VPL metadata.
- A separate desktop RM-217 boot-probe project. It is diagnostic tooling, not an Android emulator.
- A successful Android debug build for commit `588b77363c1fa2eff18c0c686b57c7b425946456`; CI artifact `retro-mobile-world-debug` (artifact ID `13527345538`, workflow run [#42264879265](https://github.com/irman100/RetroMobileWorld/actions/runs/42264879265)). APK SHA-256: `49ae1c5d9a5cd1815cc34dedf048b9de3aa707c2d4cfd7429256b39c819a0fec`.
- The debug package is `org.retromobile.world`, version `0.3.0-rm217-firmware` (versionCode 3), minSdk 26 and targetSdk 36.
- Versioned catalogue source registry, registry validators and Android CI workflow.

## RM-217 07.30 archive verification

Selected VPL: RM217_0537625_07.30_018.vpl, product code 0537625, software version 07.30, variant RM-217 6300 EURO-C TR TURKEY, Latin, blksilver.

| Component | VPL CRC-32 | Actual CRC-32 | Result |
|---|---:|---:|---|
| rm217__07.30.mcusw | 01C45DB7 | 01C45DB7 | Match |
| rm217__07.30.ppm_c | C585B71C | 613D565F | **Mismatch** |
| rm217__07.30.image_c_tr | 5008F91F | 5008F91F | Match |

The Android importer intentionally retains the structurally valid three files and displays the PPM mismatch. This is useful for continuing the investigation, but the package must not be called a fully verified or bootable set.

## Not implemented

- RM-217 CPU/SoC, full memory map, display controller, keypad scanner, interrupts, PMIC, storage and audio hardware.
- A successful boot of the original RM-217 firmware to a stable LCD frame.
- Rendering the firmware's real Series 40 UI in the retained phone display aperture.
- Forwarding the phone's physical keypad events to an emulated RM-217 machine.
- Physical handset flashing or modification.
- Embedded Java ME or Symbian application runtime in the RM-217 machine.

The reference project c3emu targets Nokia C3-00 / RM-614. Its CPU interpreter and diagnostics may inform research, but its RM-614 hardware model is not RM-217 compatible.

## Current install and boot boundary

The current debug APK is ready for a first physical-device intake test. The user can install it, choose the real Phoenix ZIP, and verify local persistence and the VPL CRC report. This checks the app's import path only; it does not attempt to boot the phone OS.

The desktop probe has newer instrumentation for the 14-entry static BSE map and MMU-mediated mapper stack reads. The latest interpretation is alias-indexed: BSE occupancy is keyed by 1-MiB virtual alias slots, so equal physical bases do not by themselves prove a collision. The next diagnostic task is to establish the exact alias passed by the late NOR allocator before changing any mapping.

The known RM-217 07.30 archive has a PPM/VPL CRC mismatch (expected `C585B71C`, actual `613D565F` in the previously inspected archive); MCU and IMAGE CRCs match. The importer retains structurally valid components but labels the set unverified.

## Next engineering steps

1. Install the current debug APK on the target phone and validate the actual Phoenix ZIP import, persistent storage and warning display.
2. Resolve the PPM/VPL CRC discrepancy or obtain the matching PPM component.
3. Complete the alias-indexed BSE trace: record mapper call-site, `r0/r1/r2`, CP15 state, guest virtual stack reads and occupied 1-MiB slot.
4. Continue the desktop RM-217 probe past the BSE boundary and model the missing hardware only from confirmed firmware/manual evidence.
5. Achieve a stable real LCD frame in the desktop probe before implementing and connecting the RM-217 `PhoneSystemEngine` on Android.
