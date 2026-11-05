use std::cell::Cell;
use std::error::Error;
use std::fs::File;
use std::io::Read;
use std::path::PathBuf;
use std::rc::Rc;

use c3emu::fpsx::{Fpsx, Region};
use c3emu::machine::{parse_mmio_model, Machine};
use c3emu::uc::{Prot, RegisterARM};
use zip::ZipArchive;

const DEFAULT_INSTRUCTIONS: u64 = 1_000_000;
const DEFAULT_TRACE: u64 = 96;
const MAX_ARCHIVE_BYTES: u64 = 512 * 1024 * 1024;
const MAX_MCU_BYTES: u64 = 256 * 1024 * 1024;
const XIP_MINIMUM_MAP: u64 = 0x0100_0000;

#[derive(Debug)]
struct Options {
    archive: PathBuf,
    instructions: u64,
    trace: u64,
    initial_registers: [u32; 4],
}

fn parse_u64(value: &str) -> Result<u64, String> {
    if let Some(hex) = value.strip_prefix("0x").or_else(|| value.strip_prefix("0X")) {
        u64::from_str_radix(hex, 16).map_err(|e| format!("invalid hexadecimal value {value}: {e}"))
    } else {
        value.parse::<u64>().map_err(|e| format!("invalid number {value}: {e}"))
    }
}

fn parse_options() -> Result<Options, String> {
    let mut archive = None;
    let mut instructions = DEFAULT_INSTRUCTIONS;
    let mut trace = DEFAULT_TRACE;
    let mut initial_registers = [0u32; 4];

    for arg in std::env::args().skip(1) {
        if let Some(value) = arg.strip_prefix("--instructions=") {
            instructions = parse_u64(value)?;
        } else if let Some(value) = arg.strip_prefix("--trace=") {
            trace = parse_u64(value)?;
        } else if let Some(value) = arg.strip_prefix("--r0=") {
            initial_registers[0] = parse_u64(value)? as u32;
        } else if let Some(value) = arg.strip_prefix("--r1=") {
            initial_registers[1] = parse_u64(value)? as u32;
        } else if let Some(value) = arg.strip_prefix("--r2=") {
            initial_registers[2] = parse_u64(value)? as u32;
        } else if let Some(value) = arg.strip_prefix("--r3=") {
            initial_registers[3] = parse_u64(value)? as u32;
        } else if arg == "--help" || arg == "-h" {
            return Err(String::new());
        } else if archive.is_none() {
            archive = Some(PathBuf::from(arg));
        } else {
            return Err(format!("unknown argument: {arg}"));
        }
    }

    let archive = archive.ok_or_else(|| {
        "usage: rm217-boot-probe <RM-217.zip> [--instructions=1000000] [--trace=96] [--r0=0] [--r1=0] [--r2=0] [--r3=0]".to_string()
    })?;
    Ok(Options { archive, instructions, trace, initial_registers })
}

fn read_mcusw_from_archive(path: &PathBuf) -> Result<Vec<u8>, Box<dyn Error>> {
    let file = File::open(path)?;
    if file.metadata()?.len() > MAX_ARCHIVE_BYTES {
        return Err("ZIP archive exceeds 512 MiB".into());
    }
    let mut archive = ZipArchive::new(file)?;

    let mut matches = Vec::new();
    for index in 0..archive.len() {
        let entry = archive.by_index(index)?;
        let basename = entry.name().replace('\\', "/");
        let basename = basename.rsplit('/').next().unwrap_or(&basename);
        if basename.eq_ignore_ascii_case("rm217__07.30.mcusw") {
            matches.push(index);
        }
    }
    if matches.len() != 1 {
        return Err(format!(
            "expected exactly one rm217__07.30.mcusw in the archive, found {}",
            matches.len()
        ).into());
    }

    let mut entry = archive.by_index(matches[0])?;
    if entry.size() == 0 || entry.size() > MAX_MCU_BYTES {
        return Err("MCU component size is outside the accepted 1..256 MiB range".into());
    }
    let mut bytes = Vec::with_capacity(entry.size() as usize);
    entry.read_to_end(&mut bytes)?;
    if bytes.len() as u64 != entry.size() {
        return Err("MCU component size differs from ZIP metadata".into());
    }
    println!(
        "Loaded {} from ZIP entry {} ({} bytes).",
        "rm217__07.30.mcusw",
        entry.name(),
        bytes.len()
    );
    Ok(bytes)
}

fn select_main_mcusw(regions: &[Region]) -> Result<&Region, Box<dyn Error>> {
    let candidates: Vec<&Region> = regions
        .iter()
        .filter(|region| region.name.as_deref() == Some("MCUSW"))
        .collect();
    candidates
        .into_iter()
        .max_by_key(|region| region.data.len())
        .ok_or_else(|| "MCU FPSX has no named MCUSW region".into())
}

