# Retro Mobile World

**An Android firmware-intake shell and future emulator for the Nokia 6300 / RM-217.**

The current app opens directly into an RM-217 firmware screen. The phone display aperture, navigation assembly and physical keypad remain; the temporary hand-drawn standby screen, launcher grid and placeholder category menus are no longer the app's main interface.

> **Current status:** the app can import a user-provided Nokia Phoenix ZIP, select the version-matched MCU, PPM and canonical IMAGE components, inspect their BB5 FPSX structure, and compare their CRC-32 values with the matching VPL manifest. Imported files stay in app-private storage. The supplied 07.30 package has a known PPM/VPL CRC mismatch, which is shown as a warning rather than hidden or silently treated as verified. The app does not yet boot the original firmware.

## Core design

- **The original firmware must own the operating-system interface.** The Android shell provides the device frame, display surface and physical controls; it must not grow a parallel hand-drawn Series 40 menu system.
- **A device profile is not an operating system.** RM-217 is the first hardware target. Other feature-phone profiles remain future work.
- **Firmware is user supplied.** The app reads the Phoenix archive locally, stores only the selected RM-217 components and never runs installer executables or flashes a physical handset.
- **Integrity is explicit.** Structural FPSX checks and VPL CRC checks are separate. A structurally readable component is not automatically a verified firmware set.
- **Replaceable execution backends.** The PhoneSystemEngine contract separates the full phone OS machine from Java ME application runtimes.

## RM-217 firmware intake

Use the on-screen ЗАГРУЗИТЬ ПРОШИВКУ control or the phone's left soft key. Select the Phoenix ZIP archive containing the RM-217 software set. The importer:

1. Finds the version-matched .mcusw, .ppm_c and canonical .image_c_tr components.
2. Reads the matching VPL manifest and checks its component CRC-32 values.
3. Validates each selected BB5 FPSX container.
4. Stores the three components in app-private storage using SHA-256-addressed file names.
5. Shows checksum mismatches in the phone display and an information dialog.

The archive's other files, including Windows installer executables and alternate colour IMAGE files, are not extracted or executed. Firmware is not copied into the Git repository or APK.

## Known 07.30 archive result

The supplied package identifies product code 0537625, software version 07.30, variant RM-217 6300 EURO-C TR TURKEY, Latin, blksilver.

- MCU CRC-32: expected 01C45DB7, actual 01C45DB7 — match.
- PPM CRC-32: expected C585B71C, actual 613D565F — mismatch.
- IMAGE CRC-32: expected 5008F91F, actual 5008F91F — match.

The app imports this structurally valid set but labels it unverified. Do not treat the current archive as a confirmed boot set until the PPM discrepancy is resolved.

## Not implemented

- RM-217 CPU/SoC, memory map and hardware device model.
- Booting the original RM-217 firmware to a stable LCD frame.
- Rendering the actual Series 40 screen inside the phone aperture.
- Routing physical key press/release events into the emulated keypad.
- Flashing or modifying a physical Nokia handset.

The desktop boot probe under engine/rm217-boot-probe is diagnostic research, not an Android firmware emulator. It already reuses c3emu's ARM926 interpreter, MMU translation helpers and FPSX parsing. It deliberately does not instantiate c3emu's RM-614 / Nokia C3-00 machine: that board's memory map, LCD, keypad, PMU and peripheral models differ from RM-217. The future RM-217 engine must keep the shared CPU core and provide an RM-217-specific SoC/device layer. See docs/experiments/RM217_EMULATOR_REUSE_RESEARCH.md for the emulator survey and hardware-backed implementation plan.

## Android build

The project uses Android Gradle Plugin 9.5.0, Gradle 9.6.0, JDK 17+ and Android SDK platform 36. From Windows PowerShell at the repository root, run the Gradle wrapper with the testDebugUnitTest and assembleDebug tasks. The debug APK is written to app/build/outputs/apk/debug/app-debug.apk.

## Repository layout

- app: Android RM-217 shell and firmware intake
- catalog: source registry and JSON schema
- profiles: device profiles, keymaps and profile schema
- docs: product, architecture, legal, engine research and roadmap
- engine: desktop RM-217 boot-probe experiment
- scripts: registry and firmware inspection tools
- .github/workflows: Android CI and desktop boot-probe CI

The application code is licensed under GPL-3.0-only; see LICENSE and THIRD_PARTY_NOTICES.md. User-supplied firmware retains its own rights and is not bundled.
