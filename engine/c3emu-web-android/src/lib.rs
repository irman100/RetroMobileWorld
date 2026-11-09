//! c3emu in the browser. The emulator (interpreter backend) behind a small C ABI that
//! the page's worker (www/worker.js) drives: hand over the firmware files, boot, run
//! for a time budget, fetch the screen / audio, send keys, and reach the phone's C:
//! drive (file panel) and its flash images (saved in IndexedDB).
//!
//! Buffers: the page allocates with c3_alloc, passes (ptr, len); results that are not
//! plain numbers (JSON, file contents, error text) are left in a result buffer read
//! with c3_result_ptr / c3_result_len.

mod fs;

use std::cell::RefCell;
use std::any::Any;
use std::panic::{catch_unwind, AssertUnwindSafe};

use c3emu::clock::Instant;
use c3emu::machine::{parse_mmio_model, Machine};
use c3emu::storage::{MemDisks, SparseDisk, DEFAULT_DEV_SECTORS, SECTOR};

const MODEL: &str = c3emu::machine::DEFAULT_MMIO_MODEL;
/// File kinds handed over with c3_set_file.
const FIRMWARE: usize = 0;
const PPM: usize = 1;
const CONTENT: usize = 2;
/// The C: drive (XSR device 0).
const DRIVE_C: u32 = 0;
/// Limit periodic Android diagnostics to a comfortably Logcat-safe payload.
const TRACE_TOUCH_LIMIT: usize = 40;
const TRACE_LOG_MAX_BYTES: usize = 3_072;
/// Firmware-identified SPI host-controller MMIO range (SOC_IO).
const SPI_IOLOG_BASE: u64 = 0x088D_0000;
const SPI_IOLOG_LENGTH: u64 = 0x2_000;
const BUS_TRACE_EVENT_LIMIT: usize = 24;
const BUS_TRACE_LOG_MAX_BYTES: usize = 3_072;
/// Bound exact-mode instruction slices so the upstream 20,000-event list cannot
/// fill between Android's periodic log snapshots.
const BUS_TRACE_SLICE_INSNS: u64 = 8_192;

#[derive(Default)]
struct Emu {
    files: [Option<Vec<u8>>; 3],
    disks: MemDisks,
    machine: Option<Machine<'static>>,
    sink: c3emu::audio::Sink,
    frame: Vec<u8>,
    audio: Vec<i16>,
    result: Vec<u8>,
    stopped: Option<String>,
    /// Rolling SPI-host transaction window, harvested at every bounded CPU slice.
    bus_trace: Vec<(u64, String)>,
    /// Lifetime counts for the selected SPI range and unrelated native events.
    bus_trace_total: u64,
    bus_trace_overwritten: u64,
    bus_trace_other_events: u64,
    /// Time of day for the phone's clock at boot (seconds since 2000).
    epoch: Option<u64>,
}

thread_local! {
    static EMU: RefCell<Emu> = RefCell::new(Emu::default());
}

fn with<T>(f: impl FnOnce(&mut Emu) -> T) -> T {
    EMU.with(|e| f(&mut e.borrow_mut()))
}

fn set_result(e: &mut Emu, data: Vec<u8>) {
    e.result = data;
}

fn panic_description(payload: &(dyn Any + Send)) -> String {
    if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_string()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "non-string Rust panic".to_string()
    }
}

/// Local calendar fields expected by the RM-614 PMU RTC registers.
fn host_rtc_fields(epoch: u64) -> [u8; 7] {
    let days = (epoch / 86_400) as i64;
    let remainder = epoch % 86_400;
    let z = days + 730_425;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let year = yoe + era * 400;
    let day_of_year = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let month_part = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * month_part + 2) / 5 + 1;
    let month = if month_part < 10 { month_part + 3 } else { month_part - 9 };
    let year = if month <= 2 { year + 1 } else { year };
    let weekday = (days + 6).rem_euclid(7);
    [
        (remainder % 60) as u8,
        (remainder / 60 % 60) as u8,
        (remainder / 3_600) as u8,
        weekday as u8,
        day as u8,
        month as u8,
        (year - 2_000).clamp(0, 255) as u8,
    ]
}

fn json_str(s: &str) -> String {
    let mut o = String::from("\"");
    for c in s.chars() {
        match c {
            '"' => o.push_str("\""),
            '\\' => o.push_str("\\"),
            c if (c as u32) < 0x20 => o.push_str(&format!("\\u{:04x}", c as u32)),
            c => o.push(c),
        }
    }
    o.push('"');
    o
}

