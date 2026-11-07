# Retro Mobile World

Retro Mobile World is an Android-first shell for classic mobile devices and their original software environments.

## Current emulator targets

### Nokia C3-00 / RM-614 — Series 40

The Android app includes an experimental native backend based on [c3emu](https://github.com/androettop/c3emu), pinned to upstream revision `9b0d3ae2da326e109428f64d8760ea823ee1b648`.

The backend runs the C3-00's own Series 40 firmware using the upstream ARM926EJ-S interpreter and RM-614 device models. It forwards LCD frames, stereo PCM audio and key down/up events to the Android shell. Flash-backed C: storage is saved under the app's private files directory.

The Android launcher retains the Nokia 6300 / RM-217 firmware intake screen and provides a separate **Nokia C3-00 · Open emulator** entry. The C3-00 screen accepts a Phoenix ZIP containing RM-614 components or individual files:
- `*.mcusw` — required operating system;
- `*.ppm_*` — optional language pack;
- `*.image_*` — optional factory content / C: drive.

Firmware is selected by the user and stored only in app-private storage. It is not bundled into the APK or repository. A valid RM-614 MCUSW is required to boot. RM-217 / Nokia 6300 firmware is not compatible.

The C3-00 interface uses a 320×240 display and a virtual QWERTY keypad. This is an initial Android integration; it does not claim full compatibility with SIM, cellular networking or every accessory.

### Nokia 6300 / RM-217

The original Series 40 firmware intake and integrity inspector remain available. The RM-217 desktop boot probe is diagnostic tooling only; it is not yet a complete RM-217 machine and does not boot the Nokia 6300 operating system.

## Build on Windows

Prerequisites:
- JDK 17;
- Android SDK platform 36 and build-tools 36.0.0;
- Android NDK 27.2.12479018;
- stable Rust through rustup.

From the repository root, build the C3-00 native libraries and then the Android APK:

```powershell
.\scripts\build-c3emu-android.ps1
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon :app:assembleDebug
```

The native script installs the Android Rust targets and `cargo-ndk` when needed, then builds `arm64-v8a`, `armeabi-v7a` and `x86_64` libraries into the ignored `app/src/main/jniLibs` directory.

Android CI performs the same native cross-build before Kotlin tests and APK packaging. See `.github/workflows/android-ci.yml`.

Third-party licensing details are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). No firmware is included.
