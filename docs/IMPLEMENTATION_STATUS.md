# Implementation status

Snapshot: 5 November 2026. The Android app is being refocused on loading and inspecting the real Nokia 6300 / RM-217 firmware. No successful RM-217 OS boot has been achieved.

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

## Next engineering steps

1. Build the Android app and exercise the real RM-217 Phoenix ZIP importer on the target phone.
2. Resolve the PPM/VPL CRC discrepancy or obtain a matching PPM file.
3. Continue the desktop RM-217 boot probe and reconstruct the hardware state needed by the original firmware.
4. Achieve a stable LCD frame in the desktop probe before porting the validated machine to Android.
5. Connect the emulated LCD frame and physical keypad to the retained phone shell.