/// Compact, bounded diagnostic of recent unique MMIO first-touch records.
/// This is address discovery, not an access-by-access SPI transaction trace.
fn compact_trace_json(
    running: bool,
    stopped: Option<&str>,
    sim_timeouts: u64,
    isi_messages: u64,
    touches: &[c3emu::machine::Touch],
) -> String {
    let stopped_json = stopped
        .map(|message| {
            let shortened: String = message.chars().take(160).collect();
            json_str(&shortened)
        })
        .unwrap_or_else(|| "null".into());

    let mut recent = touches
        .iter()
        .rev()
        .filter(|touch| touch.region == "periph" || touch.region == "soc")
        .take(TRACE_TOUCH_LIMIT)
        .collect::<Vec<_>>();
    recent.reverse();

    let first_touch = recent
        .iter()
        .map(|touch| {
            let region = if touch.region == "soc" { "s" } else { "p" };
            format!(
                "[\"{region}\",\"{}\",\"{:08X}\",\"{:08X}\",\"{:08X}\"]",
                touch.op,
                touch.addr as u32,
                touch.value as u32,
                touch.pc as u32
            )
        })
        .collect::<Vec<_>>()
        .join(",");

    format!(
        "{{\"running\":{running},\"stopped\":{stopped_json},\"simTimeouts\":{sim_timeouts},\"isiMessages\":{isi_messages},\"firstTouch\":[{first_touch}]}}"
    )
}

/// True for one access-by-access event in the firmware-identified SPI-host range.
fn is_spi_bus_event(line: &str) -> bool {
    let mut parts = line.split_whitespace();
    if parts.next() != Some("io") {
        return false;
    }
    let Some(op) = parts.next() else { return false };
    if !(op.starts_with('R') || op.starts_with('W')) {
        return false;
    }
    let Some(address) = parts.next().and_then(|part| part.strip_prefix("0x")) else {
        return false;
    };
    let Ok(address) = u32::from_str_radix(address, 16) else {
        return false;
    };
    (SPI_IOLOG_BASE..SPI_IOLOG_BASE + SPI_IOLOG_LENGTH).contains(&(address as u64))
}

/// Move the current upstream event batch into a dedicated rolling SPI window.
///
/// The Android wrapper does not call Machine::report(); its user-visible diagnostics
/// are C3EmuTrace and C3EmuBus. Draining each bounded CPU slice prevents unrelated
/// events from consuming the upstream State::events 20,000-entry lifetime cap.
fn collect_bus_trace_events(
    events: &mut Vec<(u64, String)>,
    ring: &mut Vec<(u64, String)>,
    event_total: &mut u64,
    overwritten: &mut u64,
    other_event_total: &mut u64,
) {
    for (icount, line) in events.drain(..) {
        if is_spi_bus_event(&line) {
            *event_total = (*event_total).saturating_add(1);
            if ring.len() >= BUS_TRACE_EVENT_LIMIT {
                ring.remove(0);
                *overwritten = (*overwritten).saturating_add(1);
            }
            ring.push((icount, line));
        } else {
            *other_event_total = (*other_event_total).saturating_add(1);
        }
    }
}

/// Compact rolling SPI-host MMIO records. Each row is
/// [icount, operation+width, address, value, guest_pc].
fn compact_bus_trace_json(
    events: &[(u64, String)],
    event_count: u64,
    overwritten: u64,
    other_event_count: u64,
) -> String {
    let mut parsed = events
        .iter()
        .filter_map(|(icount, line)| {
            let mut parts = line.split_whitespace();
            if parts.next()? != "io" {
                return None;
            }
            let op = parts.next()?;
            if !(op.starts_with('R') || op.starts_with('W')) {
                return None;
            }
            let addr = u32::from_str_radix(parts.next()?.strip_prefix("0x")?, 16).ok()?;
            if !(SPI_IOLOG_BASE..SPI_IOLOG_BASE + SPI_IOLOG_LENGTH).contains(&(addr as u64)) {
                return None;
            }
            if parts.next()? != "=" {
                return None;
            }
            let value = u32::from_str_radix(parts.next()?.strip_prefix("0x")?, 16).ok()?;
            let pc = u32::from_str_radix(parts.next()?.strip_prefix("pc=0x")?, 16).ok()?;
            Some((*icount, op.to_string(), addr, value, pc))
        })
        .collect::<Vec<_>>();

    let keep_from = parsed.len().saturating_sub(BUS_TRACE_EVENT_LIMIT);
    let recent = &parsed[keep_from..];
    let buffered_count = recent.len();
    let rows = recent
        .iter()
        .map(|(icount, op, addr, value, pc)| {
            format!(
                "[{icount},{},{},{},{}]",
                json_str(op),
                json_str(&format!("{addr:08X}")),
                json_str(&format!("{value:08X}")),
                json_str(&format!("{pc:08X}"))
            )
        })
        .collect::<Vec<_>>()
        .join(",");
    parsed.clear();

    format!(
        "{{\"range\":\"088D0000-088D1FFF\",\"eventCount\":{event_count},\"bufferedCount\":{buffered_count},\"overwritten\":{overwritten},\"otherEventCount\":{other_event_count},\"events\":[{rows}]}}"
    )
}

