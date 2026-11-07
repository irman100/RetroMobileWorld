# RM-217 emulator and hardware reuse research

**Reviewed:** 7 November 2026  
**Target:** Nokia 6300 RM-217, firmware 07.30  
**Decision:** reuse a shared ARM926 execution core; implement an RM-217-specific SoC and device layer. Do not substitute a different Nokia board model.

## Executive result

The most useful existing project is [androettop/c3emu](https://github.com/androettop/c3emu), a GPL-3.0-or-later emulator for Nokia C3-00 / RM-614 that reports booting the phone's own Series 40 firmware to its home screen. Its CPU is an ARM926EJ-S interpreter, and it includes an ARM MMU walker, FPSX parsing, display/keypad/audio/flash/PMU models.

Retro Mobile World already pins c3emu at commit `9b0d3ae2da326e109428f64d8760ea823ee1b648` in `engine/rm217-boot-probe/Cargo.toml`. The probe directly reuses:
- `c3emu::uc::new_cpu` and the ARM926 CPU API;
- `c3emu::mmu` for guest virtual/physical translation and virtual reads;
- `c3emu::fpsx` for BB5 FPSX containers.

BB5 entry/header consistency checks used by the probe are implemented locally; the probe does not depend on c3emu's complete RM-614 machine. It intentionally does **not** instantiate `c3emu::machine::Machine`, its RM-614 address map, or its device models. This is the correct boundary: the CPU architecture is shared, but the board/SoC is not.

## Existing emulator projects

| Project | Device/platform actually modelled | Useful contribution | Why it is not an RM-217 drop-in |
|---|---|---|---|
| [c3emu](https://github.com/androettop/c3emu) | Nokia C3-00 / RM-614, Series 40, ARM926EJ-S | ARM interpreter, MMU, FPSX parsing, deterministic scheduler patterns, framebuffer/key/audio API, device-model examples | Its machine is RAPIDO/RAP3G, with a different address map, 64-MiB SDRAM window, 320x240 LCD path and model-specific peripherals. RM-217 uses RAPGSM, 16-MiB DDR SDRAM, 240x320 LCD and RETU/TAHVO on CBUS. |
| [Nokia DCT3 Emulator](https://github.com/djr-747/nokia-dct3-emulator) | Nokia 3310/3210/3410/etc., DCT3 platform | Examples of per-model firmware profiles, bus/event tracing, real-firmware preservation, a headless test harness and separating MCU/DSP/device models | DCT3 MAD2/CCONT/C54x hardware is a different generation from RM-217 BB5/RAPGSM. The repository is GPL-2.0; use it as an architectural reference, not as source to copy into this GPL-3.0-only project without a licensing review. |
| [EmuGII](https://github.com/ExistOS-Team/EmuGII) | HP 39gII calculator, QEMU STMP3770 machine, ARM926EJ-S | Demonstrates a board-specific QEMU machine, NAND/GPMI+BCH, DMA, LCDIF, GPIO/keypad, RTC/watchdog and audio devices; useful example of how to structure an ARM926 SoC | CPU family is similar, but the STMP3770 board and calculator firmware are unrelated. Reuse the machine-design approach, not its memory map or peripherals. README states GPL-2-or-later, so review license compatibility before importing code. |
| [FreeJ2ME](https://github.com/hex007/freej2me) | Java ME application runtime | Possible later runtime for JAR/JAD application compatibility and test fixtures | It does not boot MCUSW/NOLO, implement the Nokia kernel or emulate RM-217 hardware. It is not a replacement for the phone OS engine. |
| [QEMU ARM system emulation](https://www.qemu.org/docs/master/system/target-arm.html) | Generic ARM CPU and many board-specific machine models | Alternative CPU/board infrastructure if we later need a mature system emulator framework | The guest expects its own board, memory map, boot ROM and devices. A generic ARM or `virt` board is not a substitute for RAPGSM/RM-217. |

No open-source, complete RM-217-specific Series 40 emulator was identified in this survey. c3emu is the closest BB5/Series 40 reference found, but it targets RM-614 rather than RM-217.

## RM-217 hardware facts now supported by external material

### Processor and major devices

The Nokia RM-217/RM-222 service manual describes a BB5.0 design with RAPGSM as the main processor, RETU and TAHVO as the two power-management ASICs, Ahne RF, and a combo memory package containing 128-Mbit SDRAM plus 256-Mbit NOR. It lists a QVGA 240x320 display. See the [RM-217/RM-222 service manual](https://manuals.plus/m/97f91f5d5740e2dd1c95f58fcc0482693bf8eef843b4259dfb3054195898f21a).

The [RM-217/RM-222 service schematics, page 5](https://usermanual.wiki/Document/nokia6300rm2176300brm222serviceschematicsv20.673614552.pdf) identify the combo package as `K5G5629ATB-SF75`, label it “COMBO 256M NOR + 128M DDR DRAM”, and show RAPGSM connections to the memory. The same schematics label `CBUS(3:0)` between RAPGSM and the RETU/TAHVO section. I2C(1:0) is shown as a separate bus. Therefore, an I2C-only Retu/Tahvo model borrowed from a different Nokia platform would be the wrong transport assumption for this board.

### NOR identity and firmware partition offsets

Two historical, model-specific BB5 service-tool readouts for Nokia RM-217 report:
- flash ID `00EC:2208`;
- Samsung `K8F5615ETM`, 256 Mbit;
- 16-bit transmission mode.

Sources: [GSMForum RM-217 service readout](https://gsmforum.ru/threads/nokia-6300-contact-service.99432/) and [Unlockers RM-217 07.30 flash log](https://www.unlockers.ru/threads/30650-nokia-6300-%D0%BF%D1%80%D0%BE%D1%88%D0%B8%D0%B2%D0%BA%D0%B0). These are historical service-tool records, not a primary Samsung datasheet; the profile labels the JEDEC ID as service-observed, not yet independently datasheet-verified.

The same flashing logs expose the 32-MiB physical NOR layout:
- MCU/boot/system starts at `0x00000000` and the PPM erase region begins at `0x01080000`;
- PPM interval `[0x01080000, 0x01600000)`;
- IMAGE/CNT starts at `0x01600000` and the reported content range ends at `0x01FDFFFF`;
- service logs report a 128-KiB CNT sector-size requirement, while the early MCU erase list includes smaller boot/configuration segments.

This aligns with the live type-8 allocator record at `[0x01600000, 0x02000000)`: it reserves the IMAGE/CNT tail of the 32-MiB NOR window plus the final 128 KiB not included in the reported IMAGE write range. It supports the physical NOR range accounting, but does not resolve why the late NOR allocator reuses BSE alias zero.

A Samsung K8F5615ETM part listing describes a 16M x 16, 100-ns flash with top boot block, Common Flash Interface (CFI) and Command User Interface (CUI). A competitive cross-reference describes the family as 256-Mbit, x16, boot-sector / 16-bank. The exact K8F56 datasheet search surfaced a family specification but not yet an independently verified copy of the exact ETM variant. Do **not** infer uniform erase-block geometry or implement the full command state machine solely from these secondary summaries.

## Reuse and implementation plan

1. **Keep c3emu's CPU/MMU/FPSX layer.** The dependency is already pinned and built by CI. Keep `default-features=false`; do not enable the optional Unicorn feature for redistribution, because c3emu documents that combination as GPLv2-only and incompatible with its GPLv3 distribution.
2. **Keep RM-217 board state separate.** Build an `Rm217Machine` around the shared CPU API. Own the RM-217 memory map, reset/IRQ controller, clocks/timers, RAPGSM peripheral windows, flash and RAM backends. Do not import RM-614 `layout.rs`, `soc.rs`, `lcd.rs`, `keypad.rs`, `i2c.rs` or `machine.rs` wholesale.
3. **Model memory as devices, not a single RAM slab.** The combo memory profile is 32-MiB NOR + 16-MiB DDR SDRAM. NOR read-array data, command/ID/CFI reads and erase/program state need to be distinct from volatile RAM. The current probe's mapped RAM + diagnostic NOR command hook remains a temporary path.
4. **Recover CBUS before modelling RETU/TAHVO.** The board schematic exposes CBUS signals; determine the firmware's framing, registers, chip-select and timing by tracing the RAPGSM bus driver. Do not transplant an I2C transport merely because a different Nokia Retu driver uses I2C.
5. **Add the 240x320 LCD and keypad only after the bus/register contract is known.** The DCT3 emulator and EmuGII are useful examples of event-driven device models and framebuffer/key boundaries, but their register maps are not RM-217 specifications.
6. **Bring the engine to Android after a host-side machine is stable.** The existing `PhoneSystemEngine` interface already defines bounded execution, frame/audio drains and key press/release edges. A Rust `cdylib`/JNI backend can implement that contract later; the current Android Gradle app has no native backend yet.

## Immediate engineering consequences

- The desktop probe's 32-MiB NOR capacity is now backed by the board schematic rather than an unexplained constant.
- The service-observed `00EC:2208` ID and flash partition boundaries are represented in the probe's explicit RM-217 NOR profile. The command/erase algorithm is **not** claimed to be a full K8F5615ETM model.
- The probe's static-row comment is corrected: `r3` points to the current row's fourth word (`record + 0x0C)); its attribute semantics are still unknown.
- The next hardware investigation should target RAPGSM's CBUS master and the first RETU/TAHVO register transactions, then correlate that traffic with boot progress and reset requests.

## References reviewed on 7 November 2026

- Nokia RM-217/RM-222 service manual: https://manuals.plus/m/97f91f5d5740e2dd1c95f58fcc0482693bf8eef843b4259dfb3054195898f21a
- Nokia RM-217/RM-222 service schematics v2.0: https://usermanual.wiki/Document/nokia6300rm2176300brm222serviceschematicsv20.673614552.pdf
- Samsung K8F5615ETM part listing (CUI/CFI, x16): https://www.datasheets360.com/part/detail/k8f5615etm-se1ft/-8569454681145644897/
- Samsung 256-Mbit K8F family datasheet catalogue context: https://www.datasheetarchive.com/?q=K8F56
- Samsung flash-family cross-reference (x16, 16-bank): https://www.bdtic.com/download/Micron/Spansion_NOR_Flash_Memory_2011.pdf
- RM-217 service-tool flash ID output: https://gsmforum.ru/threads/nokia-6300-contact-service.99432/
- RM-217 07.30 NOR layout/service flashing output: https://www.unlockers.ru/threads/30650-nokia-6300-%D0%BF%D1%80%D0%BE%D1%88%D0%B8%D0%B2%D0%BA%D0%B0
- c3emu source and license: https://github.com/androettop/c3emu
- Nokia DCT3 emulator source and license: https://github.com/djr-747/nokia-dct3-emulator
- EmuGII/QEMU STMP3770 model: https://github.com/ExistOS-Team/EmuGII
- FreeJ2ME source: https://github.com/hex007/freej2me
- QEMU ARM system emulation: https://www.qemu.org/docs/master/system/target-arm.html



## RETU/TAHVO CBUS: RM-217 wiring and candidate protocol (2026-11-07)

### RM-217 schematic evidence

The RM-217/RM-222 Service Schematics v2.0, page 5, name the RAPGSM-side CBUS connections:
- GENIO31 = CBusClk;
- GENIO30 = CBusDa;
- CBusEn1X = select/enable line;
- RETU-side names are CbusClk, CbusData and CbusSelX;
- the CBUS group is shared by RETU, TAHVO and FM radio.
The same page separately shows I2C(1:0), SDA and SCL. The board PMIC transport must therefore not be replaced by an ordinary I2C model.

Reference: https://usermanual.wiki/Document/nokia6300rm2176300brm222serviceschematicsv20.673614552.pdf

### Related open-source protocol implementations

The Linux Nokia Internet Tablet driver `i2c-cbus-gpio.c` (https://android.googlesource.com/kernel/common/+/e32795503de02da4e7e74a5e039cc268f6a0ecfb/drivers/i2c/busses/i2c-cbus-gpio.c) bit-bangs CLK, DAT and SEL with three GPIOs. It documents a candidate transaction header of 3-bit device address, 1-bit R/W and 5-bit register, sent MSB-first, followed by a 16-bit data phase. For reads it switches DAT to input and clocks a 16-bit word back. The Linux driver exposes this over its I2C adapter API, but physically this is GPIO-driven CBUS, not the separate I2C SDA/SCL interface.

The old QEMU/DECAF model (https://github.com/decaf-project/DECAF/blob/6b09760d9b35fa37f524e810c6561bdb4c76b821/decaf/hw/cbus.c) independently models a three-line Nokia CBUS with the same 9-bit header + 16-bit data structure and generic Retu/Tahvo devices. It is based on reverse engineering of other Nokia platforms, so its register defaults and slave addresses are references, not confirmed RM-217 behaviour.

The Linux driver specifically cautions that its 3-bit address and 5-bit register field widths should be checked for other CBUS implementations. Thus the 3-wire protocol is a strong candidate consistent with the RM-217 schematic, but it is not yet verified against live RM-217 MCUSW traffic. Do not code a full transport/PMIC model before capturing the first frame.

### Methodology reference: another Nokia reverse-engineering project

The Nokia E7-00 project https://github.com/apoage/nokia-e7-linux recovered pinmux tables and GPIO register semantics from that device's own OneNAND image and validates device models in QEMU. It targets the Broadcom BCM2727B1 / ARM1176 RAPU platform, not RM-217 RAPGSM / ARM926. It is a valuable workflow reference only; do not reuse its BCM2727 GPIO map, 7-bank registers or device code.

### Current probe instrumentation boundary

The existing watched ranges 0x20032C00..0x20032DFF and 0xFFD32C00..0xFFD32DFF are still unclassified ASIC/control windows; the reset register was observed at 0x20032D14. The current patch adds read logging to those ranges and captures the surrounding reset routine at 0xF0103A48. This is only to obtain PC/register context. The ranges are not identified as CBUS GPIO registers yet.


The [Nokia E7 Linux reverse-engineering project](https://github.com/apoage/nokia-e7-linux) is another methodology reference: it recovers GPIO/pinmux data from its own OneNAND/ROM and validates a board-specific QEMU machine, but it is BCM2727B1/ARM1176 RAPU hardware and is not reusable as an RM-217 register map.



## CBUS line count and platform-specific variants

The RM-217/RM-222 schematic uses the group label CBUS(3:0), but the detailed page-5 pin rows explicitly name three active signal connections: RAPGSM GENIO31 / CBusClk, RAPGSM GENIO30 / CBusDa, and CBusEn1X as select/enable. The destination group is RETU, TAHVO and FM radio. The same page lists I2C(1:0), SDA and SCL separately. Do not interpret the CBUS(3:0) group label alone as proof of four active physical wires; the detailed rows identify these three named nets, and no fourth active CBUS line is mapped in this schematic.

This three-signal topology is consistent with another Nokia 6233/RM-145 schematic, which also groups the bus as CBUS(3:0) but names CBUSCLK, CBUSDA and CBUSENX, and with older Nokia baseband troubleshooting documentation that explicitly describes CLK/DATA/BUS_ENABLE as a three-wire CBUS.

CBUS is not universal across every BB5 board. The Nokia N81/RM-179/RM-223 service manual describes a RAPIDO/EM ASIC CBUS variant as a four-wire half-duplex master/slave interface at 2.4 MHz. That is another phone design: do not transfer its wire count or clock rate to RM-217. This platform difference is a reason to trust RM-217 pin assignments over generic CBUS descriptions.

The Linux Nokia Internet Tablet GPIO driver and QEMU/DECAF three-line models are therefore useful physical-topology references for RM-217, but their 3-bit slave address, R/W bit, 5-bit register address and 16-bit data framing are not yet proven for this firmware. The actual MCUSW GENIO31/GENIO30/CBusEn1X transactions still need to be captured.

A comparative Nokia RM-38/RAPGSM service manual describes the RAPGSM ASIC as containing ASIC clock/reset control and a CBUS interface; it describes EM ASIC N2200 as containing start-up/reset logic and a digital CBUS interface, while TAHVO handles energy management and charge control. RM-38 is not the RM-217 register map. This block-level context does not identify the observed RM-217 reset write at 0x20032D14 as a particular RAPGSM, RETU or TAHVO register.

References:
- RM-217 exact signal assignments, service schematic page 5: https://usermanual.wiki/Document/nokia6300rm2176300brm222serviceschematicsv20.673614552.pdf
- Nokia 6233/RM-145 service schematic, page 5: https://www.scribd.com/document/712368773/nokia-6233-rm-145-6234-rm-123
- Nokia RH-51/52 three-wire CBUS troubleshooting guide: https://electronicsandbooks.com/edt/manual/Hardware/N/Nokia/Phone/NOKIA%206670%2C%207610%20RH-51/NOKIA%206670%2C%207610%20RH-52%2C%20RH-67%2C%20RH-68%206a.pdf
- Nokia N81/RM-179/RM-223 four-wire CBUS variant: https://www.manualslib.com/manual/1144021/Nokia-N81.html?page=204
- Nokia RM-38/RAPGSM functional description: https://www.manualslib.com/manual/4254639/Nokia-Rm-38.html?page=234