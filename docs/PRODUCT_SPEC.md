# Product specification

**Product:** Retro Mobile World  
**Repository:** `irman100/RetroMobileWorld`  
**Initial host:** Android  
**Status:** Draft v0.1 — bootstrap specification, 4 November 2026

## 1. Product vision

Build one application shell that can be configured to reproduce the experience of several popular feature phones from the Java ME / Symbian era. Nokia 6300 / RM-217 is the first fidelity target. The user selects a device profile; the application changes the device frame, native display resolution and aspect ratio, keypad layout, soft-key positions, key mapping, sounds and visual treatment. The host may fill the modern Android viewport, but the legacy LCD itself must keep the selected model's native geometry.

The shell must not implement every historic phone as an independent emulator. It uses a small number of runtime backends and a larger set of data-driven device profiles.

## 2. Product principles

1. **Authentic interaction over decorative nostalgia.** Hardware keys, soft keys, menu navigation, backlight, native display aspect ratio and installation flow must behave consistently. The display grows vertically to use the available host area, but its model-specific pixel ratio is never stretched; symmetrical side margins are acceptable.
2. **Profiles are data.** New phone models should normally be added by creating a profile and artwork, not by writing a new application branch.
3. **Device profile and runtime are separate.** A profile describes hardware and appearance, then advertises one or more runtimes. An S60 device such as the N73 can run Java ME MIDlets and native Symbian applications. Selecting a runtime must not change the selected device shell.
4. **Keep source identity.** Every catalogue entry records the original source, canonical package URL where permitted, source status, checksums and attribution.
5. **Do not promise perfect compatibility.** Historic devices differed in MIDP versions, optional JSR APIs, graphics, audio, key codes, screen dimensions and vendor extensions.
6. **No unauthorised ROM or game distribution.** The app supplies the shell and integration; users supply system images and packages unless redistribution rights are confirmed.

## 3. First release scope (MVP)

### Included

- Android host app.
- A generic phone-shell renderer.
- Three initial sample profiles:
  - Nokia 6300-like S40 profile — 240×320, Java ME.
  - Sony Ericsson K750i-like profile — 176×220, Java ME.
  - Nokia N73-like S60 3rd Edition profile — 240×320, supports both Java ME and native Symbian packages. The default runtime is Symbian, and the user can switch to Java ME.
- Profile selection without restarting the host app.
- Virtual keypad with D-pad, center/select, left/right soft keys, call/end, back and 0–9, `*`, `#`.
- Key mapping independent of screen artwork.
- Local library metadata model for JAR/JAD and SIS/SISX packages.
- A small source registry with explicit `verified_live`, `verified_project`, `archived`, `historical`, `unavailable` and `not_checked` status values.
- File import through Android's Storage Access Framework.
- A Java ME runtime integrated into the virtual phone display. J2ME Loader is the initial technical reference and smoke-test engine, not the intended final companion-app experience. Opera Mini is a priority real-world MIDlet compatibility target.
- A separate Symbian integration proof-of-concept based on EKA2L1, with a user-provided firmware/ROM setup step.

### Excluded from MVP

- Perfect reproduction of every manufacturer UI.
- Every Nokia/Sony Ericsson/Motorola/Samsung device profile.
- A public upload service for arbitrary JAR/SIS files.
- A central server that mirrors old game catalogues.
- Bundled commercial games, proprietary phone firmware or Symbian ROMs.
- iOS, Windows and Linux host applications (planned after Android validation).
- A cloud account, user profiles, social functions or multiplayer services.

## 4. User journeys

### A. Select a phone

1. Open Retro Mobile World.
2. Choose a model/profile from the device cabinet.
3. The shell applies the profile's body artwork, display aperture, logical screen resolution, keypad geometry, key mapping and sound profile.
4. The home screen appears inside the selected device frame.

### B. Install a Java ME app