/// # Safety
/// (ptr, len) must come from c3_alloc.
unsafe fn take_vec(ptr: *mut u8, len: usize) -> Vec<u8> {
    unsafe { Vec::from_raw_parts(ptr, len, len) }
}

/// # Safety
/// (ptr, len) must be a live buffer from c3_alloc.
unsafe fn borrow_str<'a>(ptr: *const u8, len: usize) -> &'a str {
    unsafe { std::str::from_utf8(std::slice::from_raw_parts(ptr, len)).unwrap_or("") }
}

#[unsafe(no_mangle)]
pub extern "C" fn c3_alloc(len: usize) -> *mut u8 {
    let mut v = vec![0u8; len];
    let p = v.as_mut_ptr();
    std::mem::forget(v);
    p
}

/// # Safety
/// (ptr, len) must come from c3_alloc and not be used afterwards.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn c3_free(ptr: *mut u8, len: usize) {
    drop(unsafe { take_vec(ptr, len) });
}

#[unsafe(no_mangle)]
pub extern "C" fn c3_result_ptr() -> *const u8 {
    with(|e| e.result.as_ptr())
}

#[unsafe(no_mangle)]
pub extern "C" fn c3_result_len() -> usize {
    with(|e| e.result.len())
}

/// Hand over a firmware file (kind 0 = MCUSW, 1 = PPM, 2 = content image). Takes
/// ownership of the buffer.
///
/// # Safety
/// (ptr, len) must come from c3_alloc.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn c3_set_file(kind: usize, ptr: *mut u8, len: usize) {
    let v = unsafe { take_vec(ptr, len) };
    with(|e| {
        if kind < 3 {
            e.files[kind] = Some(v);
        }
    });
}

/// Restore one saved 64 KB chunk of a flash image (before c3_boot).
///
/// # Safety
/// (ptr, len) must be a live buffer from c3_alloc.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn c3_disk_load_chunk(dev: u32, off: f64, ptr: *const u8, len: usize) {
    let data = unsafe { std::slice::from_raw_parts(ptr, len) };
    with(|e| {
        let mut d = e.disks.borrow_mut();
        d.entry(dev).or_insert_with(|| SparseDisk::new(0)).load_chunk(off as u64, data);
    });
}

/// Size of a restored flash image (after its chunks).
#[unsafe(no_mangle)]
pub extern "C" fn c3_disk_set_size(dev: u32, size: f64) {
    with(|e| {
        e.disks.borrow_mut().entry(dev).or_insert_with(|| SparseDisk::new(0)).size = size as u64;
    });
}

/// Forget all flash images (factory reset; takes effect at the next boot).
#[unsafe(no_mangle)]
pub extern "C" fn c3_disks_clear() {
    with(|e| e.disks.borrow_mut().clear());
}

/// Chunks of the flash images written since the last call, for saving: records of
/// u32 dev, f64 offset, u32 flags (1 = data follows, 0 = chunk is all zeros), u64 disk
/// size, then 64 KB of data. Returns the record count.
#[unsafe(no_mangle)]
pub extern "C" fn c3_disks_dirty() -> u32 {
    with(|e| {
        let mut out = Vec::new();
        let mut n = 0;
        for (&dev, d) in e.disks.borrow_mut().iter_mut() {
            for off in d.take_dirty() {
                out.extend_from_slice(&dev.to_le_bytes());
                out.extend_from_slice(&(off as f64).to_le_bytes());
                match d.chunk(off) {
                    Some(c) => {
                        out.extend_from_slice(&1u32.to_le_bytes());
                        out.extend_from_slice(&d.size.to_le_bytes());
                        out.extend_from_slice(c);
                    }
                    None => {
                        out.extend_from_slice(&0u32.to_le_bytes());
                        out.extend_from_slice(&d.size.to_le_bytes());
                    }
                }
                n += 1;
            }
        }
        e.result = out;
        n
    })
}

