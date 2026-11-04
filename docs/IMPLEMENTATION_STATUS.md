# Implementation status

Snapshot: 4 November 2026. This status describes the bootstrap scaffold, not a release build.

## Implemented in the starter project

- Android application entry point and an interactive phone-shell prototype.
- Three built-in fallback profiles: Nokia 6300/S40 style, Sony Ericsson K750i style, and Nokia N73/S60 style.
- Versioned JSON device-profile registry loaded from Android assets at application startup.
- Per-profile logical display dimensions and normalized display aperture.
- Per-profile keypad control list with 22 canonical controls, normalized hit rectangles, and a navigation-cluster style.
- Multi-runtime capability model: the N73-like profile advertises both Java ME and Symbian; other starter profiles advertise Java ME.
- A runtime-backend contract, package metadata model and user-supplied Symbian system-image reference.
- Versioned catalogue source registry with an explicit source status and redistribution status.
- Dependency-free Python registry validation and an initial Android CI workflow.
- Product, architecture, engine, catalogue, legal, roadmap and decision documents.

## Not implemented or not verified yet

- J2ME Loader has not been embedded; its Android render-surface and external-input integration has not been proven.
- EKA2L1 has not been embedded; Android ABI, firmware setup, rendering and input integration remain unproven.
- JAR/JAD parsing, OTA downloads, package installation, SIS/SISX import and app launch are not implemented.
- The WAP browser and catalogue website are not implemented. Source entries are metadata and outbound links only; historical endpoints are not marked live unless separately verified.
- Manufacturer-specific finished shell artwork, original sound packs and exact model-by-model mechanical geometry are not included yet. The renderer uses original neutral placeholder geometry with profile-specific layout/style data.
- No Android APK or Android SDK build has been produced in this environment.
- The CI workflow has been authored but has not run because the repository has not yet been created/pushed.

## Immediate engineering sequence

1. Bootstrap the private GitHub repository and let the initial CI run.
2. Build and install the shell prototype on an Android device; correct screen fit, input hitboxes and lifecycle issues.
3. Run a standalone J2ME Loader build and prove that its frame can be rendered in our viewport and receives canonical key events.
4. Build/run EKA2L1 on the chosen Android ABI and measure boot, system-image setup, compatibility and frame handoff.
5. Decide engine packaging and the GPL distribution boundary before shipping either runtime inside the same APK.
6. Implement safe JAR/JAD import and OTA flow only after the Java ME engine spike passes.
7. Publish a static catalogue site only after the source registry contains checked URLs and status notes.
