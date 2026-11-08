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
