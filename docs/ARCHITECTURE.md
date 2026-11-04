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
│   ├── J2ME adapter (candidate: J2ME Loader)
│   └── Symbian adapter (candidate: EKA2L1)
├── Application Library
│   ├── Package importer
│   ├── JAD/JAR metadata parser
│   ├── SIS/SISX handoff/import metadata
│   └── Install / launch history
├── Catalogue Layer
│   ├── Source registry
│   ├── Live website adapter
│   ├── Archive adapter
│   └── Provenance / compatibility metadata
└── Local storage
    ├── Profiles
    ├── App records
    ├── Per-title data containers
    └── Engine configuration references
```

## 2. Runtime contract

The host must not call engine-specific APIs directly from screen widgets. It sends actions to a backend adapter.

Proposed contract (conceptual, not yet a stable public API):

```kotlin
interface EmulatorBackend {
    val id: String
    val capabilities: Set<BackendCapability>

    suspend fun prepare(profile: DeviceProfile, systemImage: SystemImageRef?): BackendResult
    suspend fun install(packageFile: PackageFile): InstallResult
    suspend fun launch(appId: String, display: DisplaySpec): BackendResult
    suspend fun stop(): BackendResult
    fun sendKey(key: CanonicalKey, pressed: Boolean)
}
```

The interface may need platform-specific surfaces, lifecycle callbacks and streaming frame output after the engine spikes. Do not freeze it until both engines have been tested.

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

This boundary lets multiple phones share one backend while still looking and controlling like different devices. It also prevents a Symbian OS from being reduced to a wallpaper.

## 4. Display pipeline

1. The shell renderer reads the profile's logical display dimensions.
2. The backend renders into a frame/surface compatible with that logical resolution.
3. The shell scales the frame into the profile's display aperture.
4. The renderer applies a selectable integer/pixel-perfect scale or fit-with-letterboxing policy.
5. The bezel and keypad skin are drawn above/below the display; the game viewport is never stretched to fill a mismatched aperture.

The first prototype draws a placeholder screen. The eventual engine integration must provide a real render surface or a stable external-window handoff.

## 5. Input pipeline

Physical touch regions and optional hardware keyboard events map to `CanonicalKey` values, such as `UP`, `DOWN`, `LEFT`, `RIGHT`, `SELECT`, `SOFT_LEFT`, `SOFT_RIGHT`, `BACK`, `CALL`, `END`, `DIGIT_0..9`, `STAR`, `HASH`.

Backend adapters translate canonical keys to their own keycodes. Press and release events are both required to support held-key behaviour. Repeated-key cadence belongs to a profile/backend policy and must not create stuck keys when focus changes.

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
          Backend installer
                 │
                 ▼
          Installed app record
                 │
                 ▼
        Device launcher / runtime
```

No package is launched directly from a download URL. The package must be stored locally, inspected and installed first.

## 7. Storage layout

- `profiles/`: public profile definitions; user edits are stored separately from built-ins.
- `library/packages/`: imported package files, keyed by content hash.
- `library/metadata/`: parsed metadata and provenance.
- `runtime/j2me/<app-id>/`: isolated MIDlet suite data.
- `runtime/symbian/`: backend-managed system image/configuration references; no firmware copied into the app's assets.
- `catalog/cache/`: metadata and page cache only; downloaded packages are governed by user settings.

## 8. Host platform strategy

Android first. The profile format and runtime contract are platform-neutral. A desktop host can later reuse profile/catalogue/compatibility data and provide a desktop-specific renderer/input layer. Do not force the first Android milestone to become a cross-platform framework prematurely.


## 9. Device profile versus runtime

A device profile is a hardware-and-UI description, not an emulator. The versioned JSON registry is loaded at app startup and checked by the model constructors. It contains the logical display, normalized shell aperture, artwork ID, normalized keypad hit areas, keymap and sound profile. It also lists every runtime supported by that device. The Nokia N73 sample therefore advertises both `JAVA_ME` and `SYMBIAN`; selecting either runtime keeps the same N73 profile active. A Nokia 6300-like S40 profile advertises Java ME only. A runtime backend may reject a package when its actual API/firmware requirements are not met.
