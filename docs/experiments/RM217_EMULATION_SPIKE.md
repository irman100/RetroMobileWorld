# RM-217 genuine-firmware emulation spike

**Status:** partial NOLO startup is now executing in a local CPU-only probe and resolves the main MCUSW image; the full Series 40 OS has not yet reached a stable display or menu.  
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

### RM-217 first-stage boot stub (static disassembly)

The larger MCUSW segment was mapped in a temporary ELF at the candidate XIP base inferred from its own literals and disassembled as ARMv5/Thumb. This is static analysis of the supplied image; no firmware was executed.

At the BB5 header entry offset +0x3D0:
- ARM word 0xE24F4008 is SUB r4, pc, #8, which computes the current entry address. It is not a conventional vector-table LDR pc,[pc,#imm].
- The entry stub switches to System mode, loads literals identifying 0xF01003D0 and 0xF0100000, then checks an early global at image offset 0x164 (observed value zero in this image).
- At entry offset +0x38, it executes ADD r4, pc, #1 followed by BX r4, transitioning to Thumb code at candidate address 0xF0100410.
- The Thumb continuation loads SP from a literal 0x08004000, subtracts 0x1800, and stores marker 0x067308CA at 0x08002800. It preserves incoming r0-r3 and calls an early pre-initialisation routine at 0xF0104A28.
- The routine at 0xF0104A28 contains the log strings "BSE: bse_pre_init(...)" and "BSE: MMU switched on, R/O offset ...". Memory/MMU setup is therefore part of the early boot path; this is not merely a display entry point.
- Interworking helpers around 0xF0104EC0 and 0xF0104ED0 issue CP15 reads in loops after cache-related operations. Their exact platform effect still needs confirmation.

The reproducible inspector now selects the largest duplicate-named MCUSW region and reports these candidate addresses and stack values. Unit tests cover duplicate-region selection and entry-literal calculations. The candidate XIP mapping and entry state must still be verified in an executing machine.

**Consequence for c3emu:** its loader's fixed RM-614 reset-vector decoding and image mapping are not reusable unchanged. The ARM interpreter remains a promising candidate, but an RM-217-specific loader must map the firmware at the observed alias, supply the correct handoff registers, and model early memory/CP15 operations before later peripherals can be assessed.


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

## Dynamic NOLO startup experiment (5 November 2026)

The first executable experiment now starts the archive's real NOLO region at its BB5 entry point (0x000206D0) using the ARM926 CPU backend as a CPU-only probe. This does not yet emulate the RM-217 peripherals or prove that the 6300 uses the same peripheral map as the C3-00.

### Confirmed progress

- NOLO executes from the firmware region at flash address 0x00020300; the entry offset is 0x3D0.
- Its image lookup reads the TOC base from global 0x080006A0. NOLO initializes this value to 0x00000400.
- TOC records are 0x20 bytes long, the image name starts at record offset 0x14, and an end record is marked by 0xFF in the first byte of the name.
- The probe previously wrote the records at 0x08006400; NOLO did not scan that address. Writing the same records at 0x00000400, with relative offsets based on that TOC address, makes NOLO find MCUSW at 0xF0100000. The firmware then prints both 'Image found at address' and 'BB5 header at address' for that image.
- This TOC correction is covered by a unit test which resolves each candidate record back to its intended load address.
- The diagnostic BSE experiments require memory tags 0, 4 and 5 before the MCUSW early initialisation passes its first memory-map checks. Their current base/size values remain experimental candidates, not a validated RM-217 physical memory map.
- NOLO's next observed call is a BLX to 0xFFFF0808. The descriptor at 0x00023480 contains the 12-byte secure-service identifier AB 0F BD 96 AF 2A C2 71 D2 62 64 AF and service number 0x0006. The call site at 0x00022168 proceeds to the 'Starting MCUSW...' message without testing the returned r0.

### Current ROM-service boundary

The probe now treats 0xFFFF0808 as an explicit ROM service entry rather than mapping the entire 0xFFFFxxxx area as zero-filled memory. The opt-in switch --hle-secure-service6 installs a tiny Thumb return stub for only the verified service-6 descriptor: MOVS r0,#0; BX LR. This follows the same conservative interface-level assumption used by the c3emu reference's generic secure-service HLE, but the internal implementation of RM-217 service 6 is still unknown. Do not treat this as a hardware implementation or proof of secure-image validation.

Any other fetch from the high ROM window is stopped and reported with its address and caller rather than executing zero-filled memory. Diagnostic --ready-bit overrides also remain opt-in test aids; they are not part of the normal machine model.

### Next boot gates

1. Run NOLO with the opt-in service-6 boundary and inspect the next ROM service or peripheral access.
2. Identify the MCUSW hand-off contract and determine whether NOLO branches to its entry or continues in its own loader.
3. Replace the provisional RAM tags and ready-bit overrides with an evidence-based RM-217 memory/peripheral model.
4. Reach a stable original Series 40 display before starting Android UI integration or Java ME networking.

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

