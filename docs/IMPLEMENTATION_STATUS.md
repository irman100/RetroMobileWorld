# Implementation status

Snapshot: 8 November 2026.

## Implemented

- Android application entry point and the Nokia 6300 / RM-217 firmware-intake shell.
- Local-only RM-217 Phoenix ZIP intake with MCU, PPM and IMAGE component selection.
- FPSX structural checks, matching VPL manifest lookup and CRC-32 reporting.
- Separate PhoneSystemEngine contract for CPU/SoC/firmware emulation, video frames, PCM audio and key down/up events.
- Desktop RM-217 boot-probe tooling and trace summarizer tests.
- Experimental Nokia C3-00 / RM-614 engine integration:
  - native Rust crate links the pinned upstream c3emu-web C ABI;
  - JNI methods are serialized on one native worker thread because upstream c3emu keeps the emulator session in thread-local state;
  - Android engine implements bounded execution slices, 320×240 RGBA LCD frame transfer, stereo 44.1-kHz PCM drain and C3-00 keyboard matrix input;
  - flash-backed C: storage chunks are persisted in app-private storage;
  - separate C3-00 activity supports Phoenix ZIP import or individual RM-614 MCUSW / PPM / IMAGE files;
  - selected front-facing C3-00 profile asset is packaged from the pinned repository blob with a build-time integrity check;
  - FPSX preflight no longer rejects a u32 address just because address + payload length crosses the unsigned 32-bit boundary; actual file-boundary checks remain.
- Android CI run 42457493795 passed the C3-00 native build for ARM64, ARMv7 and x86_64, Android unit tests, APK assembly and artifact upload.
- Windows SDK discovery now supports ANDROID_SDK_ROOT, ANDROID_HOME, local.properties and D:\Android\Sdk.
- A safe local build/install PowerShell entry point was added for physical Android phones.
- Android app version is advanced to 0.4.1-c3emu-rm614 (versionCode 5) for the next local validation build.
- Full CI for checkpoint 1c63d991738e2b209000271ea86dfb170894f508 passed in push run 42458429582 and PR run 42458434131; the version 0.4.1 debug APK artifact is 13608921375.
- Firmware remains user-supplied and is not included in source control or the APK.

## C3-00 validation boundary

The user has confirmed that the selected C3-00 shell image is visible in the installed GitHub build. This confirms the shell asset is packaged and rendered; it does not prove that RM-614 Series 40 boots.

The FPSX error was traced to an over-restrictive Kotlin preflight check, not to a demonstrated failure in the native c3emu parser. The upstream parser reads the flash target as u32 and checks payload availability within the container; it does not reject solely because start address plus length crosses 0xFFFFFFFF. A dedicated regression test covers this boundary and passed CI.

The corrected APK still needs to be built from the latest local branch and installed on the physical phone. The user's ADB installation path is D:\Android\Sdk\platform-tools\adb.exe (ADB 1.0.41, platform-tools 37.0.0-14910828). The USB device serial/model has not yet been captured in this project log.

Required next device validation:
1. Enable Developer options and USB debugging on the phone, unlock it, and authorize this PC's RSA key.
2. Run scripts/build-install-c3emu-phone.ps1 from the repository root. It updates the C3-00 branch safely, builds and tests locally, then installs the local APK using an explicit physical-device serial. It excludes emulator serials.
3. Open Nokia C3-00 · Open emulator.
4. Import the same user-supplied RM-614 Phoenix ZIP and confirm the 32-bit FPSX error is gone.
5. Start Series 40 and verify a real guest LCD frame/home screen and key response.
6. Then test audio and persistent C: storage separately.

A successful import is not a successful boot. A successful boot must be demonstrated by a real guest OS frame and input response.

The Android backend uses c3emu's own RM-614 hardware model. It must not be selected for Nokia 6300 / RM-217 firmware.

## C3-00 UI follow-up

- User-selected image docs/75-205-263-02.png is the shell reference; do not substitute a marketplace photo or an unrelated generated image.
- Verify the LCD overlay against the image's real display opening on the physical phone.
- Verify touch hit regions against the physical keypad and confirm press/release behaviour.
- Do not mark LCD geometry or all physical-key coordinates calibrated until a screenshot and touch test confirm alignment.

## RM-217 verification

The inspected Nokia 6300 / RM-217 07.30 archive previously showed:
- MCU CRC matches the selected VPL;
- IMAGE CRC matches the selected VPL;
- PPM CRC mismatch: expected C585B71C, actual 613D565F.

The app retains structurally valid files but must not label this set fully verified or bootable.

The RM-217 desktop probe still does not boot the Nokia 6300 operating system. Its current investigation concerns the late BSE alias collision, NOR allocator and reset path. No C3-00 hardware map or firmware assumptions may be copied into RM-217.

## Known scope limits

- c3emu documents no SIM or cellular network emulation.
- Physical handset flashing is not implemented; the Android application emulates the guest environment.
- Android frame and audio delivery need measurement on the test phone.
- C3-00 QWERTY support is via an on-screen key matrix; device-specific key mapping still needs end-to-end verification.
- No successful RM-217 OS boot has been achieved.