fn boot(e: &mut Emu) -> Result<(), String> {
    e.machine = None;
    e.stopped = None;
    let fw = e.files[FIRMWARE].as_ref().ok_or("no firmware (.mcusw) loaded")?;
    let model = parse_mmio_model(MODEL)?;
    let mut m = Machine::new(0, model)?;
    // factory C: drive from the content image, unless the phone memory already exists
    if !e.disks.borrow().contains_key(&DRIVE_C) {
        if let Some(c) = &e.files[CONTENT] {
            let d = c3emu::content::to_disk(c, DEFAULT_DEV_SECTORS as u64 * SECTOR)?;
            e.disks.borrow_mut().insert(DRIVE_C, d);
        }
    }
    let opt = c3emu::boot::BootOptions {
        oslog_echo: false,
        isi: false,
        storage: c3emu::storage::Backend::Memory(e.disks.clone()),
    };
    let (entry, _log) = c3emu::boot::setup(&mut m, fw, e.files[PPM].as_deref(), opt)?;
    if let Ok(mut q) = e.sink.lock() {
        q.clear();
    }
    m.st.borrow_mut().audio.sink = Some(e.sink.clone());
    {
        // paced to real time (the phone sleeps at WFI while ahead of the wall clock)
        let mut st = m.st.borrow_mut();
        let tick = c3emu::soc::now(&st);
        st.soc.realtime = Some((Instant::now(), tick));
        if let Some(ep) = e.epoch {
            st.rtc.epoch = ep;
        }
    }
    m.iolog(SPI_IOLOG_BASE, SPI_IOLOG_LENGTH)
        .map_err(|error| format!("unable to enable SPI-host MMIO trace: {error}"))?;
    m.fast_start(entry);
    e.machine = Some(m);
    Ok(())
}

/// The host's local time (Unix seconds and UTC offset in seconds) for the phone's
/// real-time clock at the next boot.
#[unsafe(no_mangle)]
pub extern "C" fn c3_set_time(unix: f64, utc_offset: f64) {
    if !unix.is_finite() || !utc_offset.is_finite() {
        return;
    }
    let epoch = c3emu::rtc::epoch_from_unix(unix as i64, utc_offset as i64);
    with(|e| {
        e.epoch = Some(epoch);
        // Also update the running phone through the same PMU register path used
        // by the guest clock editor, keeping the guest's current timer base.
        if let Some(machine) = e.machine.as_ref() {
            let fields = host_rtc_fields(epoch);
            let mut state = machine.st.borrow_mut();
            for (index, value) in fields.into_iter().enumerate() {
                c3emu::rtc::pmu_write(
                    &mut state,
                    c3emu::rtc::RTC_FIRST + index as u8,
                    value,
                );
            }
        }
    });
}

/// Boot the phone (again) from the files handed over. 0 = ok, else the error text is
/// in the result buffer.
#[unsafe(no_mangle)]
pub extern "C" fn c3_boot() -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        with(|e| {
            e.bus_trace.clear();
            e.bus_trace_total = 0;
            e.bus_trace_overwritten = 0;
            e.bus_trace_other_events = 0;
            match boot(e) {
                Ok(()) => 0,
                Err(msg) => {
                    set_result(e, msg.into_bytes());
                    1
                }
            }
        })
    }));
    match result {
        Ok(status) => status,
        Err(payload) => {
            let message = format!("Rust panic caught during c3emu boot: {}", panic_description(payload.as_ref()));
            with(|e| {
                e.machine = None;
                e.stopped = Some(message.clone());
                set_result(e, message.into_bytes());
            });
            1
        }
    }
}

