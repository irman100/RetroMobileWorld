# Roadmap

## Milestone 0 — bootstrap

- [x] Product direction and modular architecture documented.
- [x] Engine candidates and headline licences researched.
- [x] Device profile model and three sample profiles drafted; N73 retains a deferred Symbian backend, while Java ME is the only runtime enabled in the current user interface.
- [x] JSON profile loader wired into Android startup; per-device display aperture and all 22 canonical keypad controls are profile data.
- [x] Static source registry format drafted.
- [x] Android CI workflow, Gradle Wrapper, remote build and local Android SDK build established.

## Milestone 1 — Nokia 6300 / Series 40 fidelity

- [x] Expand the host UI to use the Android viewport while respecting system-bar insets.
- [x] Preserve each profile's native LCD aspect ratio while using the available display height.
- [x] Add local-only RM-217 Phoenix ZIP intake and FPSX inspection; selected firmware components stay in app-private storage.
- [x] Identify the supplied VPL variant: product code 0537625, software 07.30, variant 018.
- [x] Inspect MCU/PPM/IMAGE FPSX regions and calculate SHA-256 / CRC-32 values.
- [x] Identify factory Opera Mini 4.0.9 MIDlet metadata in the content image.
- [ ] Resolve the PPM CRC mismatch (VPL expected C585B71C; included file CRC 613D565F).
- [ ] Compare the RM-217 hardware map and reset-entry semantics against the C3-00 c3emu implementation before adapting any SoC model.
- [ ] Reconstruct standby, status indicators, menu views, category screens, gallery and navigation against the verified RM-217 reference.
- [ ] Use only resources with a documented right-to-use basis in distributable builds; independently recreate any asset that is not cleared for packaging.
- [ ] Recreate the Nokia 6300 standby screen, original soft-key layout and Navi-key path into the main menu.
- [ ] Build the Series 40 main menu grid and navigable category submenus from the 6300 reference manual.
- [ ] Add consistent Contacts, Messaging, Gallery, Media, Organizer and Settings screen layouts.
- [x] Install and launch the shell on a physical Android device.
- [ ] Replace placeholder geometry with original, scalable vector shell artwork.
- [ ] Verify all soft keys, D-pad, digits, star/hash and back/call/end hit areas on each profile.
- [ ] Add profile import/export and profile validation.
- [ ] Add snapshot/UI tests for the three sample device profiles.

## Milestone 1.5 — RM-217 firmware emulator spike

- [x] Research c3emu as a working reference that boots original Series 40 firmware for Nokia C3-00 / RM-614.
- [x] Separate the firmware/CPU-machine contract from the Java ME application-runtime contract in Android code.
- [ ] Pin and review c3emu source and all notices before importing code; do not vendor its RM-614 machine as if it were RM-217-compatible.
- [ ] Determine the RM-217 CPU core, memory map, LCD controller, keypad scanner, timer/interrupt controller, PMIC, storage and DSP requirements from service documentation and firmware traces.
- [ ] Build a desktop RM-217 boot probe first, with deterministic logging and no UI dependency.
- [x] Select the large MCUSW named region (13,656,960 bytes), not the 1,024-byte duplicate header segment.
- [x] Decode the static first-stage entry sequence: candidate XIP base 0xF0100000, ARM entry 0xF01003D0, Thumb continuation 0xF0100410, initial SP 0x08004000.
- [ ] Validate the candidate XIP mapping, entry registers and early RAM aliases by executing a bounded RM-217 boot probe.
- [ ] Reconstruct the RM-217 IMAGE flash mapping rather than using c3emu's RM-614-only custom content-image parser.
- [ ] Make the first success criterion a repeatable boot to a stable LCD frame; absent GSM/SIM/radio functions may be stubbed only after proving they are not required for that boot path.
- [ ] Port the proven core to Android ARM64 via Rust JNI and a bounded render/input loop.

## Milestone 2 — integrated Java ME applications

- [ ] Replace the separate-app J2ME Loader handoff with an in-app MIDP/CLDC runtime surface.
- [ ] Route virtual Nokia soft keys and D-pad press/release events into the running MIDlet.
- [ ] Support installation and launch of arbitrary user-selected Java ME JAR/JAD applications from the virtual phone.
- [ ] Validate Opera Mini as a priority MIDlet: import, install, launch in the phone display, navigation, network/proxy and TLS behaviour.
- [ ] Keep the separate J2ME Loader handoff only as a temporary compatibility/smoke-test option until the in-app backend is proven.

- [x] Add a local package library for JAR/JAD import, metadata inspection, SHA-256 identity and app-private storage.
- [x] Add an in-phone Java applications list and explicit RUN handoff to J2ME Loader through a read-only content URI.
- [ ] Run CI and validate the package import/handoff flow on the physical test phone.
- [ ] Confirm installation and execution of a small, legally redistributable or self-authored MIDlet.
- [ ] Connect the MIDlet rendering surface to the selected phone display aperture.
- [ ] Route profile canonical keys to the Java ME engine with reliable press/release events.
- [ ] Add per-title RMS storage, lifecycle handling and user-controlled network access.
- [ ] Pin the engine source revision and capture its complete licence/NOTICE inventory before vendoring or embedding it.

## Deferred milestone — Symbian runtime (after Java ME)

- [ ] Revisit EKA2L1 only after Java ME runtime, display and keypad integration are complete.

## Milestone 4 — retro mobile internet

- [ ] Implement a browser/catalogue handoff for JAD/JAR downloads.
- [ ] Add source registry status and archival links.
- [ ] Add WAP-oriented rendering mode (small viewport, legacy encodings where technically safe).
- [ ] Recreate OTA installation prompts with consent and metadata validation.
- [ ] Publish a static website generated from registry/profile data.

## Milestone 5 — preservation ecosystem

- [ ] Add a compatibility database and community correction workflow.
- [ ] Add more verified phone profiles and original shell skins.
- [ ] Add optional Windows host after engine strategy stabilizes.
- [ ] Consider sound packs, themes, wallpapers and local preservation bundles with rights metadata.
