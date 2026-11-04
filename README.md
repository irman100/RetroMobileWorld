# Retro Mobile World

**A modular retro-phone shell and legacy mobile application host for Java ME and Symbian software.**

Retro Mobile World recreates the experience of a classic mobile phone: device body, display geometry, physical keypad, soft keys, launcher and application library. Android is the first host platform; emulation backends are designed as replaceable modules.

> **Current status: Android shell + Java package-library prototype.** The app can import and inspect local Java ME JAR/JAD packages, keep them in private storage and hand them to the J2ME Loader Android runtime when that separate app is installed. The Java ME engine is not yet embedded in this APK, and Symbian/SIS/SISX execution is not implemented.

## Core design

- **One shell, multiple device profiles.** A profile describes display dimensions and placement, outer-body artwork, keypad geometry, key mapping, sound set and visual style.
- **A device profile is not an operating system.** One device can expose multiple runtimes. For example, an S60 profile can support Java ME and native Symbian while keeping the same device body and controls.
- **Replaceable execution backends.** The shell talks to a common `EmulatorBackend` contract. Java ME and Symbian remain independent engines with separate licences and packaging decisions.
- **A local application library.** Imported JAR/JAD files are inspected, identified by SHA-256 and copied to app-private storage. Packages are never executed automatically on import.
- **Catalogues are data sources.** A listed source is not assumed to be online, safe or authorised to redistribute its files.
- **Preserve the original installation flow.** The intended experience is to browse a catalogue or WAP page, choose a package, download it, review metadata, install it and launch it from the virtual phone.

## Initial device profiles

- Nokia 6300 / Series 40 style — 240 × 320 display, Java ME target.
- Sony Ericsson K750i style — 176 × 220 display, Java ME target.
- Nokia N73 / S60 style — 240 × 320 display, Java ME and Symbian runtime targets.

The profiles are compatibility-oriented starting points, not a claim that every original firmware feature is implemented.

## Java ME runtime status

The first execution bridge targets [J2ME Loader](https://github.com/nikita36078/J2ME-Loader), an Apache-2.0 Android emulator. Retro Mobile World shares the selected package through a temporary, read-only Android content URI. J2ME Loader handles its own installation and MIDlet execution.

This is an interim companion-runtime integration, not yet the final embedded-phone experience. The shell's rendering surface and canonical keypad are not yet connected directly to the MIDlet runtime. JAD descriptors that reference a remote HTTP(S) JAR can be handed to J2ME Loader; a JAD with a relative/local JAR reference must be imported together with its JAR by importing the JAR itself.

## Repository layout

```text
app/                  Android phone shell and Java package library
catalog/              Source registry and JSON schema
profiles/              Device profiles, keymaps and profile schema
docs/                  Product, architecture, legal, engine research and roadmap
gradle/                Gradle wrapper configuration
scripts/               Registry validation scripts
```

## Validation

On Windows PowerShell (no Python required):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate_registry.ps1
```

In CI / Python environments:

```bash
python scripts/validate_registry.py
```

Both validators check the device-profile data, screen geometry, key coverage, runtime targets and catalogue metadata. Android CI runs unit tests and assembles the debug APK.

## Android build

The project uses Android Gradle Plugin 9.5.0, Gradle 9.6.0, JDK 17+ and Android SDK platform 36. The Gradle Wrapper scripts and bootstrap JAR are included, so a separate system-wide Gradle installation is not required. On first launch, the wrapper downloads the pinned Gradle 9.6.0 distribution. Install JDK 17+ and Android SDK platform 36, then run from the repository root in Windows PowerShell:

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon :app:assembleDebug
```

GitHub Actions uses the same checked-in wrapper for both tests and APK assembly.

## Symbian runtime

The separate Symbian candidate is [EKA2L1](https://github.com/EKA2L1/EKA2L1). It emulates Symbian OS kernel behaviour and reimplements critical app servers. Android support is experimental and requires compatible user-supplied ROM/RPKG or firmware files for the selected device generation. It is not bundled in this APK.

See [engine research](docs/ENGINE_RESEARCH.md), [third-party notices](THIRD_PARTY_NOTICES.md), [architecture](docs/ARCHITECTURE.md) and [ADR-0003](docs/decisions/ADR-0003-j2me-loader-first-runtime.md).

## Legal and security boundaries

- Symbian system ROM/firmware must be supplied by the user from a lawful source and accepted by the selected backend.
- A catalogue entry does not imply that a website is online, that its files are safe, or that redistribution is authorised.
- Imported packages are untrusted. The app validates their container metadata, limits their size and does not auto-run them. Runtime permissions and network access remain controlled by the engine.
- Third-party engines and dependencies retain their own licences. The original project code is licensed under GPL-3.0-only; see `LICENSE` and `THIRD_PARTY_NOTICES.md`.