/// Run for about `budget_ms`. Returns how long the page should wait before the next
/// call (the phone is idle until then; 0 = call again soon), or -1 when the emulator
/// stopped (reason in the result buffer).
#[unsafe(no_mangle)]
pub extern "C" fn c3_run(budget_ms: f64) -> f64 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        with(|e| {
            let Some(m) = e.machine.as_mut() else { return -1.0 };
            if e.stopped.is_some() {
                return -1.0;
            }
            let t0 = Instant::now();
            loop {
                let current_icount = m.st.borrow().icount;
                let slice_limit = current_icount.saturating_add(BUS_TRACE_SLICE_INSNS);
                let slice_result = m.fast_slice(slice_limit);
                {
                    let mut state = m.st.borrow_mut();
                    collect_bus_trace_events(
                        &mut state.events,
                        &mut e.bus_trace,
                        &mut e.bus_trace_total,
                        &mut e.bus_trace_overwritten,
                        &mut e.bus_trace_other_events,
                    );
                }
                if let Err(why) = slice_result {
                    e.result = why.clone().into_bytes();
                    e.stopped = Some(why);
                    return -1.0;
                }
                let slept = c3emu::clock::take_sleep();
                if slept > 0.0 {
                    return slept;
                }
                if t0.elapsed().as_secs_f64() * 1000.0 >= budget_ms {
                    return 0.0;
                }
            }
        })
    }));
    match result {
        Ok(wait_ms) => wait_ms,
        Err(payload) => {
            let message = format!("Rust panic caught during c3emu CPU run: {}", panic_description(payload.as_ref()));
            with(|e| {
                // Drop only the failed volatile CPU session. Persistent flash stays
                // in Emu::disks so Android can offer a manual reboot without data loss.
                e.machine = None;
                e.stopped = Some(message.clone());
                set_result(e, message.into_bytes());
            });
            -1.0
        }
    }
}

/// The screen as 320 x 240 RGBA (refreshed by this call).
#[unsafe(no_mangle)]
pub extern "C" fn c3_frame() -> *const u8 {
    with(|e| {
        let (w, h) = (c3emu::lcd::WIDTH, c3emu::lcd::HEIGHT);
        e.frame.resize(w * h * 4, 255);
        if let Some(m) = &e.machine {
            let st = m.st.borrow();
            for (px, rgb) in e.frame.chunks_exact_mut(4).zip(st.panel.gram.chunks_exact(3)) {
                px[..3].copy_from_slice(rgb);
                px[3] = 255;
            }
        }
        e.frame.as_ptr()
    })
}

/// Changes whenever the screen may have changed.
#[unsafe(no_mangle)]
pub extern "C" fn c3_frame_serial() -> u32 {
    with(|e| e.machine.as_ref().map_or(0, |m| m.st.borrow().panel.pixels_written as u32))
}

