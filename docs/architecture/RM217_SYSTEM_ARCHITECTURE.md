# Nokia 6300 RM-217 system architecture baseline

**Status:** research-backed working specification. This is a hardware model plan, not a claim that the current probe already emulates the complete phone.

## 1. Target identity

The Nokia 6300 RM-217 is a Series 40 handset on Nokia's BB5.0 baseband platform. It is not a Symbian phone and must not be approached as an EKA2/Symbian user-space emulator.

The RM-217/RM-222 service manual identifies these main blocks:

| Block | Nokia documentation | Emulation responsibility |
|---|---|---|
| Main baseband | RAPGSM, BB5.0 | Main MCU, internal peripherals, buses, clock/reset and interrupt routing |
| Energy management | Retu + Tahvo | Power rails, reset sequencing, charging and analogue/audio-related interfaces |
| RF | Ahne RF ASIC + front-end module | RF control boundary; full RF waveform emulation is a later phase |
| Combo memory | 128-Mbit SDRAM + 256-Mbit NOR | External RAM and non-volatile firmware storage |
| Bluetooth | BC4-ROM | Bluetooth controller boundary |
| FM radio | TEA5760 | I2C-controlled tuner boundary |
| Camera/HWA | STV0984N | Camera accelerator boundary |
| Display | QVGA, 240 × 320 | Display controller/framebuffer and panel output |

The board schematic identifies the combo-memory component as **K5G5629ATB-SF75** and labels the device "COMBO 256M NOR + 128M DDR DRAM". The service manual describes the memory as 256-Mbit NOR plus 128-Mbit SDRAM. These capacities are bits, not bytes:

- NOR: 256 Mbit = 32 MiB (0x02000000 bytes).
- SDRAM: 128 Mbit = 16 MiB (0x01000000 bytes).

The schematic also shows separate groups of RAPGSM signals for the external memory, CBUS, I2C, SIM, keypad/companion interfaces, USB and Bluetooth/audio paths. These are separate hardware interfaces and should become distinct bus/device models rather than one generic MMIO blob.

## 2. Processor model and confidence

The RM-217 service manual names RAPGSM as the main processor ASIC. It does not, in the extracted RM-217 pages currently available to this project, explicitly expose the internal CPU core designation.

Related Nokia service documentation for other RAPGSM-based BB5 handsets describes an ARM926 MCU subsystem together with a Lead3 PH3 DSP subsystem. This is a strong family-level architectural lead, but it is not yet a substitute for a RAPGSM/RM-217 silicon specification. Until a primary RM-217 source confirms it, record the CPU identity as **ARM926-class / likely ARM926EJ-S**, not as an independently confirmed RM-217 fact.

If confirmed, ARM926EJ-S means ARMv5TEJ, 32-bit ARM and 16-bit Thumb instruction sets, an MMU, separate instruction/data AHB interfaces, and instruction/data caches. The CPU model therefore needs correct CP15/MMU and exception behaviour; CPU instruction execution alone is not a complete platform.

The DSP is a separate processor subsystem. Treating DSP activity as unexplained ARM memory would be architecturally wrong. The initial implementation can use a clearly delimited DSP HLE/mailbox boundary, but it must preserve shared-memory and interrupt semantics as evidence becomes available.

## 3. Memory map: known facts vs observations

### 3.1 External combo memory

The external memory capacities above are documented by Nokia. Runtime NOLO/MCUSW traces also expose a 16-MiB SDRAM descriptor at:

0x10000000 .. 0x11000000

The exact base is therefore supported by the firmware's own hardware-configuration/region records, while the size independently matches the 128-Mbit device capacity.

The current probe now maps this SDRAM bank as one continuous 16-MiB RAM range. This replaces the previous behaviour where the CPU backend created sparse 4-KiB pages only after accesses. Optional experimental tag-4 ranges are still accepted for comparison, but are no longer required to create the documented/observed SDRAM window.

The 32-MiB NOR capacity is documented. The current candidate JEDEC response 0x00EC:0x2208 and the address-zero read-array implementation remain **diagnostic hypotheses**. The exact ID and chip command/timing behaviour for the ATB-SF75 variant still require a matching Samsung datasheet or reliable device trace. The current approximation does not implement erase/program algorithms, protection/status registers, timing, wait states, or the complete RAPGSM flash controller.

### 3.2 Internal and bootstrap address windows

NOLO/MCUSW traces use low addresses for the initial firmware/TOC path and a separate 0x0800xxxx workspace for boot state, descriptors and scratch records. This proves those addresses are used by the tested startup path, but does not by itself establish the complete physical size/aliasing of RAPGSM internal SRAM.

For now the probe retains its existing low-address bootstrap backing to avoid changing firmware loading and known scratch locations simultaneously. It is explicitly labelled provisional; it must be split into verified ROM, SRAM, aliases and reserved ranges as the chip-level map is recovered.

