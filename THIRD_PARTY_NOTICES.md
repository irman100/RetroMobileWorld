# Third-party notices

Retro Mobile World uses separately licensed components. Preserve their notices and licences when distributing builds.

## c3emu — Nokia C3-00 / RM-614 emulator

- Project: https://github.com/androettop/c3emu
- Upstream revision: `9b0d3ae2da326e109428f64d8760ea823ee1b648`
- Licence: GNU General Public License v3.0 or later (GPL-3.0-or-later).
- The Android native library links the upstream `c3emu-web` crate and uses its C ABI for firmware handoff, execution slices, LCD frames, audio, key events and persistent flash chunks.
- Its RM-614 machine implementation is used only by the Nokia C3-00 profile. Do not present its RAPIDO/BCM2153 device model as an RM-217 / Nokia 6300 implementation.
- The upstream crate is vendored under `engine/c3emu-core` from the exact revision above; the local fork is used by the Android wrapper through a Cargo path dependency.
- Local modifications add only the firmware-observed Nokia SPI-host MMIO subset and a target interface; no Broadcom gSPI target or network packet backend is claimed.
- Preserve the original upstream source identity, copyright notices and GPL-3.0-or-later licence. The vendored crate's Cargo manifest records that licence.
- Upstream licence and source: https://github.com/androettop/c3emu/blob/9b0d3ae2da326e109428f64d8760ea823ee1b648/LICENSE

The application's GPL-3.0 project licence is compatible with this GPL-3.0-or-later dependency. Distributed builds must identify the upstream revision, the local modifications, and provide the corresponding source under the applicable GPL terms.

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