/// Take the audio produced so far (interleaved stereo i16 at 44.1 kHz): returns the
/// sample count; the samples are at c3_audio_ptr.
#[unsafe(no_mangle)]
pub extern "C" fn c3_audio() -> usize {
    with(|e| {
        e.audio.clear();
        if let Ok(mut q) = e.sink.lock() {
            e.audio.extend(q.drain(..));
        }
        e.audio.len()
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn c3_audio_ptr() -> *const i16 {
    with(|e| e.audio.as_ptr())
}

/// A key changed: matrix index (row * 8 + column) or 255 for the red key. Short presses
/// are single taps, long ones long presses (keypad::host_down / host_up).
#[unsafe(no_mangle)]
pub extern "C" fn c3_key(index: u32, down: u32) {
    with(|e| {
        let Some(m) = &e.machine else { return };
        let mut st = m.st.borrow_mut();
        if down != 0 {
            c3emu::keypad::host_down(&mut st, index as u8);
        } else {
            c3emu::keypad::host_up(&mut st, index as u8);
        }
    })
}

/// Status as JSON: {"running", "stopped", "frames", "changed", "idle", "flash_writes"}.
#[unsafe(no_mangle)]
pub extern "C" fn c3_stats() {
    with(|e| {
        let stopped = e.stopped.as_deref().map(json_str).unwrap_or("null".into());
        let host_epoch = e.epoch.map(|value| value.to_string()).unwrap_or("null".into());
        let snapshot = match &e.machine {
            None => format!(
                "{{\"running\":false,\"stopped\":{stopped},\"host_epoch\":{host_epoch}}}"
            ),
            Some(m) => {
                let st = m.st.borrow();
                let rtc = (0..7)
                    .map(|index| c3emu::rtc::pmu_read(&st, c3emu::rtc::RTC_FIRST + index).to_string())
                    .collect::<Vec<_>>()
                    .join(",");
                let isi_messages: u64 = st.isi_counts.values().copied().sum();
                let isi_counts = st.isi_counts
                    .iter()
                    .map(|((resource, message), count)| {
                        format!("\"{:02x}:{:02x}\":{}", resource, message, count)
                    })
                    .collect::<Vec<_>>()
                    .join(",");
                let mut isi_recent = st.oslog
                    .iter()
                    .rev()
                    .filter(|(_, line)| line.starts_with("ISI ") || line.starts_with("RECV "))
                    .take(24)
                    .map(|(_, line)| json_str(line))
                    .collect::<Vec<_>>();
                isi_recent.reverse();
                let isi_recent = isi_recent.join(",");
                let mut io_touches = st.touches
                    .iter()
                    .rev()
                    .filter(|touch| touch.region == "periph" || touch.region == "soc")
                    .take(64)
                    .map(|touch| {
                        format!(
                            "{{\"region\":{},\"op\":{},\"addr\":\"0x{:08x}\",\"value\":\"0x{:08x}\",\"pc\":\"0x{:08x}\"}}",
                            json_str(touch.region),
                            json_str(&touch.op.to_string()),
                            touch.addr,
                            touch.value,
                            touch.pc
                        )
                    })
                    .collect::<Vec<_>>();
                io_touches.reverse();
                let io_touches = io_touches.join(",");
                format!(
                    "{{\"running\":{},\"stopped\":{stopped},\"host_epoch\":{host_epoch},\"rtc\":[{rtc}],\"sim_timeouts\":{},\"sim_status\":{},\"isi_messages\":{},\"isi_counts\":{{{isi_counts}}},\"isi_recent\":[{isi_recent}],\"io_touches\":[{io_touches}],\"frames\":{},\"changed\":{},\"idle\":{},\"flash_writes\":{}}}",
                    e.stopped.is_none(),
                    st.sim.timeouts,
                    st.sim.status,
                    isi_messages,
                    st.panel.frames,
                    st.panel.frames_changed,
                    st.soc.wfi_count,
                    st.hle_calls.get("storage:write").copied().unwrap_or(0)
                )
            }
        };
        e.result = snapshot.into_bytes();
    })
}

/// Compact Android trace separated from the verbose native statistics snapshot.
#[unsafe(no_mangle)]
pub extern "C" fn c3_trace_stats() {
    with(|e| {
        let snapshot = match e.machine.as_ref() {
            Some(machine) => {
                let st = machine.st.borrow();
                compact_trace_json(
                    e.stopped.is_none(),
                    e.stopped.as_deref(),
                    st.sim.timeouts,
                    st.isi_counts.values().copied().sum(),
                    &st.touches,
                )
            }
            None => compact_trace_json(false, e.stopped.as_deref(), 0, 0, &[]),
        };
        e.result = snapshot.into_bytes();
    })
}

/// Recent access-by-access transactions in the firmware-identified SPI-host range.
#[unsafe(no_mangle)]
pub extern "C" fn c3_bus_trace_stats() {
    with(|e| {
        let snapshot = compact_bus_trace_json(
            &e.bus_trace,
            e.bus_trace_total,
            e.bus_trace_overwritten,
            e.bus_trace_other_events,
        );
        e.result = snapshot.into_bytes();
    })
}

/// Stop the phone (before changing its files; boot again afterwards).
#[unsafe(no_mangle)]
pub extern "C" fn c3_stop() {
    with(|e| e.machine = None);
}

fn fs_result(e: &mut Emu, r: Result<Vec<u8>, String>) -> i32 {
    match r {
        Ok(v) => {
            e.result = v;
            0
        }
        Err(msg) => {
            e.result = msg.into_bytes();
            1
        }
    }
}

fn drive_c<T>(e: &mut Emu, f: impl FnOnce(&mut SparseDisk) -> Result<T, String>) -> Result<T, String> {
    let disks: MemDisks = e.disks.clone();
    let mut d = disks.borrow_mut();
    let disk = d.get_mut(&DRIVE_C).ok_or("the phone memory (C:) does not exist yet: start the phone once")?;
    f(disk)
}

/// List a folder of C: as JSON {"entries":[{"name","dir","size"}], "total", "free"}.
///
/// # Safety
/// (ptr, len) must be a live buffer from c3_alloc.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn c3_fs_list(ptr: *const u8, len: usize) -> i32 {
    let path = unsafe { borrow_str(ptr, len) }.to_string();
    with(|e| {
        let r = drive_c(e, |d| fs::list(d, &path)).map(|(entries, total, free)| {
            let items: Vec<String> = entries.iter().map(|x| {
                format!("{{\"name\":{},\"dir\":{},\"size\":{}}}", json_str(&x.name), x.dir, x.size)
            }).collect();
            format!("{{\"entries\":[{}],\"total\":{total},\"free\":{free}}}", items.join(",")).into_bytes()
        });
        fs_result(e, r)
    })
}

/// Read a file of C: into the result buffer.
///
/// # Safety
/// (ptr, len) must be a live buffer from c3_alloc.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn c3_fs_read(ptr: *const u8, len: usize) -> i32 {
    let path = unsafe { borrow_str(ptr, len) }.to_string();
    with(|e| {
        let r = drive_c(e, |d| fs::read(d, &path));
        fs_result(e, r)
    })
}

/// Write a file to C: (folders created as needed).
///
/// # Safety
/// Both buffers must be live buffers from c3_alloc.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn c3_fs_write(p: *const u8, pl: usize, d: *const u8, dl: usize) -> i32 {
    let path = unsafe { borrow_str(p, pl) }.to_string();
    let data = unsafe { std::slice::from_raw_parts(d, dl) }.to_vec();
    with(|e| {
        let r = drive_c(e, |disk| fs::write(disk, &path, &data)).map(|_| Vec::new());
        fs_result(e, r)
    })
}

