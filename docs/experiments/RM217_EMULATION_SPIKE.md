# RM-217 genuine-firmware emulation spike

**Status:** research and host-contract foundation; no RM-217 firmware has been booted yet.  
**Reference date:** 5 November 2026.

## Objective

Run the Nokia 6300 / RM-217's own Series 40 firmware in a hardware emulator hosted by Android. The Android app supplies the host display, virtual controls, audio output, persistent storage and internet bridge. The emulated OS supplies its own standby screen, menu, gallery, built-in applications and Java ME application manager.

The first milestone does not require GSM radio, a real SIM, cellular registration, camera, Bluetooth RF or every handset feature. Peripheral stubs are acceptable only after firmware traces show that the missing device is not required for a stable boot or the selected applications.

## What can be done before the firmware is available

- Import extracted MCU/MCUSW, PPM and IMAGE/CNT components into private app storage.
- Validate BB5 FPSX container structure, file size, named image regions and data-block boundaries.
- Record SHA-256 identifiers and original file names without uploading any image.
- Prepare an OS-machine API independent of the Java ME application runtime.
- Define video-frame, audio-sample and key-down/key-up contracts for the Android host.
- Build the native Android/JNI and desktop diagnostic harness after the SoC requirements are known.
- Research the RM-217 hardware from service documentation and compare it with the RM-614 reference emulator.

The importer accepts **individual, already-extracted FPSX components**. It does not execute installer EXEs, flash a physical handset, bypass security, decrypt firmware or claim that selected files form a compatible set.

## Reference implementation: c3emu

Repository: https://github.com/androettop/c3emu  
Reviewed upstream commit: `9b0d3ae2da326e109428f64d8760ea823ee1b648`  
Licence: GPL-3.0-or-later.

c3emu demonstrates that the Series 40 firmware-emulation approach is practical for one specific handset: Nokia C3-00 / RM-614. It boots user-provided BB5 MCU firmware and optionally accepts PPM language data and factory content data. It already has:

- an ARM926EJ-S interpreter, with optional Unicorn acceleration on supported desktop builds;
- an FPSX container parser and BB5 image loader;
- emulated RAM, flash storage and peripheral access;
- a LCD GRAM/frame output;
- keypad press/release input;
- audio sample output;
- a C ABI in the WebAssembly host that demonstrates a narrow host/engine boundary.

This is not a ready-made RM-217 engine.

### Reusable concepts

| Component | Reuse assessment |
|---|---|
| ARM instruction interpreter | Candidate for reuse only after confirming the RM-217 core's instruction-set requirements |
| FPSX parser | Candidate for reuse after testing against actual RM-217 files |
| Host frame/audio/key API | Strong architectural reference |
| Sparse persistent storage | Candidate for adaptation |
| Run-slice scheduling | Candidate for adaptation |

### RM-614-specific code that cannot be assumed correct for RM-217

The c3emu source explicitly labels its address map as RM-614 / RAPIDO / RAP3G. Its boot layout, MMIO model, LCD controller registers, 320×240 panel, keypad matrix, timer/interrupt controller, I2C devices, PMIC behaviour, DSP HLE, storage hooks and fixed firmware entry points are tied to the C3-00 analysis.

The Nokia 6300 service manual identifies RM-217 as a BB5.0 design using RAPGSM, Retu/Tahvo power management, Ahne RF and 128 Mbit SDRAM + 256 Mbit NOR flash, with a 240×320 QVGA display. Similar family membership is not proof that its peripheral register map is compatible with RM-614.

## Planned machine boundary

```text
Android host
  ├── Device artwork / touch areas
  ├── LCD frame surface (240 × 320)
  ├── Canonical keypad events (press + release)
  ├── PCM audio sink
  ├── Private firmware library
  ├── Persistent emulated flash
  └── Network adapter (later)
          │ JNI / bounded run-slice API
          ▼
RM-217 machine backend
  ├── CPU core
  ├── memory map / MMU
  ├── interrupt and timer model
  ├── LCD controller
  ├── keypad scanner
  ├── PMIC / I2C / GPIO subset
  ├── flash storage controller
  └── optional DSP / absent-device stubs
          │
          ▼
User-supplied RM-217 MCU + PPM + IMAGE firmware set
          │
          ▼
Original Nokia Series 40 OS and its own Java ME environment
```

The operating-system machine and a MIDP/CLDC Java ME app runtime are distinct layers. First boot the firmware's own OS. Only then test its bundled Java ME manager and install a known-good MIDlet. Opera Mini is a later network integration test, not the initial boot criterion.

## Boot investigation gates

1. Inspect the actual MCU FPSX image and confirm its named images, base addresses and BB5 header.
2. Identify the first instruction/boot entry, required boot-info fields, memory map and expected CPU mode.
3. Identify the processor core from reliable RM-217 documentation or firmware evidence; do not infer it only from the RAPGSM marketing name.
4. Implement the minimum memory, timer, interrupt, LCD and keypad subset required to reach the first stable display.
5. Capture deterministic traces for every missing MMIO register and firmware abort. Add a peripheral only when evidence shows it is on the boot path.
6. Reach standby and navigate the original menu before spending time on media, networking or radio simulation.
7. Verify that Java applications launch through the phone's own AMS/MIDP stack.
8. Add an Android network bridge and test Opera Mini with its actual historical server/protocol dependencies.

## Explicit non-goals for the first RM-217 milestone

- GSM baseband/RF transmission or actual telephone calls.
- Real SIM authentication or network registration.
- Camera sensor, Bluetooth radio or FM tuner hardware.
- Flashing or writing firmware to a physical Nokia phone.
- Distributing proprietary firmware inside Git, CI artefacts, APKs or the public website.

## Licence and provenance

The c3emu reference is GPL-3.0-or-later. Its source has not been copied into this project yet. Any later incorporation must pin a revision, keep the complete licence and notices, include corresponding source where required and preserve dependency attributions.

The firmware importer stores user-provided components locally and verifies structure only. A user-selected image is not automatically cleared for copying assets into a public APK. Firmware and extracted assets remain outside source control and build artefacts unless a separate documented permission basis exists.
