# Implementation status

Snapshot: 8 November 2026.

## Implemented

- Android application entry point and the Nokia 6300 / RM-217 firmware-intake shell.
- Local-only RM-217 Phoenix ZIP intake with MCU, PPM and IMAGE component selection.
- FPSX structural checks, matching VPL manifest lookup and CRC-32 reporting.
- Separate PhoneSystemEngine contract for CPU/SoC/firmware emulation, video frames, PCM audio and key down/up events.
- Desktop RM-217 boot-probe tooling and trace summarizer tests.
- Experimental Nokia C3-00 / RM-614 engine integration:
  - native Rust crate links the pinned upstream c3emu-web C ABI;
  - JNI methods are serialized on one native worker thread because upstream c3emu keeps the emulator session in thread-local state;
  - Android engine implements bounded execution slices, 320×240 RGBA LCD frame transfer, stereo 44.1-kHz PCM drain and C3-00 keyboard matrix input;
  - flash-backed C: storage chunks are persisted in app-private storage;
  - separate C3-00 activity supports Phoenix ZIP import or individual RM-614 MCUSW / PPM / IMAGE files;
  - selected front-facing C3-00 profile asset is packaged from the pinned repository blob with a build-time integrity check;
  - FPSX preflight no longer rejects a u32 address just because address + payload length crosses the unsigned 32-bit boundary; actual file-boundary checks remain.
- Android CI run 42457493795 passed the C3-00 native build for ARM64, ARMv7 and x86_64, Android unit tests, APK assembly and artifact upload.
- Windows SDK discovery now supports ANDROID_SDK_ROOT, ANDROID_HOME, local.properties and D:\Android\Sdk.
- A safe local build/install PowerShell entry point was added for physical Android phones.
- Android app version is advanced to 0.4.1-c3emu-rm614 (versionCode 5) for the next local validation build.
- Full CI for checkpoint 1c63d991738e2b209000271ea86dfb170894f508 passed in push run 42458429582 and PR run 42458434131; the version 0.4.1 debug APK artifact is 13608921375.
- Firmware remains user-supplied and is not included in source control or the APK.

## C3-00 validation boundary

The user has confirmed that the selected C3-00 shell image is visible in the installed GitHub build. This confirms the shell asset is packaged and rendered; it does not prove that RM-614 Series 40 boots.

The FPSX error was traced to an over-restrictive Kotlin preflight check, not to a demonstrated failure in the native c3emu parser. The upstream parser reads the flash target as u32 and checks payload availability within the container; it does not reject solely because start address plus length crosses 0xFFFFFFFF. A dedicated regression test covers this boundary and passed CI.

The corrected APK still needs to be built from the latest local branch and installed on the physical phone. The user's ADB installation path is D:\Android\Sdk\platform-tools\adb.exe (ADB 1.0.41, platform-tools 37.0.0-14910828). The USB device serial/model has not yet been captured in this project log.

Required next device validation:
1. Enable Developer options and USB debugging on the phone, unlock it, and authorize this PC's RSA key.
2. Run scripts/build-install-c3emu-phone.ps1 from the repository root. It updates the C3-00 branch safely, builds and tests locally, then installs the local APK using an explicit physical-device serial. It excludes emulator serials.
3. Open Nokia C3-00 · Open emulator.
4. Import the same user-supplied RM-614 Phoenix ZIP and confirm the 32-bit FPSX error is gone.
5. Start Series 40 and verify a real guest LCD frame/home screen and key response.
6. Then test audio and persistent C: storage separately.

A successful import is not a successful boot. A successful boot must be demonstrated by a real guest OS frame and input response.

The Android backend uses c3emu's own RM-614 hardware model. It must not be selected for Nokia 6300 / RM-217 firmware.

## C3-00 UI follow-up

- The exact front-facing shell image `docs/75-205-263-02.png` (968×1920) is the only geometry reference. Do not replace it with another photo.
- The LCD source is 320×240 and is stretched to the full cutout using exclusive photo edges (82,289)–(885,899). A physical-phone screenshot check is still required.
- Touch circles have a near-transparent fill and low-alpha outline (fill alpha 2/255, stroke alpha 24/255); marker centres and touch hit centres are identical.
- Physical matrix indices are centralized in `C3KeyMatrix.kt` and cross-checked against pinned upstream c3emu. Contacts=38, Messaging=52, Function=50, Ctrl=27; D-pad=56–60; soft keys=29/43; Call=30; End=255.
- `C3KeyPressLedger` handles key-down/key-up, pointer movement, multi-touch ref-counting and cancel. Unit tests cover those behaviours.
- Lowest QWERTY row and bottom modifier keycap centres follow the photographed curved separators, recorded in `docs/experiments/C3EMU_PROGRESS_LOG.md`.
- These are source-photo calibrations and unit-level input-path checks. Real phone alignment and physical touch response are still pending.

## C3-00 firmware intake and host-loop tuning