/// Create a folder on C:.
///
/// # Safety
/// (ptr, len) must be a live buffer from c3_alloc.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn c3_fs_mkdir(ptr: *const u8, len: usize) -> i32 {
    let path = unsafe { borrow_str(ptr, len) }.to_string();
    with(|e| {
        let r = drive_c(e, |d| fs::mkdir(d, &path)).map(|_| Vec::new());
        fs_result(e, r)
    })
}

/// Delete a file or folder (recursively) on C:.
///
/// # Safety
/// (ptr, len) must be a live buffer from c3_alloc.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn c3_fs_remove(ptr: *const u8, len: usize) -> i32 {
    let path = unsafe { borrow_str(ptr, len) }.to_string();
    with(|e| {
        let r = drive_c(e, |d| fs::remove(d, &path)).map(|_| Vec::new());
        fs_result(e, r)
    })
}

/// Check of the file panel against a content image (C3EMU_CONTENT=FILE cargo test).
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn content_drive_lists_and_writes() {
        let Some(img) = std::env::var_os("C3EMU_CONTENT").and_then(|p| std::fs::read(p).ok()) else { return };
        let mut d = c3emu::content::to_disk(&img, DEFAULT_DEV_SECTORS as u64 * SECTOR).unwrap();
        let (root, total, free) = fs::list(&mut d, "/").unwrap();
        for x in &root {
            println!("{} {} {}", if x.dir { "D" } else { "F" }, x.name, x.size);
        }
        println!("total {total} free {free}");
        fs::write(&mut d, "/Prueba/hola.txt", b"hola mundo").unwrap();
        assert_eq!(fs::read(&mut d, "/Prueba/hola.txt").unwrap(), b"hola mundo");
        fs::remove(&mut d, "/Prueba").unwrap();
        assert!(fs::list(&mut d, "/").unwrap().0.iter().all(|x| x.name != "Prueba"));
    }
}


#[cfg(test)]
mod android_wrapper_tests {
    use super::*;

    #[test]
    fn local_host_calendar_fields_match_leap_day_and_weekday() {
        let epoch = c3emu::rtc::epoch_from_unix(1_709_197_445, 0);
        assert_eq!(host_rtc_fields(epoch), [5, 4, 9, 4, 29, 2, 24]);
    }

    #[test]
    fn host_timezone_offset_is_applied_to_guest_local_clock() {
        let unix = 1_709_197_445;
        let utc = c3emu::rtc::epoch_from_unix(unix, 0);
        let central_europe = c3emu::rtc::epoch_from_unix(unix, 3_600);
        assert_eq!(host_rtc_fields(utc), [5, 4, 9, 4, 29, 2, 24]);
        assert_eq!(host_rtc_fields(central_europe), [5, 4, 10, 4, 29, 2, 24]);
    }

