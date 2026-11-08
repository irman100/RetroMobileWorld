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
- [x] Invert only the first two QWERTY row curves so outer keys sit higher than the fixed central pair. The third QWERTY row and fourth modifier row are unchanged by this correction; the pre-existing inward placement of Function/Ctrl hotspots is preserved.
- [x] Add Android key-down haptics with a short platform click effect and system-setting respect (physical-phone verification pending).
- [x] Add C3EmuBoot terminal error + native stats diagnostics for the remaining startup-splash interruption.
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


## C3-00 follow-up boundaries — 8 November 2026

- [x] Re-centre first/second-row key markers and expand their touch regions to match the physical keycaps; preserve rows three and four.
- [x] Add Android KEYBOARD_TAP diagnostics and display-vsync frame invalidation.
- [x] Add host-to-guest PMU RTC updates from Android local time/timezone, including time/timezone broadcasts (device display still reported incorrect; validation/fix pending).
- [x] Contain Rust panics inside the c3emu C ABI and preserve persistent flash for a manual guest reboot.
- [ ] Implement real guest Internet: first model SIM/modem/ISI and packet transport, then bridge to Android connectivity and verify DNS/TCP/TLS from a Series 40 application. This is not available in the pinned upstream core yet.


### Physical validation still open — 8 November 2026

- [x] Confirm with the user that the corrected first/second-row key positions now hit the intended physical keys; preserve them.
- [ ] Confirm perceptible short vibration on the user's phone after the direct Vibrator fallback.
- [ ] Confirm the date and time shown by Series 40 match Android; compare host clock and guest PMU RTC telemetry.
- [ ] Reproduce the guest stop/freeze from one network-dependent application and classify the exact native/guest stop reason before changing CPU/MMIO behavior.
- [ ] Implement a real SIM/modem + GPRS/ISI packet transport + Android network bridge. Current pinned c3emu has no packet backend, so Internet remains unsupported.
- [ ] Measure frame delivery and input latency on the physical device; host-side caching/coalescing is not proof of higher guest speed.


## Milestone 1A — C3-00 WLAN/Internet and direct emulator entry

- [x] Confirm from the RM-614 user guide that the original device supports both WLAN Internet and GPRS packet data.
- [x] Confirm from the RM-614 service schematic that WLAN has a separate enable/IRQ/SPI/SDIO controller interface.
- [x] Document the WLAN-first architecture and evaluate smoltcp, lwIP, Phonet and Android VpnService without confusing a TCP/IP stack with a wireless chipset.
- [x] Expose ISI TX/RX resource/message counters and recent messages plus initial SoC/peripheral register touches in native diagnostics.
- [x] Bypass the old landing screen and launch the C3-00 shell directly.
- [x] Add "Выбрать телефон…" to the three-dot menu; only implemented emulator is available.
- [x] Inspect exact RM-614 MCUSW 08.71: identify Broadcom S60 WLAN HAL/PDD, WlanSpiaImpl, WlanHpaImpl, explicit 802.11n gSPI selection, SDPCM/BDC/LMAC and IDB firmware-download diagnostics.
- [ ] Recover the exact WLAN IDB code/NVMEM payload and establish the chip/revision + BOB1.0M-a NVS-profile mapping.
- [ ] Implement the minimal firmware-observed WLAN controller state machine.
- [ ] Bridge guest IP packets through a consent-based Android network interface.
- [ ] Verify WLAN scan, association, IP assignment, DNS, TCP, HTTP and HTTPS.
- [ ] Download a real JAD/JAR from Series 40 Browser, validate the files and launch the MIDlet.
- [ ] After WLAN works, study GPRS/ISI and implement virtual SIM/modem packet data only from captured protocol evidence.


- [ ] Re-run Android CI after fixing the phone-picker showMessage call signature. Commit 8156a2d native JNI build succeeded; Kotlin compile failed on this single-argument API mismatch.


- [x] Make native MMIO diagnostics return the most recent 64 unique SoC/peripheral register addresses, so later WLAN/GPRS activity is visible instead of being buried by first-boot register touches.


- [x] Identify the RM-614 board's WLAN module as N6300 / Nokia part 4390090 / BOB1.0M-a, with separate W6000 antenna and Z6300 RF filter; document the shared SPI/SDIO host signals.
- [x] Confirm the firmware-selected WLAN host transport is Broadcom SPI/gSPI (`802.11n gSPI` and `WlanSpiaImpl`); board schematic also labels the WLAN SPI pins.
- [ ] Confirm the exact transceiver silicon/revision inside BOB1.0M-a and identify which BOB-specific NVS/IDB profile is selected; do not infer this from unrelated WMV metadata or another phone's BOB1.0M-b.


### RM-614 WLAN firmware analysis — 8 November 2026

- [x] Inspect the user's re-attached RM-614 MCUSW for the Broadcom S60 WLAN HAL, SPI/gSPI driver symbols, power/IRQ callbacks, SDPCM/BDC/LMAC protocol markers, device firmware version and IDB download stages.
- [ ] Recover the exact WLAN device IDB code and NVMEM sections; confirm silicon revision and the BOB1.0M-a profile mapping.
- [ ] Capture runtime SPI MMIO/chip-select/WLAN enable/CLK_REQ/IRQ activity during guest WLAN scan.
- [ ] Implement and test only the firmware-observed gSPI controller subset and guest packet path.
- [ ] Verify a real Series 40 Browser JAD/JAR download, integrity, MIDlet installation and launch.

The archive is analyzed locally only (SHA-256 `5239534da21345e5fc7bc1fbe5277f7d25a7798e9196742fcc77dc5d34445b11`); firmware binaries are not committed.


### Pinned c3emu SPI host and trace audit — 8 November 2026

- [x] Verify pinned upstream MMIO dispatch: no SPI-master, SDIO-host or WLAN target model; unknown registers fall back to sparse backing.
- [x] Verify GPIO/IRQ model and identify missing board-level WLAN enable/CLK_REQ/IRQ pin behavior.
- [x] Confirm existing `io_touches` entries are first-touch-only; identify upstream filtered `Machine::iolog(addr,len)` but note it is not exposed through Android JNI.
- [ ] Add opt-in bounded access-by-access MMIO tracing for selected host-peripheral address ranges.
- [ ] Map and implement the Nokia SoC SPI-master + WLAN board-pin/IRQ signals using captured MCUSW runtime accesses.
- [ ] Connect the firmware-observed Broadcom gSPI target model to the SoC SPI master.
- [ ] Bridge guest IP packets and complete the Series 40 Browser JAD/JAR acceptance test.


### Compact MMIO trace output — 8 November 2026

- [x] Split periodic `C3EmuPerf` metrics from a separate `C3EmuTrace` JSON record.
- [x] Emit at most 40 latest unique SoC/peripheral first-touch addresses with region, op, address, value and guest PC.
- [x] Cap stop text and test a maximum output size of 3,072 bytes.
- [x] Add the c3emu wrapper Rust test to Android CI before JNI ABI builds.
- [ ] Capture the compact output during the next device WLAN settings/refresh test.
- [ ] Add a later access-by-access MMIO range trace for repeated SPI reads/writes; first-touch diagnostics do not contain transaction sequences.


- [x] Fix the missing closing delimiter in the new Rust compact-MMIO test module found by CI run 489.
- [ ] Pass fresh push and PR CI, including the Rust size/retention test, JNI builds, Android unit tests and debug APK assembly.
