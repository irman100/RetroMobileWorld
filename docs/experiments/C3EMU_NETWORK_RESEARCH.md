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

