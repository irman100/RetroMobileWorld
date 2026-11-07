# Implementation status

Snapshot: 7 November 2026.

## Implemented

- Android application entry point and the Nokia 6300 / RM-217 firmware-intake shell.
- Local-only RM-217 Phoenix ZIP intake with MCU, PPM and IMAGE component selection.
- FPSX structural checks, matching VPL manifest lookup and CRC-32 reporting.
- Separate `PhoneSystemEngine` contract for CPU/SoC/firmware emulation, video frames, PCM audio and key down/up events.
- Desktop RM-217 boot-probe tooling and trace summarizer tests.
- Experimental Nokia C3-00 / RM-614 engine integration:
  - native Rust crate links the pinned upstream `c3emu-web` C ABI;
  - JNI methods are serialized on one native worker thread because upstream c3emu keeps the emulator session in thread-local state;
  - Android engine implements bounded execution slices, 320×240 RGBA LCD frame transfer, stereo 44.1-kHz PCM drain and C3-00 keyboard matrix input;
  - flash-backed C: storage chunks are persisted in app-private storage;
  - a separate C3-00 activity supports Phoenix ZIP import or individual RM-614 MCUSW / PPM / IMAGE files.
- Android CI cross-builds native libraries for ARM64, ARMv7 and x86_64 before assembling the debug APK.
- Firmware remains user-supplied and is not included in source control or the APK.

## C3-00 validation boundary

The Android integration is newly added and still requires CI compilation and physical-device testing. The C3-00 app screen is not evidence of a successful guest boot. A successful boot must be demonstrated by the guest OS producing a real LCD frame and responding to key input.

The Android backend uses c3emu's own RM-614 hardware model. It must not be selected for Nokia 6300 / RM-217 firmware.

Required first device validation:
1. Install the C3-00-enabled APK.
2. Open **Nokia C3-00 · Open emulator** from the RM-217 screen.
3. Import the user's own RM-614 MCUSW (PPM and IMAGE remain optional).
4. Start Series 40 and verify a real home screen, navigation, sound and storage persistence.
5. Record any first-boot errors and emulator diagnostics without changing the RM-217 implementation.

## RM-217 verification

The inspected Nokia 6300 / RM-217 07.30 archive previously showed:
- MCU CRC matches the selected VPL;
- IMAGE CRC matches the selected VPL;
- PPM CRC mismatch: expected `C585B71C`, actual `613D565F`.

The app retains structurally valid files but must not label this set fully verified or bootable.

The RM-217 desktop probe still does not boot the Nokia 6300 operating system. Its current investigation concerns the late BSE alias collision, NOR allocator and reset path. No C3-00 hardware map or firmware assumptions may be copied into RM-217.

## Known scope limits

- c3emu documents no SIM or cellular network emulation.
- Physical handset flashing is not implemented.
- Android frame and audio delivery need measurement on the test phone.
- C3-00 QWERTY support is via an on-screen key matrix; device-specific key mapping still needs end-to-end verification.
- No successful RM-217 OS boot has been achieved.
