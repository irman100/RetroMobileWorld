use std::cell::Cell;
use std::collections::BTreeMap;
use std::error::Error;
use std::fs::File;
use std::io::Read;
use std::path::PathBuf;
use std::rc::Rc;

use c3emu::fpsx::{Fpsx, Region};
use c3emu::uc::{new_cpu, HookType, MemType, Prot, RegisterARM, Uc};
use zip::ZipArchive;

const DEFAULT_INSTRUCTIONS: u64 = 1_000_000;
const DEFAULT_TRACE: u64 = 96;
const MAX_ARCHIVE_BYTES: u64 = 512 * 1024 * 1024;
const MAX_MCU_BYTES: u64 = 256 * 1024 * 1024;
const XIP_MINIMUM_MAP: u64 = 0x0100_0000;

const BOOT_TAGS_ADDR: u32 = 0x0800_6000;
const IMAGE_TABLE_ADDR: u32 = 0x0800_6400;
const PAPUBKEYS_RAM_ADDR: u32 = 0x0801_0000;

#[derive(Debug)]
struct Options {
    archive: PathBuf,
    instructions: u64,
    trace: u64,
    initial_registers: [u32; 4],
    no_handoff: bool,
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
    let mut no_handoff = false;

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
        } else if arg == "--no-handoff" {
            no_handoff = true;
        } else if arg == "--help" || arg == "-h" {
            return Err(String::new());
        } else if archive.is_none() {
            archive = Some(PathBuf::from(arg));
        } else {
            return Err(format!("unknown argument: {arg}"));
        }
    }

    let archive = archive.ok_or_else(|| {
        "usage: rm217-boot-probe <RM-217.zip> [--instructions=1000000] [--trace=96] [--no-handoff] [--r0=0] [--r1=0] [--r2=0] [--r3=0]".to_string()
    })?;
    Ok(Options { archive, instructions, trace, initial_registers, no_handoff })
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
    regions
        .iter()
        .filter(|region| region.name.as_deref() == Some("MCUSW"))
        .max_by_key(|region| region.data.len())
        .ok_or_else(|| "MCU FPSX has no named MCUSW region".into())
}

fn read_u32_le(bytes: &[u8], offset: usize) -> Result<u32, Box<dyn Error>> {
    let end = offset.checked_add(4).ok_or("offset overflow")?;
    let slice = bytes.get(offset..end).ok_or("truncated MCUSW header")?;
    Ok(u32::from_le_bytes(slice.try_into()?))
}

fn append_tag(out: &mut Vec<u8>, tag_type: u32, payload: &[u32]) {
    let size = 8u32 + (payload.len() as u32 * 4);
    out.extend_from_slice(&tag_type.to_le_bytes());
    out.extend_from_slice(&size.to_le_bytes());
    for value in payload {
        out.extend_from_slice(&value.to_le_bytes());
    }
}

fn build_boot_tag_list(image_table: u32) -> Vec<u8> {
    let mut tags = Vec::new();
    append_tag(&mut tags, 0x0D, &[1]); // power-on by key
    append_tag(&mut tags, 0x0A, &[image_table]); // image table pointer
    append_tag(&mut tags, 2, &[]); // end
    tags
}

fn write_image_table(
    uc: &mut Uc<'_>,
    firmware: &Fpsx,
    main_mcusw: &Region,
    xip_base: u32,
) -> Result<usize, Box<dyn Error>> {
    let mut named: BTreeMap<String, (&Region, u32)> = BTreeMap::new();
    for region in &firmware.regions {
        let Some(name) = region.name.as_deref() else { continue };
        let destination = if name == "MCUSW" {
            xip_base
        } else if region.addr == 0xFFFF_FFFF {
            PAPUBKEYS_RAM_ADDR
        } else {
            region.addr
        };
        let replace = named
            .get(name)
            .map(|(current, _)| region.data.len() > current.data.len())
            .unwrap_or(true);
        if replace {
            named.insert(name.to_string(), (region, destination));
        }
    }

    let mut entries = Vec::new();
    for (name, (region, destination)) in named {
        if name != "MCUSW" {
            uc.mem_write(destination as u64, &region.data).map_err(|error| {
                std::io::Error::other(format!(
                    "could not stage image {name} at 0x{destination:08X}: {error:?}"
                ))
            })?;
        } else if region.data.as_slice() != main_mcusw.data.as_slice() {
            return Err("image table MCUSW does not reference selected main executable region".into());
        }

        let mut entry = [0u8; 0x20];
        let relative = destination.wrapping_sub(IMAGE_TABLE_ADDR);
        entry[0..4].copy_from_slice(&relative.to_le_bytes());
        entry[4..8].copy_from_slice(&(region.data.len() as u32).to_le_bytes());
        let name_bytes = name.as_bytes();
        let name_len = name_bytes.len().min(12);
        entry[0x14..0x14 + name_len].copy_from_slice(&name_bytes[..name_len]);
        entries.extend_from_slice(&entry);
        println!(
            "  staged image {name:10} -> 0x{destination:08X} ({} bytes)",
            region.data.len()
        );
    }

    let mut end = [0u8; 0x20];
    end[0x14] = 0xFF;
    entries.extend_from_slice(&end);
    uc.mem_write(IMAGE_TABLE_ADDR as u64, &entries)
        .map_err(|error| std::io::Error::other(format!("image table write failed: {error:?}")))?;
    Ok(entries.len() / 0x20 - 1)
}

