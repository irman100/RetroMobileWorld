# Roadmap

## Milestone 0 — bootstrap

- [x] Product direction and modular architecture documented.
- [x] Engine candidates and headline licences researched.
- [x] Device profile model and sample profiles drafted.
- [x] Static source registry format drafted.
- [x] Android CI workflow, Gradle Wrapper, remote build and local Android SDK build established.

## Milestone 1 — real Nokia 6300 / RM-217 firmware

- [x] Preserve the phone display aperture and physical keypad in the Android shell.
- [x] Replace the temporary hand-drawn home/menu hierarchy with a direct RM-217 firmware-loading screen.
- [x] Add local-only Phoenix ZIP intake for the version-matched MCU, PPM and canonical IMAGE components.
- [x] Inspect selected components as BB5 FPSX containers.
- [x] Parse the matching VPL manifest and compare component CRC-32 values in the Android importer.
- [x] Identify the supplied VPL variant: product code 0537625, software 07.30, variant 018.
- [ ] Resolve the PPM CRC mismatch (VPL expected C585B71C; included file CRC 613D565F).
- [ ] Verify the importer against the real archive on the target Android phone.
- [ ] Reconstruct the RM-217 CPU/SoC and hardware map from firmware traces and reliable technical evidence.
- [ ] Validate reset-entry semantics, XIP mapping, RAM aliases and early boot MMIO accesses with the desktop probe.
- [ ] Boot the original RM-217 firmware to a repeatable, stable LCD frame.
- [ ] Route physical phone key press/release events into the emulated RM-217 keypad.
- [ ] Render the firmware's actual screen inside the retained display aperture; do not create a parallel simulated Series 40 menu system.
- [ ] Port only the validated machine core to Android ARM64 via Rust JNI and a bounded render/input loop.

## Milestone 1A — experimental Nokia C3-00 / RM-614 Series 40 runtime

- [x] Integrate the pinned upstream c3emu native backend through Rust JNI.
- [x] Add separate C3-00 Phoenix ZIP / MCUSW / PPM / IMAGE intake without mixing it with RM-217.
- [x] Build native libraries for arm64-v8a, armeabi-v7a and x86_64 in Android CI.
- [x] Fix the FPSX preflight check to accept upstream-compatible 32-bit address metadata while preserving container file-boundary checks.
- [x] Add a regression test for an FPSX region crossing the unsigned 32-bit address boundary.
- [x] Package the user-selected front-facing C3-00 shell image with a pinned Git blob integrity check.
- [x] Add Windows SDK discovery and a safe local build/test/install workflow for a physical Android phone.
- [ ] Build version 0.4.1 (versionCode 5) locally and install it on the connected physical phone.
- [ ] Confirm import of the user's RM-614 Phoenix ZIP with no 32-bit FPSX range error.
- [ ] Boot Series 40 to a real LCD frame/home screen and verify physical touch-key press/release.
- [ ] Calibrate LCD aperture and physical keypad hit regions against the selected PNG on the phone.
- [ ] Verify audio and persistent C: storage on the target phone.

## Milestone 2 — integrated Java ME applications

- [ ] Replace the temporary J2ME Loader handoff with an in-app MIDP/CLDC runtime surface.
- [ ] Route the emulated Nokia soft keys and D-pad events into the running MIDlet.
- [ ] Validate Opera Mini as a priority MIDlet after the RM-217 OS reaches standby.
- [ ] Add per-title RMS storage, lifecycle handling and user-controlled network access.
- [ ] Pin engine source and capture complete licence/NOTICE inventory before embedding it.

## Deferred milestone — Symbian runtime

- [ ] Revisit EKA2L1 only after the RM-217 OS and Java ME integration are complete.

## Milestone 4 — retro mobile internet

- [ ] Implement a browser/catalogue handoff for JAD/JAR downloads.
- [ ] Add source registry status and archival links.
- [ ] Add WAP-oriented rendering mode with legacy encodings where technically safe.
- [ ] Recreate OTA installation prompts with consent and metadata validation.
- [ ] Publish a static website generated from registry/profile data.

## Milestone 5 — preservation ecosystem

- [ ] Add a compatibility database and community correction workflow.
- [ ] Add more verified phone profiles and original shell skins.
- [ ] Consider sound packs, themes, wallpapers and local preservation bundles with rights metadata.
