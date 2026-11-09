# C3-00 guest Internet research and implementation plan

Updated: 8 November 2026

## Executive finding

The RM-614 firmware documentation describes two distinct Internet transports: WLAN (802.11) and GPRS packet data. The pinned c3emu Android backend implements neither transport yet. It models the SIM controller as no-card, and the ISI layer records messages but does not route modem/network packets.

The first implementation target should be WLAN, because the device's WLAN interface is present in the RM-614 service schematic and Android already has a real Wi-Fi/mobile-data route. GPRS can follow once its ISI modem protocol is captured and understood.

## Evidence from the device

- Nokia C3-00 user guide: WLAN can connect to the Internet, list and save available networks and use saved access points. Source: https://nokia-c3-00.helpdoc.net/en/nokia-c3-00-user-guide/settings/connectivity/wlan-connectivity/
- WLAN setup flow: Menu > Settings > Connectivity > WLAN, Available WLANs, Refresh list, Connect and Save. Source: https://nokia-c3-00.helpdoc.net/en/nokia-c3-00-user-guide/settings/connectivity/wlan-connectivity/connect-to-a-wlan/
- Connection policy: applications can ask before connecting or use saved WLANs automatically. Source: https://nokia-c3-00.helpdoc.net/en/nokia-c3-00-user-guide/settings/connectivity/wlan-connectivity/saved-wlans/
- Nokia C3-00 service schematic RM-614 identifies a distinct WLAN signal group and controller connections including WLANENABLE, WLAN_IRQ, SPI_CLK, SPI_MOSI, SPI_MISO, SPI_CSX, CLK_REQ, with alternate SDIO names on the same signal group. Source: https://documents.cdn.ifixit.com/ORvCvsmrQTL2IJVq.pdf
- Nokia user guide also documents GPRS as an IP packet-data service with on-demand and always-on policies. Source: https://nokia-c3-00.helpdoc.net/en/nokia-c3-00-user-guide/settings/connectivity/packet-data/

## Existing c3emu boundary

Pinned upstream: androettop/c3emu @ 9b0d3ae2da326e109428f64d8760ea823ee1b648.

Verified in source:
- sim.rs explicitly models no SIM card; after a reset it reports no-ATR.
- boot.rs enables ISI hooks, but the local Android wrapper uses isi: false for console echo. The hook counts and stores ISI trace lines; it does not provide a modem.
- isi.rs hooks the firmware's send/receive paths for observation. It does not inject responses from a modem or expose packet data.
- bus.rs has MMIO windows and sparse backing registers, but no SDIO host/WLAN controller, WLAN MAC, DMA packet ring or network packet device.
- c3emu does not include a virtual Wi-Fi chip, access point, IP packet endpoint, DNS resolver, TCP transport or socket bridge.

Thus, granting Android INTERNET permission alone cannot make guest Browser/Mail downloads work.

## Candidate ready-made libraries

### smoltcp (Rust, 0BSD)

- Repository: https://github.com/smoltcp-rs/smoltcp
- Docs: https://docs.rs/smoltcp/latest/smoltcp/
- Provides an event-driven TCP/IP stack with TCP, UDP, ICMP and an Ethernet interface; supports raw-socket/TAP physical devices.
- Useful for a host-side virtual-network test harness or a deliberately synthetic guest-facing NIC.
- It is not a WLAN chipset emulator, 802.11 association/authentication engine, SDIO controller or Android bridge.

### lwIP (C, permissive BSD-style licence)

- Docs: https://www.nongnu.org/lwip/2_1_x/group__api.html
- Provides raw, sequential and BSD-socket APIs for TCP/IP.
- Useful if we need a C-facing TCP/IP compatibility layer around native c3emu.
- It also does not emulate the Nokia WLAN controller or its firmware/driver protocol.

### Linux Phonet reference

- Kernel documentation: https://www.kernel.org/doc/html/v5.17/networking/phonet.html
- Defines Nokia Phonet as a packet protocol used for IPC/RPC with a cellular modem and documents its common header.
- This is useful background for the later GPRS/modem route. It is not itself an IP/TCP stack and does not substitute for Nokia's modem-side ISI services.

### Android packet bridge

