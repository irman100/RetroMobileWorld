# Third-party notices

Retro Mobile World uses separately licensed components. Preserve their notices and licences when distributing builds.

## c3emu — Nokia C3-00 / RM-614 emulator

- Project: https://github.com/androettop/c3emu
- Upstream revision: `9b0d3ae2da326e109428f64d8760ea823ee1b648`
- Licence: GNU General Public License v3.0 or later (GPL-3.0-or-later).
- The Android native library links the upstream `c3emu-web` crate and uses its C ABI for firmware handoff, execution slices, LCD frames, audio, key events and persistent flash chunks.
- Its RM-614 machine implementation is used only by the Nokia C3-00 profile. Do not present its RAPIDO/BCM2153 device model as an RM-217 / Nokia 6300 implementation.
- The dependency is fetched by Cargo at build time; the application repository does not include Nokia firmware.
- Upstream licence and source: https://github.com/androettop/c3emu/blob/9b0d3ae2da326e109428f64d8760ea823ee1b648/LICENSE

The application's GPL-3.0 project licence is compatible with this GPL-3.0-or-later dependency. The corresponding upstream source revision and licence must remain identifiable in distributed builds.

## JNI binding

- Crate: `jni` version `0.21.1`
- Licence and source: https://crates.io/crates/jni

## AndroidX Core

- Dependency: `androidx.core:core:1.16.0`
- Licence and notices: https://developer.android.com/jetpack/androidx

## Gradle Wrapper

The Gradle Wrapper launcher scripts and bootstrap JAR are distributed with Gradle 9.6.0 under the Apache License 2.0. The scripts retain their upstream copyright and licence headers. Upstream source: https://github.com/gradle/gradle/tree/v9.6.0.

## RM-217 diagnostic probe

The desktop program under `engine/rm217-boot-probe` separately depends on the same pinned c3emu revision to reuse its ARM926 interpreter, MMU helpers and FPSX parser. It does not instantiate c3emu's RM-614 machine or device map for RM-217 diagnostics.

## Firmware

No Nokia MCUSW, PPM, IMAGE, ROM or other copyrighted firmware image is included in this repository or APK. Users select firmware files they are entitled to use; the application stores selected files in app-private storage on the Android device.