1. Open Applications > Java applications or the Web section, then import a JAR/JAD package. Opera Mini is treated as a Java ME MIDlet, not as a native Android browser screen.
2. If a JAD is supplied, parse the descriptor and resolve `MIDlet-Jar-URL` relative to the descriptor URL when appropriate.
3. Fetch the JAR only after validating the URL and showing the source to the user.
4. Compare the actual package size with `MIDlet-Jar-Size` when present; inspect the archive and manifest.
5. Show name, vendor, version, requested permissions and target profile compatibility.
6. Ask for explicit confirmation before install.
7. Install into the Java ME backend and add a local launcher entry.
8. Launch the MIDlet inside the virtual phone's display using the selected device's logical resolution and key mapping. For Opera Mini, verify that the required legacy network/proxy service and TLS behaviour remain usable; package import alone does not prove that the browser's remote service works.

### C. Install a Symbian app

1. Select a Symbian-capable device profile.
2. Configure a compatible system image in the EKA2L1 backend using files supplied by the user.
3. Import a SIS/SISX package from local storage.
4. Let the Symbian backend perform native package installation.
5. Launch the app from the emulated Symbian environment. The Symbian system UI must come from the backend, not from a fake Java shell theme.

### D. Browse a preserved WAP catalogue

1. Open the virtual browser.
2. Select a source from the catalogue registry.
3. Load the live page or a clearly labelled archived copy.
4. If a supported JAD/JAR link is selected, hand it to the package-install flow.
5. If the source is unavailable, show a status message and optionally open a known archive snapshot. Never silently pretend that a mirror is the original live service.

## 5. Device profile requirements

Every device profile must define:

- stable profile ID and display name;
- manufacturer/model compatibility label;
- operating-system family (for example, Nokia S40, Sony Ericsson proprietary feature-phone UI, or Symbian S60);
- one or more runtime targets (`JAVA_ME`, `SYMBIAN`) with backend IDs, compatibility hints and system-image requirements;
- a default runtime when a device supports more than one;
- logical display width/height and optional colour-depth metadata;
- display aperture rectangle in shell coordinates;
- shell skin asset and optional alternate colourways;
- keypad geometry and hit areas;
- mapping from physical controls to canonical keys and backend key codes;
- soft-key labels/positions;
- audio/speaker style and optional sound pack;
- supported orientation and scaling policy;
- backend requirements (MIDP/CLDC hints or Symbian family/firmware requirement);
- profile provenance and asset licence.

Device names are compatibility labels, not a claim of manufacturer affiliation. Model artwork should be original or explicitly licensed; logos and copied promotional photos are not required.

## 6. Compatibility model

Compatibility is evaluated from several independent dimensions:

- MIDP/CLDC version;
- optional JSR APIs (for example, media, Bluetooth, 3D or file access);
- vendor extensions;
- screen dimensions and colour depth;
- keypad/soft-key layout;
- sound and graphics behaviour;
- engine-specific bugs and workarounds.

The catalogue should store observed test results by engine version and profile. A single global `works=true` flag is insufficient.

## 7. Non-functional requirements

- Offline-first library and profile selection.
- No automatic execution on download.
- Safe handling of untrusted archive contents and URLs.
- Download size/time limits and cancellation.
- Per-title isolated RMS/application storage.
- User-controlled network access for emulated apps; disabled by default for unknown titles.
- Clear attribution and licence notices for each bundled engine/dependency.
- Reproducible engine versions pinned to commit/tag and checksums.
- Telemetry disabled by default; no package contents uploaded.
- Accessible host controls even when the emulated phone has small text.

## 8. Acceptance criteria for MVP

- Switching the three sample profiles updates the display ratio, shell style, keypad layout and key hit areas without app restart.
- The Nokia N73 profile exposes both Java ME and Symbian runtime targets; switching runtime does not change its device shell.
- All visible keys emit the expected canonical input event in a deterministic test.
- The phone display uses the profile's declared logical resolution and scales without stretching.
- Importing a malformed JAD/JAR/SIS/SISX fails safely and does not crash the host app.
- A valid test MIDlet installs and launches in the Java ME proof-of-concept with working D-pad and soft keys.
- A supported Symbian test title launches in EKA2L1 on at least one supported target configuration using user-supplied system files.
- No proprietary firmware or game package is included in release artefacts.
- Every catalogue source shows a status and source link; no unverified source is represented as currently working.