The former default 0x80000000..0x84000000 "reference high RAM" bank had no RM-217-specific source and has been removed.

### 3.3 Unknown addresses

An unmapped address is not automatically RAM. In particular, RAPGSM/Retu/Tahvo registers must not be implemented by creating writable zero-filled pages. The default probe now reports an unmapped bus access and stops. The former dynamic-page fallback is available only through the explicit --legacy-unmapped-as-ram diagnostic flag.

This policy follows the architecture used by mature system emulators: an address space decodes an access to a named memory region or a device handler; RAM, ROM, banks and MMIO are different mapped objects. MAME documents address-space dispatch and static address maps, and QEMU describes system emulation as a CPU, memory and device model. The Nokia 3310 DCT3 emulator is not an RM-217 map reference, but it provides a relevant project-structure example: CPU behind a bus interface; explicit flash state machine; distinct timer/IRQ, LCD, keypad, SIM and DSP modules.

## 4. Proposed machine boundaries

The implementation should converge on explicit, independently testable components:

- **CPU:** ARM926-class core only after confirming the core; CP15/MMU, ARM/Thumb state, exceptions, interrupt lines, memory attributes and cycle accounting.
- **Physical bus/address decoder:** typed ranges for RAM, ROM, aliases and MMIO; access width/alignment; unmapped-access diagnostics; no silent page creation.
- **Combo memory:** 16-MiB SDRAM and 32-MiB NOR with explicit capacity, address decode, NOR command state machine, erased contents and firmware loading.
- **RAPGSM:** MCU-side internal blocks, clock/reset, interrupt controller, GPIO, serial interfaces, CBUS/I2C and DSP mailbox/shared-memory path.
- **DSP:** independent execution/HLE boundary, shared memory, mailbox commands and interrupts.
- **Power/companion ASICs:** Retu and Tahvo with explicit power/reset and bus transactions.
- **External devices:** Ahne RF control boundary, display, keypad, SIM/MMC, Bluetooth, FM, camera and audio path.
- **Boot chain:** NOR/boot ROM boundary, NOLO, secure service calls, MCUSW image start and subsequent platform initialization.

The diagnostic executable can remain a probe, but device behaviour should migrate into reusable modules rather than accumulating as anonymous hooks in main.rs.

## 5. Evidence and emulator references

Primary target sources:

1. Nokia Customer Care, RM-217; RM-222 Service Manual, Issue 1 (2007), especially the system-module introduction, key-components table, RAPGSM and power-management descriptions. Public copy: https://usermanual.wiki/Document/nokia6300rm2176300brm222servicemanual34v10.1237121143.pdf
2. Nokia Customer Care, Nokia 6300 Schematics, board version v1ywa_32 (2006), especially the combo-memory component and RAPGSM signal groups. Public schematic source used in the investigation: https://www.s-manuals.com/pdf/phone/nokia/nokia_6300_schematics.pdf
3. ARM, ARM926EJ-S Technical Reference Manual, DDI 0198: https://support.arm.com/documentation/ddi0198/e
4. Related Nokia RAPGSM service documentation is used only to identify likely ARM926/Lead3 subsystem structure; it is not treated as exact RM-217 proof.

Emulator architecture references:

- QEMU system emulation overview: https://www.qemu.org/docs/master/about/
- MAME memory/address-space documentation: https://docs.mamedev.org/techspecs/memory.html
- Nokia 3310 DCT3 emulator (different chipset and memory map; architecture reference only): https://github.com/bialasik/nokia-3310-emulator
- c3emu (Nokia C3-00 RM-614; closest Series 40 reference, but its machine map and peripheral assumptions are RM-614-specific): https://github.com/androettop/c3emu
- EKA2L1 (Symbian emulator; not the platform of the Nokia 6300): https://github.com/EKA2L1/EKA2L1

## 6. Implementation gates

1. Establish a typed, explicit RM-217 memory map and keep unknown bus accesses visible.
2. Recover and model the first RAPGSM register blocks needed by NOLO: reset/clock/status and the boot/flash interface.
3. Replace readiness-bit test overrides with register state transitions backed by evidence.
4. Replace Secure Service 0x06 trampoline with an evidence-based ROM/service boundary.
5. Validate NOR ID, array decode, command set and timing against the exact memory device.
6. Implement Retu/Tahvo buses, clocks and interrupts; then DSP mailbox/shared memory.
7. Bring up display, keypad, SIM and audio independently, with tests for each device before claiming full phone boot.

A successful CPU-only probe run is not a successful handset boot. A phone boot claim requires the correct memory/peripheral models, a complete NOLO-to-MCUSW hand-off, and observable system-level progress beyond the early baseband initialization.
