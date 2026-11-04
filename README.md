# Retro Mobile World

**A modular retro-phone shell for Java ME and Symbian software.**

Retro Mobile World recreates the experience of a classic mobile phone: device body, display geometry, physical keypad, soft keys, launcher, WAP access and application installation flow. Android is the first host platform; emulation backends are designed as replaceable modules.

> **Current status: shell prototype / initial scaffold.** This repository contains the Android phone-shell prototype, data-driven device profiles, catalogue registry, architecture and product specifications. It does **not** yet execute JAR/JAD or SIS/SISX files. Runtime integration is a separate milestone gated by compatibility, licensing and packaging tests.

## Core design

- **One shell, multiple device profiles.** A profile describes display dimensions and placement, outer-body artwork, keypad geometry, key mapping, sound set and visual style.
- **A device profile is not an operating system.** One device can expose multiple runtimes. For example, an S60 profile can support Java ME and native Symbian while keeping the same device body and controls.
- **Replaceable execution backends.** The shell talks to a common `EmulatorBackend` contract. Java ME and Symbian remain independent engines with separate licences and packaging decisions.
- **Catalogues are data sources.** The initial registry records historical, live and archived sources with explicit status and rights metadata. A listed source is not assumed to be online or authorised to redistribute its files.
- **Preserve the original installation flow.** The intended experience is to browse a catalogue or WAP page, choose a package, download it, review metadata, install it and launch it from the virtual phone.

## Initial device profiles

- Nokia 6300 / Series 40 style — 240 × 320 display, Java ME target.
- Sony Ericsson K750i style — 176 × 220 display, Java ME target.
- Nokia N73 / S60 style — 240 × 320 display, Java ME and Symbian runtime targets.

The profiles are compatibility-oriented starting points, not a claim that every original firmware feature is implemented.

## Repository layout

```text
app/                  Android-first shell prototype
catalog/              Source registry and JSON schema
profiles/              Device profiles, keymaps and profile schema
docs/                  Product, architecture, legal, engine research and roadmap
gradle/                Gradle wrapper configuration
scripts/               Registry validation scripts
.github/workflows/     Android CI
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

Both validators check the device-profile data, screen geometry, key coverage, runtime targets and catalogue metadata.

## Android build

The project uses Android Gradle Plugin 9.5.0, Gradle 9.6.0, JDK 17+ and Android SDK platform 36. The initial source archive includes Gradle wrapper properties but not the generated wrapper executable/JAR. GitHub Actions installs Gradle and builds the debug APK. Locally, with Gradle 9.6.0 and Android SDK 36 installed:

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

The initial Android debug APK was successfully built on 2026-11-04. Unit tests and `:app:assembleDebug` passed in GitHub Actions; the resulting APK is published as the `retro-mobile-world-debug` workflow artifact. This verifies the shell prototype build, not Java ME or Symbian runtime execution.

## Engine integration strategy

The first integration experiment targets J2ME Loader. Symbian support will be evaluated separately using EKA2L1. Neither emulator is vendored in this repository at this stage. No proprietary firmware, Symbian ROM, copyrighted games or paid catalogue content is bundled.

- J2ME Loader: https://github.com/nikita36078/J2ME-Loader
- EKA2L1: https://github.com/EKA2L1/EKA2L1
- FreeJ2ME: https://github.com/hex007/freej2me

See [engine research](docs/ENGINE_RESEARCH.md), [third-party notices](THIRD_PARTY_NOTICES.md) and [architecture](docs/ARCHITECTURE.md) before adding or distributing an engine.

## Legal and security boundaries

- Symbian system ROM/firmware must be supplied by the user from a lawful source and accepted by the selected backend.
- A catalogue entry does not imply that a website is online, that its files are safe, or that redistribution is authorised.
- Downloaded legacy packages are untrusted. Package inspection, explicit installation confirmation, per-app storage isolation and network permission controls are required before public release.
- Third-party engines and dependencies retain their own licences. The original project code is licensed under GPL-3.0-only; see `LICENSE` and `THIRD_PARTY_NOTICES.md`.

## Roadmap

1. Verify the Android shell build and profile switching.
2. Validate key input, hit areas, display scaling and device skins on a physical Android device.
3. Prototype Java ME backend integration without copying engine-specific UI into the shell.
4. Evaluate Symbian backend integration and lawful firmware provisioning.
5. Implement package library, download/install workflow and catalogue adapters.
6. Publish the companion static catalogue website after source status and rights review.

See [product specification](docs/PRODUCT_SPEC.md), [architecture](docs/ARCHITECTURE.md), [catalogue strategy](docs/CATALOG_AND_WEBSITE.md), [legal requirements](docs/LEGAL_AND_SAFETY.md), [implementation status](docs/IMPLEMENTATION_STATUS.md) and [roadmap](docs/ROADMAP.md).
