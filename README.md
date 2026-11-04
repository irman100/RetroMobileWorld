# Retro Mobile World

**A modular retro-phone shell for Java ME and Symbian software.**

Retro Mobile World aims to recreate the experience of using a classic mobile phone: the device body, display geometry, physical keypad, soft keys, launcher, WAP access and application installation flow. The application is an Android-first host with replaceable emulation backends.

> **Current status: bootstrap / shell prototype.** The first commit contains the product specification, architecture decisions, sample device profiles, a basic interactive phone-shell prototype and a catalogue registry format. It does **not** yet execute JAR/JAD or SIS/SISX files. Engine integration is gated by compatibility, licensing and packaging tests.

## The central design

- **One shell, multiple device profiles.** A profile describes the phone's display dimensions, screen placement, outer-body artwork, keypad geometry, key mapping, sound set and visual style.
- **Device profile is not runtime.** One profile defines the device frame, display, keypad, keymap and visual treatment. It can advertise more than one runtime: the N73-like S60 profile supports both Java ME and native Symbian. Java ME and Symbian are actual execution environments, not interchangeable themes.
- **Engines are replaceable modules.** The shell talks to an `EmulatorBackend` contract; individual engines remain independently versioned and licensed.
- **Catalogues are data sources, not hard-coded stores.** The first catalogue is a curated registry of historical/live/archive sources. Adapters are added only after each source has been checked.
- **Preserve the original flow.** Browse a WAP/catalogue page, choose a package, download, review metadata, install and launch it from the virtual phone.

## Initial target

Android is the first host platform. Windows is a planned second host after the runtime adapters and device-profile format are proven. The shell prototype is deliberately independent of any one phone model.

## Repository map

```text
app/                 Android-first phone-shell prototype
catalog/             Source registry and JSON schema
profiles/            Example device profiles and profile schema
docs/                Product, architecture, legal, engine, status and roadmap specifications
gradle/              Gradle wrapper configuration
BOOTSTRAP-WINDOWS.ps1  Creates the GitHub repository and pushes this starter project
```

## Build

The project uses Android Gradle Plugin 9.5.0 and Gradle 9.6.0 (JDK 17+). Device profiles are loaded from `profiles/device-profiles.json` at startup; their screen apertures, keypad hit areas, navigation style and runtime targets are data-driven. `scripts/validate_registry.py` (CI/Python) and `scripts/validate_registry.ps1` (local Windows PowerShell, no Python required) check profile geometry, key coverage, engine requirements and catalogue status metadata. The exact completed and pending work is listed in [implementation status](docs/IMPLEMENTATION_STATUS.md). This starter archive includes the Gradle wrapper properties but not the generated wrapper executable/JAR. The actual Android build has **not** been executed here because an Android SDK/Gradle installation is not available; do not treat the scaffold as CI-verified.

Run the registry check from the project root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate_registry.ps1
```

With Gradle 9.6.0 installed, generate the wrapper scripts once from the project root:

```powershell
gradle wrapper --gradle-version 9.6.0 --distribution-type bin
```

Then build with:

```powershell
.\gradlew.bat :app:assembleDebug
```

## GitHub bootstrap

The connected GitHub workspace can inspect and update repositories, but it does not expose a repository-creation operation. The included PowerShell script creates a **private** `irman100/RetroMobileWorld` repository using GitHub CLI (`gh`) and pushes this project. Keeping the unfinished starter private is the safer default; visibility can be changed after review. Run it from the extracted project directory after installing Git and GitHub CLI and completing `gh auth login`.

```powershell
.\BOOTSTRAP-WINDOWS.ps1
```

The script stops rather than overwriting an existing target directory or an existing GitHub repository.

## Important boundaries

- No proprietary phone firmware, Symbian ROM, copyrighted games or paid catalogue content is bundled.
- Symbian ROM/firmware must be supplied by the user from a lawful source and accepted by the selected backend.
- A catalogue entry is not an assertion that a source is online, safe, authorised to redistribute files or compatible with every device.
- Downloaded legacy software is untrusted. Package inspection, user confirmation, per-app storage isolation and network permission controls are required before public release.

See [the product specification](docs/PRODUCT_SPEC.md), [architecture](docs/ARCHITECTURE.md), [engine research](docs/ENGINE_RESEARCH.md), [catalogue strategy](docs/CATALOG_AND_WEBSITE.md), [legal and safety requirements](docs/LEGAL_AND_SAFETY.md) and [roadmap](docs/ROADMAP.md).

## Licence

The original project code is licensed under GPL-3.0-only. Third-party engines and dependencies retain their own licences; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [engine research](docs/ENGINE_RESEARCH.md).