fn read_u32_le(bytes: &[u8], offset: usize) -> Result<u32, Box<dyn Error>> {
    let end = offset.checked_add(4).ok_or("offset overflow")?;
    let slice = bytes.get(offset..end).ok_or("truncated MCUSW header")?;
    Ok(u32::from_le_bytes(slice.try_into()?))
}

fn run_probe(options: Options) -> Result<(), Box<dyn Error>> {
    println!("=== RM-217 FIRST-STAGE CPU PROBE ===");
    println!("Archive: {}", options.archive.display());
    println!("Instruction budget: {}", options.instructions);
    println!(
        "Initial r0-r3: 0x{:08X}, 0x{:08X}, 0x{:08X}, 0x{:08X}",
        options.initial_registers[0],
        options.initial_registers[1],
        options.initial_registers[2],
        options.initial_registers[3]
    );
    println!("Firmware stays local; the probe does not extract or upload it.");

    let mcusw = read_mcusw_from_archive(&options.archive)?;
    let firmware = Fpsx::parse(&mcusw)?;
    let main = select_main_mcusw(&firmware.regions)?;
    println!(
        "Selected MCUSW: flash=0x{:08X}, bytes={}, blocks={}",
        main.addr,
        main.data.len(),
        main.blocks
    );

    if main.data.len() < 0x14C || read_u32_le(&main.data, 0)? != 0x8097_95A3 {
        return Err("selected MCUSW does not contain a BB5 OS header".into());
    }
    let entry_offset = read_u32_le(&main.data, 0x1C)? as usize;
    let header_hint = read_u32_le(&main.data, 0x148)?;
    let entry_word = read_u32_le(&main.data, entry_offset)?;
    let xip_entry_literal = read_u32_le(&main.data, entry_offset + 0x74)?;
    let xip_base = read_u32_le(&main.data, entry_offset + 0x78)?;
    let stack_cookie = read_u32_le(&main.data, entry_offset + 0x68)?;
    let stack_reserve = read_u32_le(&main.data, entry_offset + 0x6C)?;
    let initial_sp = read_u32_le(&main.data, entry_offset + 0x70)?;
    let entry = xip_base.wrapping_add(entry_offset as u32);
    let thumb_entry = xip_base.wrapping_add(entry_offset as u32 + 0x40);
    let scratch = initial_sp.wrapping_sub(stack_reserve);

    println!("BB5 header entry offset: 0x{entry_offset:X}; hint: 0x{header_hint:08X}");
    println!("First ARM word: 0x{entry_word:08X}");
    println!("Candidate XIP base: 0x{xip_base:08X}");
    println!("Candidate ARM entry: 0x{entry:08X}");
    println!("Candidate Thumb continuation: 0x{thumb_entry:08X}");
    println!("Early stack: 0x{initial_sp:08X}");
    println!("Early scratch: 0x{scratch:08X}, marker=0x{stack_cookie:08X}");
    println!(
        "XIP entry literal agrees: {}",
        if xip_entry_literal == entry { "yes" } else { "NO" }
    );
    println!();
    println!(
        "WARNING: this uses c3emu's RM-614 machine/peripheral model as a diagnostic scaffold. \
         It is not an RM-217 hardware model and a stop/fault is expected until the right \
         memory map, boot handoff and devices are implemented."
    );

    let mmio = parse_mmio_model("{}").map_err(std::io::Error::other)?;
    let mut machine = Machine::new(0, mmio).map_err(std::io::Error::other)?;
    let map_size = ((main.data.len() as u64 + 0xFFF) & !0xFFF).max(XIP_MINIMUM_MAP);
    machine
        .uc
        .mem_map(xip_base as u64, map_size, Prot::ALL)
        .map_err(|error| std::io::Error::other(format!("XIP map failed: {error:?}")))?;
    machine
        .uc
        .mem_write(xip_base as u64, &main.data)
        .map_err(|error| std::io::Error::other(format!("MCUSW load failed: {error:?}")))?;

    // The first-stage stub changes to SYS mode itself. Begin with a masked SVC state.
    machine
        .uc
        .reg_write(RegisterARM::CPSR, 0xD3u64)
        .map_err(|error| std::io::Error::other(format!("CPSR setup failed: {error:?}")))?;
    for (register, value) in [
        (RegisterARM::R0, options.initial_registers[0]),
        (RegisterARM::R1, options.initial_registers[1]),
        (RegisterARM::R2, options.initial_registers[2]),
        (RegisterARM::R3, options.initial_registers[3]),
    ] {
        machine
            .uc
            .reg_write(register, value as u64)
            .map_err(|error| std::io::Error::other(format!("register setup failed: {error:?}")))?;
    }

    // The first 100k-instruction pass found these addresses as the first
    // writes outside the RM-614 map. Record both values and the guest PC.
    for address in [0x201E_F000u64, 0x201F_D800] {
        machine
            .watch(address)
            .map_err(std::io::Error::other)?;
    }

    // Retain the reference probe's candidate clock/controller watchpoints too.
    for address in [0xD800_0094u64, 0xD800_00A4, 0xE000_1000, 0xE000_3000] {
        if let Err(error) = machine.iolog(address, 4) {
            println!("Could not attach I/O watch at 0x{address:08X}: {error}");
        }
    }

    // Capture the hand-off path instruction by instruction. The range contains
    // the ARM entry stub and its first Thumb continuation; a shared cap keeps
    // loop diagnostics bounded even if the ROM spins here.
    let state_trace_count = Rc::new(Cell::new(0usize));
    for address in (0xF010_03D0u64..=0xF010_04A0).step_by(2) {
        let count = Rc::clone(&state_trace_count);
        machine
            .uc
            .add_code_hook(address, address, move |uc, pc, _size| {
                let n = count.get();
                if n >= 160 {
                    return;
                }
                count.set(n + 1);
                let cpsr = uc.reg_read(RegisterARM::CPSR).unwrap_or(0) as u32;
                let r4 = uc.reg_read(RegisterARM::R4).unwrap_or(0) as u32;
                let r5 = uc.reg_read(RegisterARM::R5).unwrap_or(0) as u32;
                let sp = uc.reg_read(RegisterARM::SP).unwrap_or(0) as u32;
                println!(
                    "  [state {n:03}] pc=0x{pc:08X} {} cpsr=0x{cpsr:08X} r4=0x{r4:08X} r5=0x{r5:08X} sp=0x{sp:08X}",
                    if cpsr & 0x20 != 0 { "T" } else { "A" }
                );
            })
            .map_err(|error| {
                std::io::Error::other(format!(
                    "could not attach boot-state hook at 0x{address:08X}: {error:?}"
                ))
            })?;
    }

    if options.trace > 0 {
        machine.enable_trace(0, options.trace);
    }
    let stop = machine.run(entry as u64, options.instructions);
    println!();
    println!("=== PROBE STOP ===");
    println!("{stop}");
    machine.report();
    Ok(())
}

