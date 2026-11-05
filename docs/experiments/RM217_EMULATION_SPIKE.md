# RM-217 genuine-firmware emulation spike

**Status:** research and host-contract foundation; no RM-217 firmware has been booted yet.  
**Reference date:** 5 November 2026.

## Objective

Run the Nokia 6300 / RM-217's own Series 40 firmware in a hardware emulator hosted by Android. The Android app supplies the host display, virtual controls, audio output, persistent storage and internet bridge. The emulated OS supplies its own standby screen, menu, gallery, built-in applications and Java ME application manager.

The first milestone does not require GSM radio, a real SIM, cellular registration, camera, Bluetooth RF or every handset feature. Peripheral stubs are acceptable only after firmware traces show that the missing device is not required for a stable boot or the selected applications.

## Supplied firmware: RM-217 07.30

The uploaded archive `RM-217.zip` was inspected locally without executing any installer or writing data back to a phone.

| Item | Selected result |
|---|---|
| Product code | 0537625 |
| VPL | `RM217_0537625_07.30_018.vpl` |
| Variant | RM-217 6300 EURO-C TR Turkey, Latin, black-silver |
| Software version | 07.30 |
| MCU | `rm217__07.30.mcusw`, 15,204,164 bytes |
| PPM | `rm217__07.30.ppm_c`, 4,525,730 bytes |
| Factory IMAGE | `rm217__07.30.image_c_tr`, 3,674,450 bytes |

### SHA-256 inventory

| Component | SHA-256 |
|---|---|
| MCU | `80172266f517180ace7a0065d0167619c174ec3fc1aa181fdc9b9f6fc0c2e17d` |
| PPM | `166ee371be01e8fa070a60202bae085171a169e970e73e782e8e5730ac99276d` |
| IMAGE | `15f2a47216bd7deae8223e62d2579005edd9b0f26f244f469aa269c2f61b818b` |

### Manifest integrity result

The ZIP archive itself passes `zipfile.testzip()`. The selected VPL CRC-32 matches the ZIP CRC and calculated CRC for MCU and IMAGE, but not for PPM:

| Component | VPL CRC-32 | Actual/ZIP CRC-32 | Result |
|---|---:|---:|---|
| MCU | `01C45DB7` | `01C45DB7` | Match |
| PPM | `C585B71C` | `613D565F` | **Mismatch** |
| IMAGE | `5008F91F` | `5008F91F` | Match |

All six VPL files in the ZIP reference the same PPM name and expected CRC `C585B71C`. The archive contains only one matching PPM component. Its FPSX header identifies language-pack version 07.30 and its payload is structurally readable, but this does not resolve the manifest checksum discrepancy. Treat this set as **not fully verified**; do not use it as the reference boot set until a matching PPM is found or the checksum rule is independently explained.

### FPSX layout findings

| Component | Header / TLVs | Blocks | Named/flash data |
|---|---:|---:|---|
| MCU | 17,719 bytes / 22 TLVs | 944 | 22 regions |
| PPM | 161 bytes / 14 TLVs | 276 | raw region at `0x01080000`, 4,520,320 bytes |
| IMAGE | 173 bytes / 15 TLVs | 224 | raw region at `0x01600000`, 3,670,016 bytes |

The MCU contains **two named MCUSW regions** at flash address `0x00320000`:
- a 1,024-byte header segment;
- a 13,656,960-byte main segment (835 blocks).

Both carry a BB5 header with `+0x1C = 0x3D0` and `+0x148 = 0x3D0`. At the pointed-to offset, the first ARM word is `0xE24F4008` (bytes `08 40 4F E2`, a PC-relative `SUB` instruction), not the `LDR pc,[pc,#imm]` reset-vector instruction assumed by c3emu's RM-614 boot helper. The larger segment also contains literals `0xF01003D0` and `0xF0100000` at entry offsets `+0x74` and `+0x78`; these are clues for an RM-217 XIP/flash mapping, not yet a complete memory-map proof.

The two MCUSW regions share a name. c3emu's current `Fpsx::image("MCUSW")` returns the first matching region, which is the 1,024-byte segment. A direct call to the existing RM-614 loader would therefore select the wrong segment even before the reset-entry mismatch is reached.

### Factory Java content

The IMAGE contains directory entries `OPERA_~1JAD` and `OPERA_~1JAR`, and a MIDlet manifest record for:
- Opera Mini 4.0.9;
- MIDP 2.0 / CLDC 1.0;
- JAR size 207,413 bytes;
- JAR URL `opera_hifi_nokia_s40_6-all.jar`;
- vendor Opera Software ASA.

This is strong evidence that the factory content image includes the intended Opera Mini app. It has not yet been installed or launched in the emulator.

### Reproducible inspection

The repository contains `scripts/inspect_rm217_firmware.py`. It reads the archive in place, validates ZIP/FPSX structure, resolves a product-code-specific VPL, compares CRC-32 values, reports named regions and prints selected MIDlet metadata. It does not extract firmware files or run executables.

The importer accepts either the complete RM-217 Phoenix ZIP or individual extracted components. It does not execute installer EXEs, flash a physical handset, bypass security, decrypt firmware, or automatically declare a component set boot-compatible.

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

The firmware importer stores user-provided components locally and verifies structure only. The current 07.30 archive's PPM CRC discrepancy is recorded in this spike report; the Android import flow currently does not block storage of that component, but no boot path uses it yet. A user-selected image is not automatically cleared for copying assets into a public APK. Firmware and extracted assets remain outside source control and build artefacts unless a separate documented permission basis exists.
