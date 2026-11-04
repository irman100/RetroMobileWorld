# Emulator engine research

Research snapshot: 4 November 2026. Re-check upstream tags, licences, build instructions and Android compatibility before every engine update.

## Java ME candidates

### J2ME Loader — preferred Android candidate

- Upstream: <https://github.com/nikita36078/J2ME-Loader>
- Licence: Apache License 2.0.
- Android application, supports most 2D/3D Java ME games, virtual keyboard, per-app settings and scaling.
- Existing project contains an Android `MicroActivity` launcher, so it is an application project rather than an already-proven embeddable library. The first task is a technical spike: determine whether it can be included as a Gradle module and whether its render view/input can be hosted inside our phone viewport.
- Proposed route: maintain a clearly attributed fork or source module if required; pin upstream commit; preserve Apache notices and third-party notices.

### FreeJ2ME — alternative / desktop candidate

- Upstream: <https://github.com/hex007/freej2me>
- Licence: GPL-3.0-or-later for FreeJ2ME; the repository also contains ObjectWeb ASM under its own permissive licence.
- Offers AWT, SDL2 and libretro frontends; can launch a JAR and accept a logical width/height.
- It is a useful desktop/proof-of-concept alternative, but it is not an Android-ready drop-in library. The libretro path also has a Java runtime dependency and native frontend requirements.

## Symbian candidate

### EKA2L1 — preferred Symbian/N-Gage candidate

- Upstream: <https://github.com/EKA2L1/EKA2L1>
- Licence: GPL-3.0.
- Reimplements Symbian kernel behaviour and key application servers/libraries; supports a broad set of N-Gage and Symbian game libraries from S60v1 to Belle, while general application compatibility remains limited.
- Upstream publishes Windows, macOS, Linux and Android builds. Its own download page describes Android support as experimental, with compatibility varying by title and architecture.
- Setup needs ROM/RPKG or firmware files depending on the target Symbian generation. The application must not bundle proprietary system images.
- The first spike must determine whether Android's EKA2L1 UI/renderer can be embedded, whether a companion-app workflow is required, and which Android ABI/build is suitable.

## Licensing consequences

- J2ME Loader is Apache-2.0; retain the upstream copyright, licence and NOTICE material, including notices for dependencies.
- EKA2L1 is GPL-3.0. If the distributed product combines/links EKA2L1 into one derivative work, GPL obligations may apply to the combined distribution. This needs a deliberate product decision and qualified legal review before publishing binaries.
- A separate process or companion-app arrangement may reduce coupling, but must not be presented as a guaranteed legal workaround. The architecture decision depends on technical coupling and legal review.
- This repository's own code is initially GPL-3.0-only to keep the all-in-one open-source route available. Third-party components remain under their own licences; the top-level licence does not relicense them.

## Decision gates

1. Build and run J2ME Loader independently on the test Android device.
2. Test a controlled MIDlet set: small MIDP 1.0 app, MIDP 2.0 game, soft-key test, RMS test, sound test and at least one optional JSR API test.
3. Determine whether J2ME Loader can expose a render surface and accept external key events without forking core code heavily.
4. Build and run EKA2L1 on the same Android ABI/device; record boot, firmware setup, title compatibility, input latency and renderer behaviour.
5. Decide integrated GPL route vs. companion-engine route before embedding Symbian binaries.
6. Pin each upstream source to a commit SHA and store its licence/NOTICE snapshot with the integration record.

## Reference links

- J2ME Loader README/licence: <https://github.com/nikita36078/J2ME-Loader>
- EKA2L1 README/build/download: <https://github.com/EKA2L1/EKA2L1> · <https://eka2l1.github.io/download/> · <https://eka2l1.github.io/quickstart/basic/installdevice/>
- FreeJ2ME: <https://github.com/hex007/freej2me>
- Java ME OTA provisioning overview: <https://www.oracle.com/technical-resources/articles/javame/intro-ota-application-provisioning.html>