fn main() {
    match parse_options() {
        Ok(options) => {
            if let Err(error) = run_probe(options) {
                eprintln!("ERROR: {error}");
                std::process::exit(1);
            }
        }
        Err(message) if message.is_empty() => {
            println!("Usage: rm217-boot-probe <RM-217.zip> [--instructions=1000000] [--trace=96] [--r0=0] [--r1=0] [--r2=0] [--r3=0]");
        }
        Err(message) => {
            eprintln!("{message}");
            std::process::exit(2);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn calculates_candidate_entry_aliases_and_stack_values() {
        let mut image = vec![0u8; 0x2000];
        image[0..4].copy_from_slice(&0x8097_95A3u32.to_le_bytes());
        image[0x1C..0x20].copy_from_slice(&0x3D0u32.to_le_bytes());
        image[0x148..0x14C].copy_from_slice(&0x3D0u32.to_le_bytes());
        image[0x3D0..0x3D4].copy_from_slice(&0xE24F_4008u32.to_le_bytes());
        image[0x3D0 + 0x68..0x3D0 + 0x6C].copy_from_slice(&0x0673_08CAu32.to_le_bytes());
        image[0x3D0 + 0x6C..0x3D0 + 0x70].copy_from_slice(&0x1800u32.to_le_bytes());
        image[0x3D0 + 0x70..0x3D0 + 0x74].copy_from_slice(&0x0800_4000u32.to_le_bytes());
        image[0x3D0 + 0x74..0x3D0 + 0x78].copy_from_slice(&0xF010_03D0u32.to_le_bytes());
        image[0x3D0 + 0x78..0x3D0 + 0x7C].copy_from_slice(&0xF010_0000u32.to_le_bytes());
        let entry_offset = read_u32_le(&image, 0x1C).unwrap() as usize;
        assert_eq!(read_u32_le(&image, entry_offset + 0x74).unwrap(), 0xF010_03D0);
        assert_eq!(read_u32_le(&image, entry_offset + 0x78).unwrap(), 0xF010_0000);
        assert_eq!(0xF010_0000u32 + entry_offset as u32 + 0x40, 0xF010_0410);
        assert_eq!(0x0800_4000u32 - 0x1800, 0x0800_2800);
    }
}
