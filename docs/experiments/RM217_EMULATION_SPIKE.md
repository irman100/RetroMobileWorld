# RM-217 genuine-firmware emulation spike

**Status:** The RM-217 MCU firmware executes through the CPU-only NOLO-to-MCUSW diagnostic path and reaches BSE initialization, where the late allocator reproducibly stops on the alias-0 slot collision at 0xF0103C0A. The new trace analyzer checks the helper return, SUBS arithmetic and saved SP+0x38 alias value. The original Series 40 OS has not reached a stable display or menu; the PPM/VPL checksum discrepancy remains a separate firmware-set verification issue.  
**Reference date:** 7 November 2026.

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

The ZIP archive itself passes `zipfile.testzip()`. For the selected MCU, PPM and IMAGE entries, the inspector performs three distinct checks: ZIP central-directory CRC, a fresh CRC-32 over the decompressed component bytes, and the CRC-32 recorded in the selected VPL:

| Component | VPL CRC-32 | ZIP entry CRC-32 | Recalculated CRC-32 | Result |
|---|---:|---:|---:|---|
| MCU | `01C45DB7` | `01C45DB7` | `01C45DB7` | All match |
| PPM | `C585B71C` | `613D565F` | `613D565F` | ZIP self-consistent; VPL mismatch |
| IMAGE | `5008F91F` | `5008F91F` | `5008F91F` | All match |

This distinction matters. `zipfile.testzip()` validates the entries against their own ZIP CRCs; recalculating the PPM CRC gives the same `613D565F` as the ZIP entry. Therefore the PPM bytes are internally consistent with the ZIP archive as packaged. The discrepancy is specifically between those packaged PPM bytes and the selected VPL manifest. It is not evidence of accidental damage while this ZIP entry was read or decompressed. It remains possible that the PPM was already wrong, substituted or damaged before the ZIP was assembled, or that the VPL metadata is stale/wrong.

All six VPL files in the ZIP reference the same PPM name and expected CRC `C585B71C`. The archive contains only one matching PPM component. Its FPSX header identifies language-pack version 07.30 and its payload is structurally readable, but structural readability and the matching version string do not prove that it belongs to this exact package/variant. Treat this set as **not fully verified** and do not use the PPM as a trusted reference component until a matching file or independent explanation is obtained.

The MCU checksum does match the selected VPL, the ZIP entry CRC and the recalculated CRC. That lowers the likelihood of accidental corruption of the MCU bytes in this package, although CRC-32 is not a cryptographic authenticity proof and no trusted external SHA-256 baseline is currently available. The IMAGE has the same three-way match.

The current CPU/BSE probe selects and executes the MCU component only; it does not load or execute PPM during the investigated BSE initialization. Therefore this PPM mismatch does **not** explain the observed alias-0 collision or marker `0x3E` assertion. It may become relevant later when the full OS loads language resources or other PPM data, so it must be resolved before claiming a complete, verified Series 40 boot.


### Historical public reports (anecdotal)

Older Nokia repair-forum discussions contain user reports of `Corrupted Flash File` / PPM-package errors with RM-217 version 07.30, including `rm217__07.30.ppm_c`. These reports are not Nokia service documentation, are not the same verified archive/product code, and cannot establish that this particular PPM file is damaged. They do show that a 07.30 PPM package problem has been reported historically; keep this as a weak corroborating clue only:

- [Unlockers archive: Nokia 6300, “Corrupted flash file” for PPM_C 07.30](https://www.unlockers.ru/archive/index.php/t-15828.html)
- [Unlockers archive: Nokia 6300 firmware 07.30 PPM reinstall reports](https://www.unlockers.ru/archive/index.php/t-37017.html)

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


## ARM926 backend audit (2026-11-06)

The probe's pinned c3emu dependency is built with `default-features = false` and only
the `disasm` feature. Therefore `new_cpu()` selects c3emu's built-in
ARM926EJ-S / ARMv5TE interpreter, not its optional Unicorn backend. The optional
Unicorn path explicitly selects Unicorn's ARM Model_926.

The pinned interpreter source was reviewed directly:

- `cpu/mod.rs` describes the core as ARM926EJ-S / ARMv5TE.
- It implements CP15 state (SCTLR, TTBR, DACR, DFSR, IFSR, FAR, FCSE and context
  ID), L1/L2 MMU table walks, domains/AP permission checks, and a TLB.
- Its MMU walk handles section, large-page, small-page and tiny-page descriptors.
- It treats cache maintenance as simplified and reports SWI/BKPT/abort events
  through host interrupt hooks without performing the architectural exception
  vector entry itself.

The probe now installs an interrupt/exception observer that logs the backend
exception code, PC, CPSR and LR, then stops. It does not pretend that an ARM
exception vector was entered. The remaining CPU-core limitations include full
exception entry/banked-state transitions, IRQ/FIQ line timing, cache and
write-buffer behaviour, and cycle-accurate bus timing.

This confirms the CPU implementation being used; it does not independently prove
that the RM-217 RAPGSM silicon contains exactly the same ARM926 revision.


## NOR hand-off and BSE low-window overlap (2026-11-06)

The NOLO readiness-loop instruction was rechecked from its Thumb bytes. The
sequence at `0x00020DF8` loads a status word, executes `LSLS #31`, then
branches on Z. This tests **bit 0 of the original status word**, not bit 31.
The prior bit-31 interpretation was wrong. For the CPU-only comparison run,
the probe now uses the explicit diagnostic overrides
`--ready-bit=0x20032D80:0`, `--ready-bit=0x20032D20:0` and
`--ready-bit=0x20032D40:0`. These are test overrides, not a model of the
ASIC's real ready-state transitions.

With those overrides and the candidate NOR autoselect response
`0x00EC:0x2208`, NOLO reaches Secure Service 0x06 and launches MCUSW.
The ID response remains provisional for the exact K5G5629ATB-SF75 device;
this experiment does not establish a complete NOR-controller model.

### Current BSE assertion path

The most recent full trace reaches a non-empty BSE mapping table. It does not
fail at `0xF010431A` on this path: the conditional branch at
`0xF0104318` is taken and skips that call. The active assertion is
`0xF0103C0A`.

The mapper's first static-table pass is visible at
`0xF0104898`. Its table begins at `0xF0105AEC` and it calls
`0xF0103ECE`. The first mapping request is an identity mapping of
`0x00000000..0x00100000`. The mapper writes `0x3E` into the first
map slot at `0x10090000` (write at `0xF0103C3E`).

A later BSE region pass calls the same mapping helper from
`0xF01043A0` (return address `0xF01043A5`). At helper entry, the
observed arguments are:

- base `0x00000000`
- length `0x01600000` (22 MiB)
- destination/alias `0x00000000`
- descriptor context includes `0x0800382C`, the NOR descriptor record:
  type `0x24`, size `0x02000000`, base `0x00000000`,
  manufacturer `0x00EC` and device `0x2208`.

The first mapper slot is already non-zero (`0x3E`). The second call
reads it at `0xF0103C04`, sees that it is occupied, and calls the
assert helper at `0xF0103C0A`. This proves a conflict between the
initial 1-MiB identity mapping and a later 22-MiB mapping starting at the same
base. It does **not yet prove** whether the second mapping's base should be
different, whether the initial mapping is intended to be replaced/extended, or
whether an earlier hardware/boot configuration step should remove the overlap.

The adjacent descriptor at `0x08003820` contains type `0x10`,
base `0x00A00000` and size `0x01600000`. Its exact role in
producing the zero-based 22-MiB mapping is not yet established. The next
investigation is to trace how this record and the NOR descriptor are transformed
into the call arguments at `0xF01043A0`, then compare that with the
static low-window mapping table. No mapping values have been patched and the
assertion has not been suppressed.

This remains a CPU-only diagnostic run with synthetic readiness and NOR-ID
inputs plus the explicit legacy unmapped-as-RAM fallback. It is not a successful
phone boot or a verified complete RM-217 hardware model.

---

## Allocator alias derivation and validated trace checkpoint (2026-11-07)

The Type-8 scan and BSE alias observations recorded in the progress log establish the current failure mechanism more precisely than the earlier initial region-map report:

- The Type-8 record at `0x0800381C` contains size `0x00A00000` at `+0x08` and start `0x01600000` at `+0x0C`, describing occupied interval `[0x01600000, 0x02000000)`.
- The observed allocator subtracts that tail from candidate interval `[0, 0x02000000)`, leaving `[0, 0x01600000)`. Before the late mapper call, the saved alias input at `SP+0x38` is zero.
- Earlier mapper calls in the same trace include physical interval `[0x01600000, 0x02000000)` through alias `0xC1600000` (slot `0xC16`) and low interval `[0, 0xB000)` through alias `0xC0000000` (slot `0xC00`). These occupy different BSE slots.
- The static prelude's identity mapping of the first 1 MiB already marks slot 0 with `0x3E`. The late NOR allocator then requests alias 0 for a 22-MiB mapping and encounters that marker at `0x10090000`, entering the assertion at `0xF0103C0A`.

The observed range subtraction is not itself proven incorrect. The open question is why the helper/literal calculation supplies alias zero and whether the static prelude mapping and allocator free-range list are intended to share this address space. The raw descriptor pointer observed alongside a mapper call is one descriptor ahead of the arguments in the old trace; it must not be treated as the source of the current mapping without a new direct correlation.

The Windows trace runner now captures the helper entry/return and the adjacent Thumb arithmetic checkpoints. Its summarizer calculates the expected 32-bit `r4-r0` result, checks the post-`SUBS` register, and compares that result with the captured `SP+0x38` value before the mapper. Synthetic tests cover both matching and mismatching traces.

The updated diagnostic tooling and Android application build passed CI for checkpoint `558c35bb48e4cd2719146ce998f4d0668a23638c`:

- [RM-217 Boot Probe — Windows and Linux](https://github.com/irman100/RetroMobileWorld/actions/runs/42366177923)
- [Android CI — pull request](https://github.com/irman100/RetroMobileWorld/actions/runs/42366177892)
- [Android CI — push](https://github.com/irman100/RetroMobileWorld/actions/runs/42366175996)

The refreshed [Windows BSE trace kit](https://github.com/irman100/RetroMobileWorld/actions/runs/42366177923) is artifact `13571579203` (SHA-256 `49b79d8234405d1ee57f9032eef7176a8fd5018799c0ff96d1e4217df3b91ec6`). The tests and CI builds validate the diagnostic code only. No new guest-firmware trace was captured in this checkpoint; the BSE assertion remains open, no stable LCD frame is available, and Series 40 has not booted. Do not alter the firmware memory map or suppress the assertion until the new register trace explains the alias source.

The PPM/VPL CRC-32 mismatch is independent of this BSE allocator issue. The matching MCU and IMAGE checksums remain unchanged, and the PPM warning remains a separate integrity gate before a complete-OS claim.


## Latest diagnostic build and next trace (2026-11-07)

The last fully tested runtime/tooling checkpoint is 75ae7edc0f37eef4b8f79c6504bc4d28da072014. Subsequent commits only refresh these reports and correct the PowerShell example; they do not change the tested runtime or trace-analyzer code from checkpoint 558c35bb48e4cd2719146ce998f4d0668a23638c.

- [RM-217 Boot Probe run #42366626010](https://github.com/irman100/RetroMobileWorld/actions/runs/42366626010) — success on Windows and Linux; trace summarizer tests, Rust tests, release builds and Windows trace-kit assembly/upload passed.
- [Android CI pull-request run #42366626025](https://github.com/irman100/RetroMobileWorld/actions/runs/42366626025) — success.
- [Android CI push run #42366622554](https://github.com/irman100/RetroMobileWorld/actions/runs/42366622554) — success.

The newest Windows trace kit is artifact 13571708963 from run 42366626010 (SHA-256: ef99d059acab360556713f6b3693797510e53c30248a6acee704364680def20c; 1,299,370 bytes; expires 2026-11-21 11:06:08 UTC). It supersedes the earlier trace-kit artifact referenced in the prior progress checkpoint.

Run the kit against the same local RM-217 Phoenix ZIP and retain both the compact summary and full trace. The summary should show whether the helper's returned r0 survives the literal load, whether post-SUBS r0 equals (r4-r0), and whether SP+0x38 carries that same computed value into the BSE mapper. This is still a diagnostic experiment; no new guest-firmware trace has yet been captured and the Series 40 OS is not running.
## Physical memory, ARM MMU and BSE alias: the new trace (7 November 2026)

The newly supplied full trace contains 5,427 lines. It confirms that the earlier distinction between physical Uc::mem_read() and guest-virtual CPU loads remains important when ARM MMU is enabled, but it does not explain the current BSE collision:

- At the late mapper hook 0xF0103ECE, CP15 SCTLR is 0x0009117C; M bit 0 is clear. TTBR, DACR and FCSEIDR are zero.
- The slot-0 table read is observed as VA=PA=0x10090000.
- The mapper's BSE alias argument (r2) selects a slot by alias >> 20. It is a separate firmware-managed alias namespace, not the ARM MMU's CPU VA-to-PA translation.

The descriptor cluster distinguishes NOR (type 0x24, base 0, size 32 MiB) from SDRAM (type 0x04, base 0x10000000, size 16 MiB). The current conflicting pair is not a NOR-versus-SDRAM overlap: both mapper requests use physical base 0 and BSE alias 0.

The static prelude requests (physical=0, length=1 MiB, BSE alias=0); the late NOR allocator requests (physical=0, length=22 MiB, BSE alias=0). The first request writes marker 0x3E to slot 0x10090000; the second sees that marker and asserts. This is a repeated BSE alias-slot request with different range lengths.

The zero is now explained by the actual register flow. The caller loads r0 from literal 0xF0104424; the helper at 0xF0104976 returns 0xF0100000 unchanged in this execution; the subsequent LDR r4 reads the same literal 0xF0100000; and SUBS r0,r4,r0 correctly computes zero. The same value reaches SP+0x38 and mapper r2. No register corruption is indicated.

The remaining question is no longer why the arithmetic returns zero. It is why the static prelude's first MiB and the later NOR allocator's 22-MiB free prefix both claim the same BSE alias slot, and whether the mapper supports extending a same-origin mapping. The next step is to inspect the complete static table beginning at 0xF0105AEC, especially the entry before the observed raw cursor at 0xF0105AF8. Do not change the firmware map or suppress the assertion.
