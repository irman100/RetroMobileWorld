# RM-614 Nokia SPI host-controller contract

**Firmware:** Nokia C3-00 / RM-614 MCUSW 08.71  
**Firmware archive SHA-256:** `5239534da21345e5fc7bc1fbe5277f7d25a7798e9196742fcc77dc5d34445b11`  
**Pinned emulator source:** `androettop/c3emu@9b0d3ae2da326e109428f64d8760ea823ee1b648`

This document separates facts decoded from MCUSW from provisional implementation choices. It is not a full RAP3G SPI controller datasheet.

## 1. Runtime address translation and evidence

The FPSX named MCUSW region is loaded by upstream c3emu at guest address
`0x80130000`. Its reconstructed payload is `0x00DBD200` bytes. For guest
instruction PC `P`, the MCUSW file offset is therefore
`P - 0x80130000`.

Do not use `P - 0x80000000` for this image.

The physical-device C3EmuBus trace confirmed MMIO accesses in
`0x088D0000..0x088D1FFF`, the RM-614 SoC host-controller window. The earlier
trace showed repeated reads of `0x088D000C` returning zero because upstream
c3emu did not claim this range and sparse backing returned zero.

## 2. Registers decoded from MCUSW 08.71

| Address | Access seen in firmware | Established behaviour | Confidence / limits |
|---|---|---|---|
| `0x088D0000` | 16-bit read/write | SPI configuration word. `SPI_Set_Clock` preserves the low byte and writes a computed divider into the upper byte. | Divider algorithm observed; complete bit meanings unknown. |
| `0x088D0004` | Byte read/write | `SPI_Set_Master_Mode` clears bit 2, conditionally sets bit 2 based on its argument, and always sets bit 1. The observed init call uses argument 1. | Bit 1 is used as the model's master-enable gate. Do not name other bits without further evidence. |
| `0x088D0008` | 16-bit read/write | Data/FIFO port. Firmware writes a halfword after testing status bit 2, then reads a halfword after testing status bit 3. | The paired TX/RX access is confirmed. FIFO depth and timing are not. |
| `0x088D000C` | Byte read | Status. Bit 2 is tested before the data-port write; bit 3 is tested before the data-port read. | The bit tests are exact. TX-ready/RX-ready interpretation follows the calling code. Other bits are unknown. |
| `0x088D0010` | Byte write | `SPI_Set_Clock` writes value 2 before updating the configuration word at `+0x00`. | Value and sequence confirmed; full field meaning unknown. |
| `0x088D0014` | Byte write | Initialization writes zero. | Meaning unknown. |
| `0x088D0018` | Byte read | RX FIFO helper checks bit 7 and emits `SPIDRV: [ERIC]: RxFIFO Overrun!` when set. | Bit-7 error indication confirmed. |
| `0x088D0020` | Byte write | Initialization writes 3; overrun handler writes 1 after observing bit 7 at `+0x18`. | Clear/acknowledge role is strongly suggested by control flow, but full register semantics are unknown. |
| `0x088D0024` | Byte write | Initialization writes 3. | Meaning unknown. |
| `0x088D103C` | Byte write | Address is derived from the literal pointer `0x088D1020` plus offset `0x1C`. Nearby MCUSW strings name `IOCR2_REG`; firmware writes `0x81` and `0x80` in different paths. | Treat as separate IOCR2 / mux / board-control space, not a FIFO register. Exact pin semantics remain unresolved. |

The separate string `IOCR0_REG` also exists in MCUSW. Its complete address
and bit mapping are not established in this contract.

## 3. Polling routines

### TX/RX word exchange at `SPIDRV: -Do_Read_Polling(bytesToRead=%d)`

At guest PC `0x8073A838`:

1. `ldrb r3,[r2,#0x0c]` reads the status byte.
2. `lsls r3,r3,#30` tests bit 2.
3. `bpl` repeats while bit 2 is clear.
4. The routine maintains a retry counter with a limit of `0x8000`; exhaustion returns code 11.
5. When bit 2 is set, firmware writes a halfword to `0x088D0008`.
6. It then tests bit 3 at `0x088D000C` and reads a halfword from `0x088D0008` when RX data is ready.

### RX FIFO drain and overrun check

The helper around guest PC `0x8072BB28`:

- reads one byte from `0x088D0018`;
- tests bit 7;
- logs the overrun message and writes 1 to `0x088D0020` if set;
- otherwise reads `0x088D000C`, loops while bit 3 is set, and drains halfwords from `0x088D0008`.

These routines establish an observable host-controller contract but not the full register specification.

## 4. Initial implementation scope

The first local fork adds a stateful host MMIO model with:

- exact-width handling for the observed 16-bit data port and byte registers;
- a TX-ready status bit when the observed master-enable bit is set;
- RX-ready only when an attached peripheral endpoint supplies an RX halfword;
- a separate `SpiTarget` interface, so the future Broadcom device is not embedded in host-register logic;
- explicit TX/RX counters for unit-level verification;
- the observed RX-overrun flag location and an acknowledgement path.

The implementation is deliberately conservative:

- no Broadcom response is invented;
- no response is produced when no `SpiTarget` is attached;
- the SoC transfer timing, FIFO depth and shift-register timing are not claimed to be cycle-accurate;
- `0x088D103C` is not folded into the SPI FIFO block; it belongs to the still-unresolved IOCR/pin-mux/board layer;
- the host model does not claim WLAN scan, association or Internet functionality.

## 5. Validation gates

1. Rust unit tests verify register widths, TX-ready gating, RX-ready transitions, RX word consumption and the overrun acknowledgement path.
2. Android CI must pass native builds for arm64-v8a, armeabi-v7a and x86_64 plus Android tests and APK assembly.
3. A physical RM-614 boot must show TX-ready polling advances beyond the previous `0x8073A838` TX-ready timeout.
4. With no target attached, the next RX timeout is expected and is evidence of the boundary between host and peripheral. It must not be hidden by fabricated data.
5. Only after the board mux/power/CS signals and exact Broadcom gSPI identity/protocol are mapped should a real WLAN target be attached.

## 6. Next work

- Decode `IOCR0_REG`, `IOCR2_REG`, chip-select, WLANENABLE, CLK_REQ and WLAN_IRQ setup/teardown.
- Determine the host FIFO capacity and status timing from additional driver code and traces.
- Recover the WLAN firmware's exact IDB/NVMEM selection and silicon identity.
- Implement the Broadcom gSPI target separately, then test command framing, device identity, firmware download and SDPCM/BDC paths.
