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
- [x] Reposition first-pass C3 D-pad and lower-keyboard hotspots against the exact shell PNG (physical-phone verification remains pending).
- [ ] Install the latest responsiveness build from commit `51e60a1` over the existing app without clearing app data.
- [x] Confirm import of the user's RM-614 Phoenix ZIP and successful launch of the full Series 40 firmware on the physical phone (user-confirmed 8 November 2026).
- [x] Confirm guest firmware UI appears and the user can navigate menus / attempt to open applications.
- [ ] Verify every physical touch key, including fast taps, holds, releases, D-pad and QWERTY after installing the input-queue fix.
- [x] Measure the C3-00 D-pad rim and lower keyboard keycap centres against the selected front-facing PNG (photo-space geometry only; phone validation remains separate).
- [x] Draw near-transparent touch markers at the physical keycap centres and bind them to the same hit regions.
- [x] Centralize physical key matrix indices and test key down/up, pointer movement, multi-touch and cancel.
- [x] Parse RM-614 `.image_*` sparse FAT16 content images and cover multiple sparse blocks in unit tests.
- [x] Pair PPM and IMAGE only when software release and regional suffix match.
- [x] Transfer imported firmware from app-private file paths directly into native c3emu buffers.
- [x] Reduce host-side stalls: offload blocking audio, match upstream 16 ms slices / 15 ms frame cadence / 20 ms WFI cap, and throttle flash persistence.
- [x] Fix dropped quick taps by retaining key-down through one complete native CPU slice; enlarge invisible touch hit areas while preserving faint marker appearance.
- [x] Replace Activity-per-slice round trips with a continuous single-owner native worker and coalesce stale LCD frames.
- [x] Add `C3EmuPerf` Logcat counters for cycle rate, delivered FPS and native c3emu stats; CI run 42475334037 green on commit 51e60a1.
- [x] Pass Android CI on C3 touch matrix / key-edge tests and sparse multi-block IMAGE fixture; assemble and upload debug APK artifact `13614395927` (latest branch head `4c00850`, push + PR runs both green).
- [x] Pin only Kotlin compiler embeddable to exclusive WSO2 Maven POM/artifact source to avoid the recurring Kotlin 2.2.10 GitHub Releases HTTP 504.

- [ ] Import the actual user-supplied RM-614 IMAGE file on the physical phone and confirm factory C: content appears on first boot.
- [ ] Install commit `51e60a1` build and measure guest frame rate and input-to-screen latency on the physical phone from `C3EmuPerf` logs.
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
