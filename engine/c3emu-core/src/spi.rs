//! Minimal, firmware-observed Nokia RAP3G SPI master register model.
//!
//! This is the SoC host controller only. It deliberately does not impersonate a
//! Broadcom WLAN device: an RX word is produced only by an explicitly attached
//! SpiTarget. Board power, pin mux, chip-select wiring and Broadcom gSPI protocol
//! belong to separate layers.
//!
//! Confirmed by RM-614 MCUSW 08.71:
//! - 0x088D0008 is accessed as a 16-bit data/FIFO port;
//! - 0x088D000C is read as a byte; bit 2 is tested before a write to +0x08,
//!   and bit 3 is tested before reading a returned halfword from +0x08;
//! - 0x088D0004 is changed by SPI_Set_Master_Mode (bit 1 is enabled);
//! - 0x088D0010 is written with 2 by SPI_Set_Clock;
//! - 0x088D0018 bit 7 is checked as RxFIFO overrun;
//! - writing 1 to 0x088D0020 follows the overrun check.
//!
//! FIFO depth, clock timing, most control bits and reset defaults are not yet
//! established. This first slice uses an immediate one-word host hand-off and
//! an unbounded software RX queue. It does not claim cycle-accurate FIFO timing.

use std::collections::VecDeque;

pub const SPI_BASE: u64 = 0x088D_0000;
const CONFIG0: u64 = SPI_BASE;
const CONTROL: u64 = SPI_BASE + 0x04;
const DATA: u64 = SPI_BASE + 0x08;
const STATUS: u64 = SPI_BASE + 0x0C;
const CLOCK_MODE: u64 = SPI_BASE + 0x10;
const CONFIG14: u64 = SPI_BASE + 0x14;
const ERROR_STATUS: u64 = SPI_BASE + 0x18;
const ERROR_CLEAR: u64 = SPI_BASE + 0x20;
const CONFIG24: u64 = SPI_BASE + 0x24;

const STATUS_TX_READY: u8 = 1 << 2;
const STATUS_RX_READY: u8 = 1 << 3;
const ERROR_RX_OVERRUN: u8 = 1 << 7;
const MASTER_ENABLE: u8 = 1 << 1;
const CLEAR_RX_OVERRUN: u8 = 1 << 0;

/// A target connected to the host controller's serial data pins.
///
/// Returning None represents no responding target / no sampled RX word. This
/// lets the host controller be tested independently without fabricating WLAN
/// firmware replies.
pub trait SpiTarget {
    fn transfer_word(&mut self, tx_word: u16) -> Option<u16>;
}

/// Stateful host-controller register model for the firmware-observed subset.
pub struct SpiHost {
    config0: u16,
    control: u8,
    clock_mode: u8,
    config14: u8,
    error_clear: u8,
    config24: u8,
    rx_overrun: bool,
    rx_fifo: VecDeque<u16>,
    target: Option<Box<dyn SpiTarget>>,
    tx_word_count: u64,
    rx_word_count: u64,
}

impl Default for SpiHost {
    fn default() -> Self {
        Self {
            config0: 0,
            control: 0,
            clock_mode: 0,
            config14: 0,
            error_clear: 0,
            config24: 0,
            rx_overrun: false,
            rx_fifo: VecDeque::new(),
            target: None,
            tx_word_count: 0,
            rx_word_count: 0,
        }
    }
}

impl SpiHost {
    /// Attach a peripheral endpoint. The endpoint is independent of the SoC
    /// host and can later be backed by the board-connected Broadcom gSPI model.
    pub fn attach_target(&mut self, target: Box<dyn SpiTarget>) {
        self.target = Some(target);
    }

    pub fn detach_target(&mut self) {
        self.target = None;
        self.rx_fifo.clear();
    }

    /// Explicit test/diagnostic injection; production target traffic should use
    /// attach_target and transfer_word instead.
    pub fn inject_rx_word(&mut self, value: u16) {
        self.rx_fifo.push_back(value);
        self.rx_word_count = self.rx_word_count.saturating_add(1);
    }

    pub fn tx_word_count(&self) -> u64 {
        self.tx_word_count
    }

    pub fn rx_word_count(&self) -> u64 {
        self.rx_word_count
    }

    fn master_enabled(&self) -> bool {
        self.control & MASTER_ENABLE != 0
    }

    fn status(&self) -> u8 {
        let mut value = 0;
        // Firmware's TX-ready check gates a write to the data port. The first
        // implementation has immediate TX hand-off, so the transmit slot is
        // available whenever master mode is enabled.
        if self.master_enabled() {
            value |= STATUS_TX_READY;
        }
        if !self.rx_fifo.is_empty() {
            value |= STATUS_RX_READY;
        }
        value
    }