fn install_rm217_handoff(
    uc: &mut Uc<'_>,
    firmware: &Fpsx,
    main_mcusw: &Region,
    xip_base: u32,
) -> Result<(), Box<dyn Error>> {
    println!("Installing provisional RM-217 boot hand-off (C3-00 tag ABI reference):");
    let image_count = write_image_table(uc, firmware, main_mcusw, xip_base)?;
    let tags = build_boot_tag_list(IMAGE_TABLE_ADDR);
    uc.mem_write(BOOT_TAGS_ADDR as u64, &tags)
        .map_err(|error| std::io::Error::other(format!("boot-tag write failed: {error:?}")))?;
    println!(
        "  tag list @0x{BOOT_TAGS_ADDR:08X}; image table @0x{IMAGE_TABLE_ADDR:08X}; {image_count} images"
    );
    println!(
        "  r2 points to the tag list; r1 remains zero unless explicitly supplied. \
         This is an experimental hand-off, not a confirmed Nokia RM-217 boot-ROM contract."
    );
    Ok(())
}

fn run_probe(options: Options) -> Result<(), Box<dyn Error>> {
    println!("=== RM-217 FIRST-STAGE CPU PROBE ===");
    println!("Archive: {}", options.archive.display());
    println!("Instruction budget: {}", options.instructions);
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

    // Use the c3emu ARM926 CPU core without its RM-614 Machine wrapper. The latter
    // installs a model-specific peripheral map and a tight-loop heuristic that can
    // mistake legitimate cache-maintenance polling for a fatal guest loop.
    let mut uc = new_cpu().map_err(|error| std::io::Error::other(format!("CPU creation failed: {error:?}")))?;
    uc.mem_map(0x0000_0000, 0x1000_0000, Prot::ALL)
        .map_err(|error| std::io::Error::other(format!("low RAM map failed: {error:?}")))?;
    uc.mem_map(0x8000_0000, 0x0400_0000, Prot::ALL)
        .map_err(|error| std::io::Error::other(format!("SDRAM map failed: {error:?}")))?;
    let map_size = ((main.data.len() as u64 + 0xFFF) & !0xFFF).max(XIP_MINIMUM_MAP);
    uc.mem_map(xip_base as u64, map_size, Prot::ALL)
        .map_err(|error| std::io::Error::other(format!("XIP map failed: {error:?}")))?;
    uc.mem_write(xip_base as u64, &main.data)
        .map_err(|error| std::io::Error::other(format!("MCUSW load failed: {error:?}")))?;

    if !options.no_handoff {
        install_rm217_handoff(&mut uc, &firmware, main, xip_base)?;
    } else {
        println!("Boot hand-off disabled; r0-r3 start from supplied values.");
    }

    uc.reg_write(RegisterARM::CPSR, 0xD3u64)
        .map_err(|error| std::io::Error::other(format!("CPSR setup failed: {error:?}")))?;
    let mut initial = options.initial_registers;
    if !options.no_handoff && initial[2] == 0 {
        initial[2] = BOOT_TAGS_ADDR;
    }
    for (register, value) in [
        (RegisterARM::R0, initial[0]),
        (RegisterARM::R1, initial[1]),
        (RegisterARM::R2, initial[2]),
        (RegisterARM::R3, initial[3]),
    ] {
        uc.reg_write(register, value as u64)
            .map_err(|error| std::io::Error::other(format!("register setup failed: {error:?}")))?;
    }

    let unknown_count = Rc::new(Cell::new(0usize));
    uc.add_mem_hook(HookType::MEM_UNMAPPED, 1, 0, {
        let unknown_count = Rc::clone(&unknown_count);
        move |cpu, kind, address, size, value| {
            let pc = cpu.reg_read(RegisterARM::PC).unwrap_or(0);
            let operation = match kind {
                MemType::READ => "READ",
                MemType::WRITE => "WRITE",
                MemType::FETCH => "FETCH",
                MemType::READ_UNMAPPED => "READ_UNMAPPED",
                MemType::WRITE_UNMAPPED => "WRITE_UNMAPPED",
                MemType::FETCH_UNMAPPED => "FETCH_UNMAPPED",
            };
            let n = unknown_count.get();
            if n < 128 {
                unknown_count.set(n + 1);
                println!(
                    "  [unmapped {n:03}] {operation} addr=0x{address:08X} size={size} value=0x{value:X} pc=0x{pc:08X}"
                );
            }
            cpu.mem_map(address & !0xFFF, 0x1000, Prot::ALL).is_ok()
        }
    })
    .map_err(|error| std::io::Error::other(format!("unmapped hook failed: {error:?}")))?;

    for (begin, end) in [
        (0x201E_F000u64, 0x201E_F003u64),
        (0x201F_D800u64, 0x201F_D803u64),
    ] {
        uc.add_mem_hook(HookType::MEM_WRITE, begin, end, move |cpu, _kind, address, size, value| {
            let pc = cpu.reg_read(RegisterARM::PC).unwrap_or(0);
            println!(
                "  [write] addr=0x{address:08X} size={size} value=0x{value:X} pc=0x{pc:08X}"
            );
            true
        })
        .map_err(|error| std::io::Error::other(format!("write watch failed: {error:?}")))?;
    }

    let trace_count = Rc::new(Cell::new(0usize));
    for (begin, end) in [
        (0xF010_03D0u64, 0xF010_0450u64),
        (0xF010_4EC0u64, 0xF010_4ED0u64),
        (0xF010_3350u64, 0xF010_3380u64),
    ] {
        let trace_count = Rc::clone(&trace_count);
        let trace_limit = options.trace as usize;
        uc.add_code_hook(begin, end, move |cpu, pc, _size| {
            let n = trace_count.get();
            if n >= trace_limit {
                return;
            }
            trace_count.set(n + 1);
            let read = |register| cpu.reg_read(register).unwrap_or(0) as u32;
            let cpsr = read(RegisterARM::CPSR);
            println!(
                "  [trace {n:03}] pc=0x{pc:08X} {} cpsr=0x{cpsr:08X} \
                 r0=0x{:08X} r1=0x{:08X} r2=0x{:08X} r3=0x{:08X} \
                 r4=0x{:08X} r5=0x{:08X} r6=0x{:08X} sp=0x{:08X} lr=0x{:08X}",
                if cpsr & 0x20 != 0 { "T" } else { "A" },
                read(RegisterARM::R0),
                read(RegisterARM::R1),
                read(RegisterARM::R2),
                read(RegisterARM::R3),
                read(RegisterARM::R4),
                read(RegisterARM::R5),
                read(RegisterARM::R6),
                read(RegisterARM::SP),
                read(RegisterARM::LR)
            );
        })
        .map_err(|error| std::io::Error::other(format!("code trace hook failed: {error:?}")))?;
    }

    println!(
        "Initial registers: r0=0x{:08X} r1=0x{:08X} r2=0x{:08X} r3=0x{:08X}",
        uc.reg_read(RegisterARM::R0)? as u32,
        uc.reg_read(RegisterARM::R1)? as u32,
        uc.reg_read(RegisterARM::R2)? as u32,
        uc.reg_read(RegisterARM::R3)? as u32
    );

    let result = uc.emu_start(entry as u64, 0xFFFF_FFF0, 0, options.instructions as usize);
    let stop = result.map(|_| "CPU execution slice ended".to_string())
        .unwrap_or_else(|error| format!("CPU error: {error:?}"));
    let read = |register| uc.reg_read(register).unwrap_or(0) as u32;

    println!();
    println!("=== PROBE STOP ===");
    println!("{stop}");
    println!(
        "PC=0x{:08X}; CPSR=0x{:08X}; SP=0x{:08X}; LR=0x{:08X}",
        read(RegisterARM::PC),
        read(RegisterARM::CPSR),
        read(RegisterARM::SP),
        read(RegisterARM::LR)
    );
    println!(
        "r0=0x{:08X} r1=0x{:08X} r2=0x{:08X} r3=0x{:08X} r4=0x{:08X} r5=0x{:08X} r6=0x{:08X}",
        read(RegisterARM::R0),
        read(RegisterARM::R1),
        read(RegisterARM::R2),
        read(RegisterARM::R3),
        read(RegisterARM::R4),
        read(RegisterARM::R5),
        read(RegisterARM::R6)
    );
    println!("Dynamically mapped pages: {}", unknown_count.get());
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
            println!("Usage: rm217-boot-probe <RM-217.zip> [--instructions=1000000] [--trace=96] [--no-handoff] [--r0=0] [--r1=0] [--r2=0] [--r3=0]");
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

    #[test]
    fn builds_boot_info_with_power_on_and_image_table_tags() {
        let tags = build_boot_tag_list(IMAGE_TABLE_ADDR);
        assert_eq!(u32::from_le_bytes(tags[0..4].try_into().unwrap()), 0x0D);
        assert_eq!(u32::from_le_bytes(tags[8..12].try_into().unwrap()), 1);
        assert_eq!(u32::from_le_bytes(tags[12..16].try_into().unwrap()), 0x0A);
        assert_eq!(u32::from_le_bytes(tags[20..24].try_into().unwrap()), IMAGE_TABLE_ADDR);
        assert_eq!(u32::from_le_bytes(tags[24..28].try_into().unwrap()), 2);
    }
}
