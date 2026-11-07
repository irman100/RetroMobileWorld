# ADR-0003: Use J2ME Loader as the first Java ME runtime bridge

- Status: Accepted for the first execution slice; not the final integrated-shell architecture.
- Date: 2026-11-04.

## Context

Retro Mobile World currently provides a data-driven phone shell and canonical keypad model, but has no MIDP/CLDC virtual machine. J2ME Loader is an actively updated Android application under Apache-2.0. Its upstream Android manifest accepts JAR/JAD files through an exported `ACTION_VIEW` activity, and its main activity opens the installer flow for a supplied content URI.

J2ME Loader is a complete Android application rather than a published embeddable library. Its MIDlet activity, storage, Android application class, resources, native code and UI are coupled to its application module. Embedding it as a library is therefore a separate engineering project, not a one-line Gradle dependency.

## Decision

For the first working Java ME execution path, Retro Mobile World will:

1. Import local `.jar` and `.jad` files through Android's document picker.
2. Inspect package metadata without extracting or executing archive contents.
3. Store accepted files in an app-private, SHA-256-addressed library.
4. Display imported MIDlets in the selected virtual phone's Java applications list.
5. Share a read-only content URI with the separately installed `ru.playsoftware.j2meloader` app only after the user selects RUN.
6. Leave installation confirmation, MIDlet lifecycle, rendering and runtime permissions to J2ME Loader during this interim phase.

JAD files with an absolute HTTPS `MIDlet-Jar-URL` can be handed to the engine. A JAD that references a relative or local JAR is not launched by this first slice; import the JAR directly.

## Consequences

- This creates a real route to install and execute Java ME programs without copying an emulator implementation into this repository.
- The engine must be installed separately, and the current game display/keyboard do not remain embedded in the Retro Mobile World phone frame during execution.
- The package library does not download software automatically, execute packages on import, extract archive contents or grant broad storage access.
- A future release may embed a maintained J2ME Loader fork or add a dedicated engine module. That requires a technical spike for render-surface hosting, canonical key press/release routing, MIDlet lifecycle, RMS persistence and complete dependency notices.
- This integration decision is not a final endorsement of the companion-app UX.

## References

- J2ME Loader (Apache-2.0): https://github.com/nikita36078/J2ME-Loader
- Android document access: https://developer.android.com/guide/topics/providers/document-provider
- Android FileProvider URI grants: https://developer.android.com/reference/androidx/core/content/FileProvider
