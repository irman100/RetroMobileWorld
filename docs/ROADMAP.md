# Roadmap

## Milestone 0 — bootstrap (current)

- [x] Product direction and modular architecture documented.
- [x] Engine candidates and headline licences researched.
- [x] Device profile model and three sample profiles drafted, including dual Java ME + Symbian support on the N73-like S60 profile.
- [x] JSON profile loader wired into Android startup; per-device display aperture and all 22 canonical keypad controls are profile data.
- [x] Static source registry format drafted.
- [x] Basic interactive Android phone-shell prototype added with different keypad/casing treatments and runtime switching on the dual-runtime profile.
- [x] Initial Android CI workflow added (not yet executed).
- [ ] Create/push GitHub repository from the prepared Windows bootstrap script.
- [ ] First remote CI run and Android SDK build verification.

## Milestone 1 — shell vertical slice

- [ ] Build the Android app with current Android Studio / SDK.
- [ ] Verify the shell on a physical Android device and emulator.
- [ ] Replace placeholder geometry with original, scalable vector shell artwork.
- [ ] Verify all soft keys, D-pad, digits, star/hash and back/call/end hit areas.
- [ ] Add profile import/export and profile validation.
- [ ] Add snapshot/UI tests for the three sample device profiles.

## Milestone 2 — Java ME runtime

- [ ] Pin J2ME Loader upstream revision and capture its licence/NOTICE inventory.
- [ ] Build the upstream app and run a small reference MIDlet set.
- [ ] Prototype module integration and surface/input routing.
- [ ] Implement JAR/JAD parsing, install prompts, local library and per-title storage.
- [ ] Prove the display and keys follow device profiles.
- [ ] Add compatibility records by backend version + profile.

## Milestone 3 — Symbian runtime

- [ ] Pin and build EKA2L1 for the chosen Android ABI/device.
- [ ] Test boot and firmware setup using a user-provided system image.
- [ ] Test a small supported SIS/SISX and N-Gage app set.
- [ ] Measure Android performance and document unsupported titles.
- [ ] Decide whether the EKA2L1 renderer can be embedded or should run as a companion engine.
- [ ] Complete GPL/source-distribution and third-party licence review.

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