- `.image_*` uses the RM-614 sparse FAT16 content-image parser, not the generic FPSX parser. The parser validates the boot sector and multi-block boundaries; actual import of the user's IMAGE file on the phone is not yet confirmed.
- MCUSW, PPM and IMAGE selection is matched by software release and shared regional suffix (MH1 with MH1; MR with MR). A saved C: disk is preserved; a new IMAGE only seeds C: when no saved disk exists.
- JNI now reads imported app-private firmware paths directly into c3emu-owned native buffers to avoid several copies of large IMAGE files.
- Host loop uses 16 ms native slices, 15 ms LCD frame cadence and a 20 ms WFI wait cap, matching upstream worker timing. Blocking audio writes run on a separate bounded worker; dirty flash storage is throttled to 1 second with forced flush on pause/stop/failure.
- These are host-overhead optimizations, not a measured increase beyond upstream real-time emulation. Physical-device FPS/latency measurement remains pending.
- CI uses Gradle 9.6.0 through `gradle/actions/setup-gradle@v6`, caches the distribution and dependencies, and retries transient network 5xx failures. The Kotlin compiler module is exclusively pinned to WSO2 with POM/artifact metadata to avoid GitHub Releases 504 redirects; other dependencies retain normal repositories.
- **Latest head `4c00850ea09fb681257d303d6e01f03ef2a763aa`: both push run `42472380211` and PR run `42472383487` passed.** Native builds, Android unit tests, APK assembly and upload all succeeded.
- Current artifact: `13614395927`, ZIP SHA-256 `c3d85b501a1c1483707c029df3d8e2c8b2112b9caa9001334782aa9b3198b42e`; expires 22 November 2026 10:43 UTC. CI success is not a physical-device validation.

## RM-217 verification

The inspected Nokia 6300 / RM-217 07.30 archive previously showed:
- MCU CRC matches the selected VPL;
- IMAGE CRC matches the selected VPL;
- PPM CRC mismatch: expected C585B71C, actual 613D565F.

The app retains structurally valid files but must not label this set fully verified or bootable.

The RM-217 desktop probe still does not boot the Nokia 6300 operating system. Its current investigation concerns the late BSE alias collision, NOR allocator and reset path. No C3-00 hardware map or firmware assumptions may be copied into RM-217.

## Known scope limits

- c3emu documents no SIM or cellular network emulation.
- Physical handset flashing is not implemented; the Android application emulates the guest environment.
- Android frame and audio delivery need measurement on the test phone.
- C3-00 QWERTY support is via an on-screen key matrix; device-specific key mapping still needs end-to-end verification.
- No successful RM-217 OS boot has been achieved.


## C3-00 — first confirmed physical Series 40 boot and responsiveness follow-up (8 November 2026)

The user has confirmed that the RM-614 Phoenix ZIP imports and launches the full Series 40 firmware on the physical phone. The user can navigate the firmware menus and attempt to launch applications. Some touch keys still appear to miss taps and transitions are sluggish; full input/performance verification is not complete.

New responsiveness code at `51e60a11dc5b1c4be1eb20cff0324c26ce81c086`:
- `C3MatrixInputQueue` applies input edges immediately before native CPU slices and guarantees a quick tap is visible for at least one complete slice.
- Touch hit areas are larger without changing the faint visible marker diameter/alpha.
- c3emu runs in a continuous single-owner native worker rather than bouncing through the Activity main thread after every slice.
- LCD frames are coalesced to avoid a backlog of stale images.
- `C3EmuPerf` Logcat telemetry reports cycle rate, delivered FPS, average/max cycle time and upstream c3emu stats every two seconds.

**CI run 42475334037 on 51e60a1: SUCCESS** — native build for arm64-v8a/armeabi-v7a/x86_64, Android unit tests (including 4 C3MatrixInputQueue tests), debug APK assembly and upload.

Artifact ID: `13615556057`; ZIP SHA-256: `9e6cf0f7eeb7cbdf55611a97576cbd9cc1820e4a546ebb743b93d3c496ffb531`; APK SHA-256: `d5ccb5e691afd1b9c0ae886c8fd314bb19bd14cc0bb3d18775f0fe063dbc0a30`. CI run: https://github.com/irman100/RetroMobileWorld/actions/runs/42475334037.

The new build has not yet been installed and measured on the physical phone. Do not claim a measured speed/FPS gain until `C3EmuPerf` logs and real key tests are captured. Preserve existing app-private storage and C: data.


### C3-00 LCD rendering responsiveness follow-up (8 November 2026)

The per-frame RGBA-to-ARGB conversion and Bitmap update have been moved off the Android UI/input thread to a dedicated single-thread frame-render executor. Frame coalescing remains in place, so stale LCD frames do not queue up. The final Bitmap update is synchronized against drawing and requests redraw with `postInvalidate()`. Physical FPS/input latency still require measurement.