- Android VpnService creates a local TUN interface after explicit user consent. Its protect(Socket) API lets bridge sockets bypass the TUN path and use the underlying Android network.
- Docs: https://developer.android.com/develop/connectivity/vpn
- This is a plausible unprivileged packet bridge if we decide the guest-facing adapter should exchange IP packets. It requires a clear user-consent UI and careful routing to avoid a loop.

## Proposed WLAN-first architecture

1. Firmware/driver discovery (required before coding a fake device).
   - Inspect the user's exact RM-614 MCUSW/PPM package and locate WLAN driver, firmware blob, device IDs, GPIO/SPI/SDIO init sequences and packet-buffer format.
   - Use guest traces to identify the exact controller registers, commands, interrupt sequence, WLAN scan/auth/association and RX/TX path.
   - Compare those accesses against the service schematic and c3emu's current MMIO map.
2. Virtual WLAN controller.
   - Add a small, deterministic device model to the c3emu SoC for only the commands and registers used by this firmware.
   - Implement scan/association with a virtual access point. Start with a user-selected open test network, then implement WPA2 only after the driver sequence is known.
   - Model interrupts, queues, frame lengths, timeouts and reset. Unknown commands must fail locally with diagnostics, not corrupt unrelated SoC state.
3. Guest packet path.
   - Prefer passing guest IP packets through the emulated WLAN interface to a host bridge, avoiding a second TCP/IP stack if Series 40 already owns IP/DNS/TCP.
   - Use smoltcp only if a small deterministic packet adapter or test harness is needed; do not embed it merely because the project is Rust.
4. Android bridge.
   - Use Android VpnService TUN or an equivalent permission-aware bridge. A TUN bridge requires explicit user approval, route/DNS configuration and socket protection to avoid looping its own traffic.
   - Keep the bridge scoped to the emulator; do not route the user's general phone traffic through a remote server.
5. Download validation.
   - First test DNS + HTTP GET against a controlled test endpoint.
   - Then test HTTPS/TLS compatibility.
   - Finally test the Series 40 Browser downloading a small .jad and .jar, verify file size/hash, and install/launch the MIDlet from guest C:.
   - Record exact failure phase: WLAN scan, association, DHCP, DNS, TCP connect, TLS handshake, HTTP response, download write or JAD/JAR install.

## GPRS route — second phase

- Reuse the ISI trace hook and Phonet message format.
- Capture exact resources/message IDs and payloads for SIM state, network registration, packet-data attach, PDP context/APN activation and socket/data routing.
- Then implement only the observed request/response subset for a virtual SIM/modem. A fake "SIM present" bit alone is not enough; the OS expects a sequence of ATR, SIM filesystem/application responses, registration and packet-data state.
- Map the established PDP packet path to the same Android bridge.
- Do not guess ISI resource numbers, message IDs, APN payloads or SIM responses.

## Acceptance criteria for the user's requested result

A feature is not complete when a menu says "connected". It is complete only after all of the following are observed in a guest Series 40 application:

1. WLAN list shows the virtual access point.
2. The phone joins it without stopping the guest CPU.
3. DHCP (or the firmware's static configuration) assigns an IP address.
4. DNS resolves a public test hostname.
5. TCP opens and an HTTP response is read.
6. HTTPS is tested separately; if firmware TLS cannot negotiate with modern sites, the limitation is recorded and an explicitly scoped compatibility gateway is evaluated.
7. Series 40 Browser downloads a real test .jad and .jar into guest storage.
8. The MIDlet appears in the phone UI and launches in the emulator.

No Internet support is declared until the complete download/install path succeeds on the physical Android device.


Implementation note: the runtime MMIO trace window is a rolling view over the latest 64 unique SoC/peripheral register addresses encountered, in chronological order. It is intended to help identify register accesses that occur when opening WLAN/GPRS settings or Browser; it does not emulate those devices.


## RM-614 WLAN component identification update — 8 November 2026

A repair reference for Nokia C3-00 lists:
- N6300 — Nokia part 4390090 — BOB1.0M-a, LGA130;
- W6000 — internal WLAN antenna;
- Z6300 — WLAN front-end band-pass filter.

The RM-614 service schematic confirms N6300's WLAN-facing host signals:
- WLANENABLE (H2);
- WLAN_IRQ (K3), also labelled SDIO_D1;
- SPI_CLK / SDIO_CLK (K2);
- SPI_MOSI / SDIO_CMD (H3);
- SPI_MISO / SDIO_D0 (L1);
- SPI_CSX / SDIO_D3 (K4);
- CLK_REQ (K1).

The same N6300 package also exposes Bluetooth UART/PCM and FM/I2C signals, consistent with a combined connectivity module. The service schematic shows the RF path through Z6300 to W6000.

Sources:
- RM-614 schematic page 7: https://documents.cdn.ifixit.com/ORvCvsmrQTL2IJVq.pdf
- Component identification / part-number table: https://www.radioradar.net/repair_electronic_technics/phones_repair/nokia_c3_00_mobile_phone_repair.html
- Related Nokia service documentation describes BOB1.0M-a as a combined WLAN/BT/FM connectivity module: https://manualmachine.com/nokia/lumia900/4544489-service-manual/

Important distinction: a different BOB1.0M-b revision used in other Nokia models is documented with a TI WL1271 transceiver. That does **not** prove the C3-00 BOB1.0M-a uses the same silicon. Do not implement wl1271-specific registers, firmware commands or the Linux wl12xx driver until the RM-614 MCUSW/PPM firmware or the part marking/BOM confirms the transceiver. The likely host bus in the C3-00 schematic is selected through shared SPI/SDIO signal lines; firmware traces must establish the protocol actually used.

Next firmware-specific work:
1. Disassemble the RM-614 network/WLAN driver and inspect strings, driver tables and firmware resource references.
2. Find the N6300 power, enable, clock request, reset and IRQ sequence.
3. Determine whether the actual driver uses SPI or SDIO and recover its command/data framing.
4. Only then decide whether to reuse TI wl12xx/wlcore code, another open-source driver, or a small purpose-built BOB1.0M-a model.
5. Bridge the resulting guest Ethernet/IP packets to Android network transport and validate Browser downloads.



## Exact RM-614 MCUSW WLAN analysis — 8 November 2026

### Archive and confidence boundary

The user re-attached the RM-614 Phoenix package as `RM-614.zip`. Archive SHA-256: `5239534da21345e5fc7bc1fbe5277f7d25a7798e9196742fcc77dc5d34445b11`. The archive includes the RM-614 08.71 MCUSW (29,271,181 bytes), regional PPM and IMAGE variants, and Phoenix product metadata. The original archive was not modified and no firmware binary was added to this repository.

### What the MCUSW establishes

Static string/symbol analysis of `rm614__08.71.mcusw` identifies a real Broadcom WLAN host stack, rather than merely WLAN menu support:

- `WHA: ... Broadcom S60 WLAN HAL PDD [BcmWha ...]` at file offset `0x003F7532` identifies the Broadcom WLAN HAL/PDD.
- The WLAN configuration contains the explicit string `802.11n gSPI` at `0x003F9EEA`.
- C++ symbol names include `WlanSpiaImpl::Configure/Request/SpiDfcCallback/SpiIsrCallback` (around `0x0028F360`) and `WlanHpaImpl::PowerOnDevice/PowerOffDevice/InterruptServiced/ISR/EnableIrq` (around `0x0028EE9C`). This is direct firmware evidence of an SPI/gSPI transport implementation and a separate power/IRQ adaptation layer.
- The neighbouring WLAN configuration contains SROM/NVRAM-style fields (including `sromrev`, `vendid`, `devid`, `boardtype`, `boardrev`, PA calibration and RSSI tables). The test service includes distinct `WLAN_TEST_EEPROM_NVS_BOB_B1_0` and `WLAN_TEST_EEPROM_NVS_BOB_T1_0` profile names at approximately `0x00B6FB72` and `0x00B6FBEA`. These are useful module/profile clues, but the binary does not yet prove which profile is selected for the board's BOB1.0M-a component.
- Broadcom transport/protocol strings include `Broadcom SDPCMD CDC driver`, `SDPCM`, `BDC`, LMAC commands (`lmac_scan`, `lmac_join`, `lmac_reset`), FIFO/status/interrupt handling and RX/TX paths. The host-side WLAN driver therefore expects a real controller command/data interface; simply adding Android Internet permission or a guest-side IP socket is not enough.
- A device-side Broadcom firmware version string `4.218.227.19` and build date `Oct 12 2010` are present around `0x00B5A1DB`. WHA diagnostics describe INIT1/INIT2/INIT3, ALP/HT clock-ready waits, `DevDownload`, and an Init Download Block (IDB) with required code and NVMEM sections plus code/vars verification. This strongly indicates a host boot-time firmware-download flow. The exact IDB code/NVMEM payload and its complete container boundaries have **not** yet been recovered.

### Bus conclusion and cautions

The RM-614 schematic labels the N6300 WLAN pins as `WLAN_SPICSX`, `WLAN_SPITxData`, `WLAN_SPIRxData`, `WLAN_SPIClk`, `WLAN_IRQ`, `WLAN_En` and `WLAN_CLK_REQ` (the same board also has SDIO aliases on shared lines). Together with the MCUSW's `802.11n gSPI` configuration and `WlanSpiaImpl` symbols, the firmware-selected WLAN host path is SPI/gSPI, not the generic SDIO path. Generic SDIO driver strings elsewhere in MCUSW are not evidence that WLAN uses SDIO on this phone.

The board component remains documented as N6300 / Nokia part 4390090 / BOB1.0M-a. The exact Broadcom silicon/revision behind that module and the mapping of the BOB-specific NVS profile to this exact board revision remain unconfirmed. The string `BCM4329B1 Class 1.5 For Nokia Phones` occurs inside a separate WMV3/Windows Media resource block and is deliberately **not** treated as chip-identification evidence. Do not substitute a TI WL1271/BOB1.0M-b driver.

Schematic/parts references: https://documents.cdn.ifixit.com/ORvCvsmrQTL2IJVq.pdf and https://www.radioradar.net/repair_electronic_technics/phones_repair/nokia_c3_00_mobile_phone_repair.html. The Broadcom BCM4329 datasheet is useful for understanding the generic SPI/gSPI framing and firmware/LMAC split, but does not by itself prove that this exact BOB1.0M-a module contains that silicon: https://www.digikey.com/en/htmldatasheets/production/1654863/0/0/1/bcm4329.html.

### Next engineering steps

1. Recover the exact IDB/device-code and NVMEM payload from the MCUSW package, establish its format and confirm the target chip/revision without relying on unrelated media metadata.
2. Capture guest runtime SPI MMIO, chip-select, WLAN enable, CLK_REQ and IRQ activity when opening WLAN settings and scanning. Map these accesses to the Broadcom host transaction layer in c3emu; do not invent SoC register addresses.
3. Implement only the observed gSPI subset: reset/wake/clock readiness, command framing, control/register access, packet FIFOs, interrupt/status and SDPCM/BDC framing. Keep unknown operations visible in diagnostics.
4. Connect valid guest IP packets to the Android network bridge. Validate WLAN scan/association, IP configuration, DNS, TCP/HTTP and then HTTPS before attempting Browser JAD/JAR downloads.
5. Keep GPRS/virtual SIM as a later phase; no SIM/ISI packet behavior should be fabricated from these WLAN findings.

The firmware analysis narrows the design substantially, but it does not yet implement Internet or prove a working packet path. The acceptance criterion remains a real .jad/.jar download and MIDlet install/launch from the guest Series 40 Browser.


## Pinned c3emu host-side SPI and trace limitations — 8 November 2026

The pinned dependency `androettop/c3emu@9b0d3ae2da326e109428f64d8760ea823ee1b648` was reviewed directly to determine what must be emulated on the Nokia host side before connecting the Broadcom gSPI target.

- `crates/c3emu/src/bus.rs` maps only the DMAC, LCDC, SOC_IO and PERIPH MMIO windows. Its read/write dispatch covers the existing SoC, I2C, LCD, SIM, DMAC, keypad, general timer, GPIO, RTC and DSP handlers; it has no SPI-master, SDIO-host or WLAN device model.
- A register not claimed by a device handler falls back to the sparse MMIO backing store. The guest can read back stored values, but there is no SPI transfer, FIFO, chip-select, clock or WLAN side effect. Thus, adding only a Broadcom gSPI target would still not connect it to the guest CPU: the host SoC SPI controller must also be implemented.
- The current GPIO implementation models the interrupt status latches and raises INTC line 6; it is not a general board-pin output/edge model for WLAN enable, CLK_REQ and IRQ wiring. These signals need an explicit board-level model once their guest register accesses are mapped.
- The Android wrapper's `io_touches` diagnostic takes the last 64 unique addresses. In upstream, `State::touch` records only the first access to each (region,address), so this is useful for address discovery but not for reconstructing repeated SPI transactions or polling sequences.
- Upstream `Machine::iolog(addr,len)` can log every read/write in a selected MMIO range, including value and guest PC, into the machine event log. That event log is capped at 20,000 entries and emitted by `Machine::report()`; the current Android C ABI/JNI wrapper does not expose range selection or event retrieval. A targeted, bounded trace interface must be added before attempting protocol recovery. Do not permanently log every MMIO address because that can generate excessive volume and slow the guest.

### Correct implementation order

1. Use the existing first-touch MMIO list during WLAN-menu/scan activity to identify candidate host SPI/GPIO register ranges and guest call sites.
2. Add a narrow, opt-in, bounded access-by-access trace for selected ranges to the Android wrapper (or a small rolling trace buffer in the vendored/forked c3emu bus layer).
3. Recover host SPI-master configuration, transfer framing, GPIO enable/CLK_REQ, chip-select and IRQ routing from the trace and MCUSW call sites.
4. Implement the SoC SPI master and board signal routing first; then attach the Broadcom gSPI device model and its observed firmware/SDPCM/BDC command subset.
5. Verify WLAN scan and packet traffic end to end before declaring network support.

This confirms the architecture is two connected emulated layers — Nokia SoC SPI/GPIO host plus Broadcom gSPI WLAN device — followed by the Android packet bridge. No addresses, register values or successful network behavior are assumed yet.


## Compact MMIO diagnostics for the next device capture — 8 November 2026

The previous periodic performance line embedded the full native stats JSON. The growing ISI counter map and recent ISI text caused Android Logcat to truncate the tail of the line before the MMIO list. The logging path is now split:

- `C3EmuPerf`: performance counters only (`cycleHz`, `deliveredFps`, `cycleAvgMs`, `cycleMaxMs`).
- `C3EmuTrace`: one compact JSON record, capped at 40 most-recent unique SoC/peripheral first-touch entries. Fields: `running`, `stopped`, `simTimeouts`, `isiMessages`, `firstTouch`.
- Each first-touch row is `[region, op, addr, value, pc]`; `s` = SoC and `p` = peripheral. Hex fields are eight uppercase digits. Stop text is limited to 160 characters.
- A Rust unit test enforces a payload size of at most 3,072 bytes and verifies the newest 40 first-touch records are retained. CI now runs this Rust test before the Android native builds.

The compact log fixes the truncation seen in the previous capture, but intentionally does not claim to be a repeated-access SPI bus trace: upstream `State::touch` still records only the first access per unique address. Range-based, access-by-access tracing is separate follow-up work.

Device capture:

```powershell
adb logcat -v time -s C3EmuPerf:I C3EmuTrace:I C3EmuBoot:E |
    Tee-Object "$env:TEMP\\emulator-mmio-trace.txt"
```


## Diagnostic-test CI correction — 8 November 2026

The first CI run for the compact trace change reached `cargo test` and exposed an unclosed `android_wrapper_tests` module after the new test was inserted. The helper/string escaping was already corrected before that run; the compiler reported only the missing final module delimiter. A closing brace was added. No runtime implementation or existing phone state was changed. The follow-up push/PR CI must pass this Rust test before any APK is distributed.


## First observed SPI-host initialization and watchdog stop — 9 November 2026

The 9 November device capture (Android PID 1192) remained in running=true from 13:52:42 through 13:54:46 and reported a guest BB H/W watchdog reset at 13:54:48.436, reset PC 0x80702931. This is a guest firmware stop; the capture does not show an Android process restart or an application reinstall.

At 13:54:44.918 the first new SoC MMIO accesses appeared in the 0x088D window. The following two-second interval added accesses at offsets +0x10, +0x24, +0x18, and +0x0C. The exact first-touch records and guest PCs are preserved in the supplied capture.

The MCUSW 08.71 image was parsed as FPSX and the logged guest PCs were disassembled from its MCUSW region. They land in code with strings/symbolic diagnostics SPI_Init, ConfigSPISelectMux, SPI_Set_Clock, IOCR2_REG, and IOCR0_REG. In particular, the logged writes at PCs 0x8081018C, 0x80810198, 0x808101E0, 0x808101E8, 0x808101F8, 0x80833D0C, and 0x80810AB4 are inside the SPI initialization / mux / clock paths. This is direct evidence that the guest is initializing its SoC SPI host controller at 0x088D0000 and related mux registers.

The address window is within c3emu SOC_IO (0x08800000..0x08A00000). The pinned upstream bus dispatch has no SPI-master model; unclaimed addresses are served by the sparse backing store. The observed reads from 0x088D0018 and 0x088D000C return zero on their first touch. Because the current first-touch mechanism does not capture repeated polls, this alone does not prove which status bit or missing interrupt caused the watchdog. It does prove that the guest reached the SPI host-driver path shortly before stopping.

### Targeted transaction tracing added

The next diagnostic build enables upstream Machine::iolog only for 0x088D0000..0x088D1FFF. This records every read/write with operation width, address, value, instruction count and guest PC. A separate C3EmuBus line returns only the latest 24 transactions in compact JSON; output is bounded to 3,072 bytes. C3EmuTrace continues to provide first-touch address discovery. A Rust unit test verifies filtering, chronological retention, and the line-size bound.

This remains instrumentation only: it does not emulate the SPI host or Broadcom target, and it does not claim working WLAN. The transaction trace should tell us whether the guest is polling a register, waiting for DMA/IRQ completion, or issuing another setup sequence before the watchdog.

## Research update — gSPI framing and Android egress boundaries — 9 November 2026

### Broadcom gSPI protocol references

The exact transceiver silicon inside the RM-614 N6300 / BOB1.0M-a module remains unconfirmed. The MCUSW evidence (`802.11n gSPI`, `WlanSpiaImpl`, Broadcom S60 WLAN HAL) establishes the firmware-selected gSPI host path, but it does not justify naming the chip BCM4329 or importing a chip-specific register map.

A Broadcom 802.11n gSPI specification is useful as a protocol-family reference. It describes a 32-bit command word with packet length, address, function, fixed/incrementing access and read/write fields. The documented function roles include F0 for SPI-specific registers, F1 for device registers/memory, F2 for WLAN packet DMA and optional F3 for a second packet channel. The length encoding and two-phase read behavior must be checked against the exact device firmware/profile before they are treated as RM-614 requirements.

Reference: https://www.fccid.io/m/5b5f9fee14b4be35262b499257d8edd4f65478cc9d12b4efe9d410367ccd9053.pdf

Broadcom's older `bcmspibrcm.c` source is explicitly marked unpublished proprietary source; it is not copied into this project. It is useful only as a pointer to the historical Broadcom gSPI transport layer:
https://android.googlesource.com/kernel/msm.git/+/3f2bc4d6eb5a4fada842462ba22bb6bbb41d00c7/drivers/net/wireless/bcm4329/bcmspibrcm.c

### SDPCM/BDC is a separate layer from gSPI

Linux mainline `brcmfmac` provides a useful reference for Broadcom SDPCM framing: a 4-byte hardware header plus an 8-byte software header, with control, event, data and glom channel identifiers. This helps interpret the MCUSW strings for SDPCM/BDC and the packet path above the bus.

Important limitation: the current Linux `brcmfmac` fullmac bus implementations are documented around SDIO, USB and PCIe; this is not a drop-in implementation of the Nokia's gSPI host controller. Reuse the protocol concepts only, not the SDIO register/host implementation.

References:
- https://github.com/torvalds/linux/blob/master/drivers/net/wireless/broadcom/brcm80211/brcmfmac/sdio.c
- https://wireless.docs.kernel.org/en/latest/en/users/drivers/brcm80211.html

### Android network egress: per-network sockets first, TUN only by design

Android's `Network.getSocketFactory()` creates sockets bound to one selected Android `Network`; `Network.bindSocket()` can bind an individual unconnected socket. This supports an emulator-owned egress layer that selects the active Wi-Fi/cellular network per connection without rebinding the entire Android process.

A `VpnService` is a different mechanism: it establishes a TUN interface after the user grants VPN consent. Its `protect(Socket)` / `protect(DatagramSocket)` methods keep the service's own upstream sockets outside the VPN route and prevent a routing loop. TUN does **not** automatically translate arbitrary guest IP packets into Internet flows; packet routing/forwarding or transport-flow termination still has to be implemented by the app.

References:
- https://developer.android.com/reference/android/net/Network
- https://developer.android.com/reference/android/net/VpnService

### Architecture decision refined

Keep the implementation in distinct layers:

1. Nokia Juno SoC SPI-master and board signals (WLAN enable, chip-select, CLK_REQ, IRQ).
2. Broadcom gSPI target: reset/clock readiness, control/register access, device firmware/NVS download, packet FIFO and SDPCM/BDC framing — only as supported by the captured RM-614 firmware sequence.
3. A guest-facing packet boundary that preserves the Series 40 TCP/IP stack. Decide between a real packet-level interface and a host socket-flow gateway only after the guest driver path and packet format are understood.
4. Android host egress using sockets bound per `Network`. Add `VpnService` only if the selected packet architecture genuinely needs an Android TUN interface; do not make it a prerequisite by assumption.

The guest packet transport still needs a real bridge. Android `INTERNET` permission and a successful host HTTP request are not sufficient acceptance tests. Required validation remains guest WLAN scan/association, IP setup, DNS, TCP/HTTP, HTTPS and a real Series 40 Browser JAD/JAR download/install/launch.

### Updated evidence boundary

The 9 November SPI capture shows repeated `R1 0x088D000C = 0` reads from guest PC `0x8073A838`, separated by 8 guest instruction counts. The shared upstream event vector is capped at 20,000 entries, so the filtered `eventCount=13868` plateau can be a trace-source saturation effect rather than the end of guest polling. A separate rolling MMIO event ring is required before the complete poll-to-watchdog sequence can be established.

No WLAN chipset registers have been inferred from the gSPI specification, no TUN bridge has been implemented, and guest Internet remains unsupported.

## Register-map separation: Nokia host MMIO versus Broadcom gSPI target — 9 November 2026

A further datasheet search found a potentially misleading offset match. Some Broadcom gSPI datasheets (for example BCM4330/BCM4334 family references) place an internal F1-info register at target-side gSPI address `0x000C`; they also describe a 32-bit gSPI status word containing data-not-available, FIFO underflow/overflow, F2/F3 interrupt, RX-ready and packet-available/length fields.

These are **register addresses inside the Broadcom target**, reached through a completed gSPI transaction. They are not the Nokia Juno SoC's CPU-visible MMIO address map. The captured `0x088D000C` access is in the Nokia SoC `SOC_IO` range and occurs at the host SPI-controller guest PC. The matching low offset `+0x0C` is only a coincidence until the MCUSW instruction sequence proves its meaning. Do not assign Broadcom F1-info/status semantics to `0x088D000C`.

The Broadcom datasheets are protocol-family references only; the exact N6300 / BOB1.0M-a silicon and revision are still unconfirmed. In particular, do not implement a BCM4330/4334 internal register map merely because those public documents are easier to find.

References:
- BCM4330 gSPI command/status reference: https://www.digikey.tw/htmldatasheets/production/2092456/0/0/1/BCM4330.pdf
- BCM4334 gSPI register/status reference: https://device.report/m/acff856fabf42a66a5aff0277d40c8ce90c98c318dfbf6eaedbc71d9ba24b84f.pdf
- RM-614 host-side WLAN pinout: https://documents.cdn.ifixit.com/ORvCvsmrQTL2IJVq.pdf

A public search for the exact Nokia host address `0x088D000C`, `0x088D0000` and the MCUSW names `IOCR0_REG` / `IOCR2_REG` did not produce an authoritative RM-614/Juno SoC register manual. Search results for the "Juno ARM Development Platform" technical reference manual describe a different ARM development board and are not applicable to Nokia C3-00. The host-register meaning must therefore come from the exact MCUSW disassembly/runtime sequence or a Nokia Juno-specific source, not from similarly named platform documentation.