    fn write_data(&mut self, value: u16) {
        if !self.master_enabled() {
            return;
        }
        self.tx_word_count = self.tx_word_count.saturating_add(1);
        if let Some(target) = self.target.as_mut() {
            if let Some(rx_word) = target.transfer_word(value) {
                self.rx_fifo.push_back(rx_word);
                self.rx_word_count = self.rx_word_count.saturating_add(1);
            }
        }
    }

    fn read_register(&mut self, addr: u64, size: usize) -> Option<u32> {
        match (addr, size) {
            (CONFIG0, 2) => Some(self.config0 as u32),
            (CONTROL, 1) => Some(self.control as u32),
            (DATA, 2) => Some(self.rx_fifo.pop_front().unwrap_or(0) as u32),
            (STATUS, 1) => Some(self.status() as u32),
            (CLOCK_MODE, 1) => Some(self.clock_mode as u32),
            (CONFIG14, 1) => Some(self.config14 as u32),
            (ERROR_STATUS, 1) => Some(if self.rx_overrun { ERROR_RX_OVERRUN as u32 } else { 0 }),
            (ERROR_CLEAR, 1) => Some(self.error_clear as u32),
            (CONFIG24, 1) => Some(self.config24 as u32),
            _ => None,
        }
    }

    fn write_register(&mut self, addr: u64, size: usize, value: u32) {
        match (addr, size) {
            (CONFIG0, 2) => self.config0 = value as u16,
            (CONTROL, 1) => self.control = value as u8,
            (DATA, 2) => self.write_data(value as u16),
            (CLOCK_MODE, 1) => self.clock_mode = value as u8,
            (CONFIG14, 1) => self.config14 = value as u8,
            (ERROR_CLEAR, 1) => {
                self.error_clear = value as u8;
                if value as u8 & CLEAR_RX_OVERRUN != 0 {
                    self.rx_overrun = false;
                }
            }
            (CONFIG24, 1) => self.config24 = value as u8,
            _ => {}
        }
    }
}

/// Return Some(value) only for the MMIO registers implemented here.
/// Unsupported widths fall through to the emulator's existing backing store.
pub fn read(spi: &mut SpiHost, addr: u64, size: usize) -> Option<u32> {
    spi.read_register(addr, size)
}

pub fn write(spi: &mut SpiHost, addr: u64, size: usize, value: u32) {
    spi.write_register(addr, size, value);
}

#[cfg(test)]
mod tests {
    use super::*;

    struct EchoTarget;

    impl SpiTarget for EchoTarget {
        fn transfer_word(&mut self, tx_word: u16) -> Option<u16> {
            Some(tx_word ^ 0xA55A)
        }
    }

    #[test]
    fn master_enable_exposes_tx_ready_without_fabricating_rx_data() {
        let mut spi = SpiHost::default();
        assert_eq!(read(&mut spi, STATUS, 1), Some(0));

        write(&mut spi, CONTROL, 1, MASTER_ENABLE as u32);
        assert_eq!(read(&mut spi, STATUS, 1), Some(STATUS_TX_READY as u32));

        write(&mut spi, DATA, 2, 0x1234);
        assert_eq!(spi.tx_word_count(), 1);
        assert_eq!(read(&mut spi, STATUS, 1), Some(STATUS_TX_READY as u32));
        assert_eq!(spi.rx_word_count(), 0);
    }

    #[test]
    fn attached_target_response_sets_rx_ready_until_data_word_is_read() {
        let mut spi = SpiHost::default();
        spi.attach_target(Box::new(EchoTarget));
        write(&mut spi, CONTROL, 1, MASTER_ENABLE as u32);

        write(&mut spi, DATA, 2, 0x1234);
        assert_eq!(
            read(&mut spi, STATUS, 1),
            Some((STATUS_TX_READY | STATUS_RX_READY) as u32)
        );
        assert_eq!(read(&mut spi, DATA, 2), Some((0x1234 ^ 0xA55A) as u32));
        assert_eq!(read(&mut spi, STATUS, 1), Some(STATUS_TX_READY as u32));
        assert_eq!(spi.tx_word_count(), 1);
        assert_eq!(spi.rx_word_count(), 1);
    }

    #[test]
    fn observed_overrun_clear_write_clears_rx_overrun_flag() {
        let mut spi = SpiHost::default();
        spi.rx_overrun = true;
        assert_eq!(
            read(&mut spi, ERROR_STATUS, 1),
            Some(ERROR_RX_OVERRUN as u32)
        );
        write(&mut spi, ERROR_CLEAR, 1, CLEAR_RX_OVERRUN as u32);
        assert_eq!(read(&mut spi, ERROR_STATUS, 1), Some(0));
    }

    #[test]
    fn register_widths_are_not_silently_aliased() {
        let mut spi = SpiHost::default();
        write(&mut spi, CONFIG0, 1, 0xFF);
        assert_eq!(read(&mut spi, CONFIG0, 2), Some(0));
        write(&mut spi, CONTROL, 2, 0xFFFF);
        assert_eq!(read(&mut spi, CONTROL, 1), Some(0));
    }
}