## BSE region-map failure and follow-up trace (2026-11-05)

The current NOLO-to-MCUSW probe reaches MCUSW BSE and then reports an assertion at
`0xF010431A`. At the failure snapshot, the region-map workspace around
`0x080032B0` is zero-filled. This is an observed symptom, not yet a proven
root cause: the snapshot alone does not tell us whether BSE never received its
input, rejected it, skipped the builder, or cleared the result.

A source review of the probe found two different hand-off paths:

- Direct `--entry=mcusw` invokes `install_rm217_handoff`, writes the probe's
  boot-tag list, and puts its address in `r2`. Tag types `0`, `4` and `5`
  remain experimental candidates; their RM-217 physical meaning is unverified.
- `--entry=nolo --hle-secure-service6-launch` does not inject that MCUSW tag
  list. At Secure Service `0x06`, the diagnostic trampoline restores
  `r0-r3` from the NOLO service-call stack and branches to the validated BB5
  entry. Therefore the actual saved register values—especially `r2`—must be
  captured before changing the hand-off.

The pinned c3emu reference implementation (`bootinfo.rs`, revision
`9b0d3ae2da326e109428f64d8760ea823ee1b648`) creates only power-on tag
`0x0D`, image-table tag `0x0A`, and the end marker for its RM-614 OS setup.
It does **not** validate RM-217 tags `0`, `4` or `5`; those tags must not be
treated as a confirmed contract merely because c3emu supplies the CPU core.

The probe now has opt-in `--trace-bse-memory` instrumentation. It records reads
of the boot-tag area and reads/writes in the BSE region-record, region-map,
descriptor and target workspaces. Pair it with
`--watch-pc=0xF010431A` to capture the assertion-site registers, code bytes,
`r0`, `r2` and stack. The trace is observational only: it does not fabricate
region IDs, RAM bases or sizes.

Interpretation gates for the next run:

1. No boot-tag reads, or an invalid `r2` at the hand-off, points to the NOLO
   service-6 register/stack contract.
2. Boot tags are read but no BSE records are written: inspect the tag parser,
   expected tag types and allocator prerequisites.
3. BSE records are written but the map window is never written: inspect the
   region-selection/build routine and its validation branches.
4. The map window is written and later zeroed: trace the clearing write's PC
   before considering a memory-model change.

The PPM CRC discrepancy remains a separate archive-integrity warning. This
CPU probe selects and executes the MCU component; the PPM is not used by this
specific BSE memory-map experiment, so the PPM mismatch does not explain the
observed zero-filled map table.


## Hardware-map correction and architecture baseline (2026-11-06)

A new architecture baseline is recorded in `docs/architecture/RM217_SYSTEM_ARCHITECTURE.md`.
It separates facts from family-level inferences and lays out the eventual RAPGSM,
DSP, Retu/Tahvo, combo-memory, bus and external-device boundaries.

The Nokia RM-217/RM-222 service manual identifies RAPGSM, Retu, Tahvo, Ahne and
a combo memory of 128-Mbit SDRAM plus 256-Mbit NOR. The board schematic identifies
the memory device as K5G5629ATB-SF75 and labels it 256M NOR + 128M DDR DRAM.
This converts to 16 MiB of SDRAM and 32 MiB of NOR.

The firmware itself exposes the SDRAM descriptor at
`0x10000000..0x11000000`. The probe now maps that 16-MiB window continuously
from startup instead of relying on sparse pages created by access faults.
Optional experimental tag-4 ranges remain available for comparison.

The previous speculative `0x80000000..0x84000000` reference RAM mapping was
removed. The existing broad low-address bootstrap backing remains provisional
because NOLO/MCUSW currently use both low firmware/TOC addresses and a separate
`0x0800xxxx` scratch/descriptor workspace; its exact physical RAM/ROM partition
still needs a verified RAPGSM memory map.

Most importantly, unknown memory accesses no longer silently create writable
zero-filled pages by default. They are reported as unmapped bus faults and stop
the probe. The previous behaviour is retained only through
`--legacy-unmapped-as-ram` for controlled A/B comparison with older traces.
This makes missing ASIC/MMIO models visible rather than accidentally treating
registers as RAM.

This change does not claim a successful phone boot. It intentionally exposes
the next missing RAPGSM peripheral decoder. The next implementation phase is
to recover and model the earliest NOLO clock/reset/status/flash register blocks,
then Retu/Tahvo and the interrupt/bus behaviour required by the real boot chain.

CPU identification remains qualified: related RAPGSM service documentation
describes an ARM926 MCU and Lead3 PH3 DSP subsystem, but the currently reviewed
RM-217 service-manual pages identify RAPGSM without independently naming the
CPU core. Treat ARM926EJ-S as the working family-level hypothesis until a
primary RAPGSM/RM-217 source confirms it. Likewise, the experimental NOR ID
`0x00EC:0x2208` and read-array implementation are not yet a verified
K5G5629ATB-SF75 datasheet model.