    #[test]
    fn compact_spi_bus_trace_is_bounded_and_keeps_latest_transactions() {
        let mut events = (0..100u64)
            .map(|index| {
                let addr = SPI_IOLOG_BASE + index * 4;
                (
                    index,
                    format!(
                        "io W4 0x{addr:08x} = 0x{:x} pc=0x{:08x}",
                        0xA500_0000u32 + index as u32,
                        0x8081_0000u32 + index as u32
                    ),
                )
            })
            .collect::<Vec<_>>();
        events.push((101, "DSP cmd[0] 000b e540 00aa 0000".into()));
        events.push((
            102,
            "io R4 0x088c0000 = 0x0 pc=0x80810000".into(),
        ));

        let trace = compact_bus_trace_json(&events, 100, 76, 2);

        assert!(trace.len() <= BUS_TRACE_LOG_MAX_BYTES, "trace was {} bytes", trace.len());
        assert!(trace.starts_with("{\"range\":\"088D0000-088D1FFF\""));
        assert!(trace.contains("\"eventCount\":100"));
        assert!(trace.contains("\"bufferedCount\":24"));
        assert!(trace.contains("\"overwritten\":76"));
        assert!(trace.contains("088D018C"));
        assert!(!trace.contains("088D012C"));
        assert!(!trace.contains("088C0000"));
        assert_eq!(trace.matches("],[").count(), BUS_TRACE_EVENT_LIMIT - 1);
    }

    #[test]
    fn spi_trace_drains_the_shared_event_cap_and_keeps_a_rolling_tail() {
        let mut events = Vec::with_capacity(20_000);
        for index in 0..13_868u64 {
            events.push((
                index,
                format!("io R1 0x088d000c = 0x0 pc=0x8073a838"),
            ));
        }
        for index in 13_868..20_000u64 {
            events.push((index, format!("DSP diagnostic event {index}")));
        }

        let mut ring = Vec::new();
        let mut event_total = 0;
        let mut overwritten = 0;
        let mut other_event_total = 0;
        collect_bus_trace_events(
            &mut events,
            &mut ring,
            &mut event_total,
            &mut overwritten,
            &mut other_event_total,
        );

        assert!(events.is_empty(), "the upstream event batch must be drained");
        assert_eq!(event_total, 13_868);
        assert_eq!(other_event_total, 6_132);
        assert_eq!(ring.len(), BUS_TRACE_EVENT_LIMIT);
        assert_eq!(overwritten, 13_868 - BUS_TRACE_EVENT_LIMIT);
        assert_eq!(ring.first().map(|(icount, _)| *icount), Some(13_868 - BUS_TRACE_EVENT_LIMIT as u64));
        assert_eq!(ring.last().map(|(icount, _)| *icount), Some(13_867));

        let trace = compact_bus_trace_json(&ring, event_total, overwritten, other_event_total);
        assert!(trace.len() <= BUS_TRACE_LOG_MAX_BYTES);
        assert!(trace.contains("\"eventCount\":13868"));
        assert!(trace.contains("\"bufferedCount\":24"));
        assert!(trace.contains("\"overwritten\":13844"));
        assert!(trace.contains("\"otherEventCount\":6132"));
        assert!(trace.contains("[13867,"));
    }

    #[test]
    fn compact_mmio_trace_is_bounded_and_keeps_latest_first_touches() {
        let touches = (0..100)
            .map(|index| c3emu::machine::Touch {
                icount: index,
                region: if index % 2 == 0 { "periph" } else { "soc" },
                op: if index % 2 == 0 { 'R' } else { 'W' },
                addr: 0x0880_0000 + index,
                value: 0xA500_0000 + index,
                pc: 0x8070_0000 + index,
            })
            .collect::<Vec<_>>();

        let trace = compact_trace_json(
            true,
            Some(&"watchdog diagnostic ".repeat(20)),
            2,
            5_437,
            &touches,
        );

        assert!(trace.len() <= TRACE_LOG_MAX_BYTES, "trace was {} bytes", trace.len());
        assert!(trace.starts_with("{\"running\":true"));
        assert!(trace.contains("\"firstTouch\":["));
        assert!(trace.contains("08800063"));
        assert!(!trace.contains("0880003B"));
        assert_eq!(trace.matches("],[").count(), TRACE_TOUCH_LIMIT - 1);
    }
}
