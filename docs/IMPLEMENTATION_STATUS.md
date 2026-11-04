# Implementation status

Snapshot: 4 November 2026. This describes the current development branch; physical-device engine validation is still pending.

## Implemented

- Android application entry point and full-viewport phone-shell prototype: top device toolbar, expanded virtual display and compact keypad panel above the system navigation area.
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
- First functional Gallery file flow: select local media with Android's document picker, browse the selected files in the virtual phone, and open a file with a compatible Android viewer.
- Unit tests for JAR metadata, JAD metadata and malformed/missing MIDlet descriptors.
- Versioned catalogue source registry, registry validators and Android CI workflow.
- Product, architecture, engine, catalogue, legal, roadmap and decision documents.

## Implemented but not yet device-verified

- External J2ME Loader handoff. It requires the separate `ru.playsoftware.j2meloader` package to be installed. The integration uses its documented Android `ACTION_VIEW` file-handling entry point, but this branch still needs an end-to-end test on the target phone.
- JAD handoff for descriptors with absolute HTTPS `MIDlet-Jar-URL`. Relative/local JAD references are intentionally not launched by the current prototype.

## Not implemented

- J2ME Loader is not embedded in the Retro Mobile World APK. The game display, canonical keypad events, MIDlet lifecycle and RMS storage are not yet connected to the shell.
- Symbian/EKA2L1 is deferred; no Symbian firmware or SIS/SISX execution is part of the current milestone.
- WAP browser, OTA catalogue and website.
- Manufacturer-specific finished phone artwork and original sound packs; current color LCD/menu rendering and vector icons are original approximations, not original firmware UI.
- Gallery currently uses Android's document picker and external viewers; in-shell thumbnails, preview and playback remain unfinished.
- Historically faithful Nokia 6300 standby/menu/submenu screen flow is still being implemented; this is now the first UI priority.
- Per-title network permissions and runtime isolation beyond the import library's private storage.

## Next engineering steps

1. Complete the Nokia 6300 standby, main menu, soft-key, Navi-key and submenu experience against the original user guide.
2. Add the remaining visible Series 40 screens: Messaging, Contacts, Gallery, Media, Organizer and Settings.
3. Validate color rendering, screen geometry and keypad navigation on the physical test phone.
4. Resume Java ME engine integration: JAR/JAD launch, display surface, canonical key events, RMS and lifecycle.
5. Revisit Symbian only after the Nokia/Java ME experience is complete.
