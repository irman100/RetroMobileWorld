# Implementation status

Snapshot: 4 November 2026. This describes the current development branch; physical-device engine validation is still pending.

## Implemented

- Android application entry point and interactive phone-shell prototype.
- Three device profiles: Nokia 6300/S40 style, Sony Ericsson K750i style and Nokia N73/S60 style.
- Versioned JSON device-profile registry with logical display dimensions, display aperture and 22 canonical keypad controls.
- All three device profiles start in Java ME. The N73 profile retains its Symbian backend declaration for a later milestone; Symbian is not selectable in the current interface.
- Local Java ME package library:
  - imports `.jar` and `.jad` through Android's document picker;
  - copies packages into app-private storage and identifies them by SHA-256;
  - inspects JAR manifests and JAD descriptors without extracting or executing the package;
  - rejects malformed descriptors, unsupported file types and oversized packages;
  - presents imported MIDlets inside the virtual phone's Java applications screen.
- Read-only FileProvider handoff to the separate J2ME Loader Android app when the user selects RUN.
- Local-only RM-217 firmware intake:
  - accepts extracted BB5 FPSX `.mcusw`, `.ppm_*` and `.image_*` components;
  - validates FPSX record and data-block boundaries and checks that MCU files contain the named MCUSW region;
  - stores components under app-private storage using SHA-256-addressed names;
  - never uploads firmware, adds it to APK assets or executes it during import.
- A separate PhoneSystemEngine contract for CPU/SoC/firmware emulation, video frames, PCM audio and key-down/key-up events. No RM-217 engine implementation is registered yet.
- Unit tests for JAR/JAD parsing and the BB5 FPSX firmware inspector.
- Versioned catalogue source registry, registry validators and Android CI workflow.
- Product, architecture, engine, catalogue, legal, roadmap and decision documents.

## Implemented but not yet device-verified

- External J2ME Loader handoff. It requires the separate `ru.playsoftware.j2meloader` package to be installed. The integration uses its documented Android `ACTION_VIEW` file-handling entry point, but this branch still needs an end-to-end test on the target phone.
- JAD handoff for descriptors with absolute HTTPS `MIDlet-Jar-URL`. Relative/local JAD references are intentionally not launched by the current prototype.

## Not implemented

- J2ME Loader is not embedded in the Retro Mobile World APK. The game display, canonical keypad events, MIDlet lifecycle and RMS storage are not yet connected to the shell.
- Symbian/EKA2L1 is deferred; no Symbian firmware or SIS/SISX execution is part of the current milestone.
- WAP browser, OTA catalogue and website.
- Manufacturer-specific finished phone artwork and original sound packs; current color LCD/menu rendering is an original approximation, not original firmware UI.
- The RM-217 hardware model and native Android engine are not implemented. c3emu is a feasibility/reference project for RM-614 (C3-00), not a drop-in RM-217 emulator.
- Firmware import currently accepts individual extracted FPSX components; it does not unpack Phoenix/firmware installer executables or prove that a set is mutually compatible.
- Historically faithful Nokia 6300 standby/menu/submenu screen flow remains available as a temporary fallback, not the main long-term emulation target.
- Per-title network permissions and runtime isolation beyond the import library's private storage.

## Next engineering steps

1. Complete the Nokia 6300 standby, main menu, soft-key, Navi-key and submenu experience against the original user guide.
2. Add the remaining visible Series 40 screens: Messaging, Contacts, Gallery, Media, Organizer and Settings.
3. Validate color rendering, screen geometry and keypad navigation on the physical test phone.
4. Complete the RM-217 hardware research and add a minimal CPU/SoC boot probe behind PhoneSystemEngine.
5. Port the validated emulator core to Android through Rust JNI; retain a desktop test harness for repeatable boot diagnostics.
6. Integrate the emulated LCD frame, keypad press/release, PCM audio and persistent flash into the phone shell.
7. Test the original Series 40 Java ME environment and Opera Mini only after the OS reaches a stable user interface.
