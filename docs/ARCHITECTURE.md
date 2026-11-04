# Architecture

## 1. High-level modules

```text
RetroMobileWorld Android host
├── Shell UI
│   ├── Device Cabinet / Profile selector
│   ├── Device frame renderer
│   ├── Display viewport
│   ├── Virtual keypad + soft keys
│   └── Host settings / accessibility
├── Device Profile Registry
│   ├── Versioned JSON loaded at app startup
│   ├── Device OS family (S40 / S60 / vendor feature-phone UI)
│   ├── Screen geometry + normalized display aperture
│   ├── Shell skin assets + normalized key hit areas
│   ├── Canonical key mapping + navigation style
│   └── One-or-more runtime targets per device
├── Runtime Orchestrator
│   ├── EmulatorBackend contract
│   ├── Java ME companion bridge (initially J2ME Loader)
│   └── Symbian adapter (candidate: EKA2L1)
├── Application Library
│   ├── Android document picker
│   ├── JAR/JAD metadata inspector
│   ├── SHA-256-addressed private package store
│   └── Runtime handoff via temporary content URI
├── Catalogue Layer
│   ├── Source registry
│   ├── Live website adapter
│   ├── Archive adapter
│   └── Provenance / compatibility metadata
└── Local storage
    ├── Profiles
    ├── App records and package files
    ├── Per-title data containers (future)
    └── Engine configuration references
```

## 2. Runtime contract

The host must not call engine-specific APIs directly from screen widgets. It sends actions to a backend adapter.

The backend boundary remains deliberately small until the in-process engine surface and input integration have been proven:

- prepare a runtime for a selected device profile;
- validate and install a package;
- launch and stop a MIDlet;
- send canonical key down/up events;
- provide display lifecycle and surface callbacks;
- expose per-title storage and permission controls.

The current companion bridge is an interim implementation. It shares a read-only URI with J2ME Loader; it does not expose a render surface or send the shell's keypad events yet.

## 3. Device profile vs. operating system

A device profile owns hardware presentation:

- body/shell and screen aperture;
- logical display size and pixel scaling;
- keypad positions and hit areas;
- key-to-runtime mapping;
- speaker and feedback options.

An engine owns software behaviour:

- Java ME: MIDP/CLDC runtime, AMS, MIDlet lifecycle, RMS and Java APIs.
- Symbian: OS kernel/system services, native package manager, application environment and its system UI.

A device profile is not a firmware image. Nokia S40-style and Sony Ericsson-style profiles are currently compatibility-oriented shells, not full recreations of the original proprietary OS firmware. Java ME programs run in a MIDP/CLDC runtime and do not require us to boot the complete phone firmware. A true Symbian environment is a separate emulation problem.

## 4. Display pipeline

1. The shell renderer reads the profile's logical display dimensions.
2. An integrated backend should render into a frame/surface compatible with that logical resolution.
3. The shell scales the frame into the profile's display aperture.
4. The renderer applies an integer/pixel-preserving scale or fit-with-letterboxing policy.
5. The bezel and keypad skin are drawn around the viewport; the game viewport is never stretched to fill a mismatched aperture.

The current external J2ME Loader handoff leaves display rendering to the companion application. An embedded backend must prove a stable surface before this boundary is frozen.

## 5. Input pipeline

Physical touch regions and optional hardware keyboard events map to `CanonicalKey` values, such as `UP`, `DOWN`, `LEFT`, `RIGHT`, `SELECT`, `SOFT_LEFT`, `SOFT_RIGHT`, `BACK`, `CALL`, `END`, `DIGIT_0..9`, `STAR`, `HASH`.

Press and release events are both required for held-key behaviour. The current shell prototype reacts to touch-up events for menu navigation. The integrated MIDlet backend must receive both edges and must release held keys when focus or lifecycle changes.

## 6. Package pipeline

```text
Local import / WAP page / catalogue item
                 │
                 ▼
           Source validation
                 │
                 ▼
       Package type + metadata parser
                 │
                 ▼
           User confirmation
                 │
                 ▼
          Private package store
                 │
                 ▼
        Explicit runtime handoff
                 │
                 ▼
          Engine installer / MIDlet
```

No package is launched directly from an arbitrary download URL. Current import supports local JAR and JAD documents. It copies the selected file into app-private storage, computes SHA-256, inspects the JAR manifest or JAD descriptor, and only then adds it to the in-phone list. No archive extraction or automatic code execution occurs during import. The importer rejects unsupported extensions, malformed MIDlet metadata and packages above 128 MiB. JAD handoff currently requires an absolute HTTP(S) `MIDlet-Jar-URL`; import the corresponding JAR for local/relative descriptors.

The FileProvider exposes only the private package directory. A per-launch read grant is given to the explicitly selected J2ME Loader activity.

## 7. Storage layout

- `profiles/`: public profile definitions; user edits belong in a separate writable area.
- `filesDir/legacy-library/packages/`: content-addressed imported JAR/JAD files.
- `runtime/j2me/<app-id>/`: target location for future isolated MIDlet suite data.
- `runtime/symbian/`: backend-managed system image/configuration references; no firmware copied into app assets.
- `catalog/cache/`: metadata and page cache only; downloaded packages are governed by user settings.

## 8. Host platform strategy

Android first. The profile format and runtime contract are platform-neutral. A desktop host can later reuse profile/catalogue/compatibility data and provide a desktop-specific renderer/input layer. Do not force the first Android milestone to become a cross-platform framework prematurely.

## 9. Symbian system images

A Symbian profile describes hardware and visual identity; it does not contain the Symbian OS. EKA2L1 emulates the Symbian kernel and critical app servers. Depending on the target generation, setup needs a compatible ROM/RPKG or firmware with its required metadata. The user supplies these system files; they are not bundled in this application. Android builds of EKA2L1 are documented by upstream as experimental, so compatibility must be measured on the target device before promising support.
