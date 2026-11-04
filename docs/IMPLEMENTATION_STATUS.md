# Implementation status

Snapshot: 4 November 2026. This describes the current development branch; physical-device engine validation is still pending.

## Implemented

- Android application entry point and interactive phone-shell prototype.
- Three device profiles: Nokia 6300/S40 style, Sony Ericsson K750i style and Nokia N73/S60 style.
- Versioned JSON device-profile registry with logical display dimensions, display aperture and 22 canonical keypad controls.
- Runtime capability model: the N73-like profile advertises both Java ME and Symbian.
- Local Java ME package library:
  - imports `.jar` and `.jad` through Android's document picker;
  - copies packages into app-private storage and identifies them by SHA-256;
  - inspects JAR manifests and JAD descriptors without extracting or executing the package;
  - rejects malformed descriptors, unsupported file types and oversized packages;
  - presents imported MIDlets inside the virtual phone's Java applications screen.
- Read-only FileProvider handoff to the separate J2ME Loader Android app when the user selects RUN.
- Unit tests for JAR metadata, JAD metadata and malformed/missing MIDlet descriptors.
- Versioned catalogue source registry, registry validators and Android CI workflow.
- Product, architecture, engine, catalogue, legal, roadmap and decision documents.

## Implemented but not yet device-verified

- External J2ME Loader handoff. It requires the separate `ru.playsoftware.j2meloader` package to be installed. The integration uses its documented Android `ACTION_VIEW` file-handling entry point, but this branch still needs an end-to-end test on the target phone.
- JAD handoff for descriptors with absolute HTTP(S) `MIDlet-Jar-URL`. Relative/local JAD references are intentionally not launched by the current prototype.

## Not implemented

- J2ME Loader is not embedded in the Retro Mobile World APK. The game display, canonical keypad events, MIDlet lifecycle and RMS storage are not yet connected to the shell.
- Symbian/EKA2L1 runtime integration, Android ABI validation, firmware setup and SIS/SISX installation.
- WAP browser, OTA catalogue and website.
- Manufacturer-specific finished phone artwork and original sound packs; current device shells still use neutral placeholder geometry.
- Per-title network permissions and runtime isolation beyond the import library's private storage.

## Next engineering steps

1. Run CI for the Java package-library branch and fix any failures.
2. Validate JAR import, metadata display, J2ME Loader handoff and MIDlet installation on a physical Android phone.
3. Prove display sizing and canonical key mapping with reference MIDlets; decide whether to embed a maintained J2ME Loader fork or keep a companion process temporarily.
4. Add per-title settings, RMS persistence and explicit runtime permissions.
5. Evaluate EKA2L1 on Android separately; require user-provided compatible system files.
