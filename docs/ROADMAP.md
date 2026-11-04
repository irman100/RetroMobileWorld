# Roadmap

## Milestone 0 — bootstrap

- [x] Product direction and modular architecture documented.
- [x] Engine candidates and headline licences researched.
- [x] Device profile model and three sample profiles drafted, with Java ME as the only active runtime in the current release; Symbian is deferred.
- [x] JSON profile loader wired into Android startup; per-device display aperture and all 22 canonical keypad controls are profile data.
- [x] Static source registry format drafted.
- [x] Android CI workflow, Gradle Wrapper, remote build and local Android SDK build established.

## Milestone 1 — phone-shell vertical slice

- [x] Basic Android phone-shell prototype with profile switching and runtime selection.
- [x] Install and launch the shell on a physical Android device.
- [ ] Replace placeholder geometry with original, scalable vector shell artwork.
- [ ] Verify all soft keys, D-pad, digits, star/hash and back/call/end hit areas on each profile.
- [ ] Add profile import/export and profile validation.
- [ ] Add snapshot/UI tests for the three sample device profiles.

## Milestone 2 — Java ME application execution

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
