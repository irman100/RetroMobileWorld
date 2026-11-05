use std::cell::{Cell, RefCell};
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
    pokes: Vec<(u32, u32)>,
    memory_tag0: Option<(u32, u32)>,
    ram_tag4: Option<(u32, u32)>,
    ram_tag5: Option<(u32, u32)>,
}

fn parse_u64(value: &str) -> Result<u64, String> {
    if let Some(hex) = value.strip_prefix("0x").or_else(|| value.strip_prefix("0X")) {
        u64::from_str_radix(hex, 16).map_err(|e| format!("invalid hexadecimal value {value}: {e}"))
    } else {
        value.parse::<u64>().map_err(|e| format!("invalid number {value}: {e}"))
    }
}

fn parse_ram_range(value: &str) -> Result<(u32, u32), String> {
    let (base, size) = value
        .split_once(':')
        .ok_or_else(|| format!("invalid --ram-tag5={value}; expected BASE:SIZE"))?;
    let base = parse_u64(base)?;
    let size = parse_u64(size)?;
    if base > u32::MAX as u64 || size == 0 || size > u32::MAX as u64 {
        return Err("RAM tag range must use a nonzero 32-bit base and size".to_string());
    }
    if base & 0xFFF != 0 || size & 0xFFF != 0 {
        return Err("RAM tag range base and size must be 4 KiB aligned".to_string());
    }
    if base.checked_add(size).is_none_or(|end| end > (u32::MAX as u64) + 1) {
        return Err("RAM tag range exceeds the 32-bit address space".to_string());
    }
    Ok((base as u32, size as u32))
}

fn parse_poke(value: &str) -> Result<(u32, u32), String> {
    let (address, data) = value
        .split_once(':')
        .ok_or_else(|| format!("invalid --poke={value}; expected ADDRESS:VALUE"))?;
    let address = parse_u64(address)?;
    let data = parse_u64(data)?;
    if address > u32::MAX as u64 || address & 3 != 0 {
        return Err(format!("poke address must be a 4-byte-aligned 32-bit address: {address:#x}"));
    }
    if data > u32::MAX as u64 {
        return Err(format!("poke value must fit in 32 bits: {data:#x}"));
    }
    Ok((address as u32, data as u32))
}

fn parse_options() -> Result<Options, String> {
    let mut archive = None;
    let mut instructions = DEFAULT_INSTRUCTIONS;
    let mut trace = DEFAULT_TRACE;
    let mut initial_registers = [0u32; 4];
    let mut no_handoff = false;
    let mut pokes = Vec::new();
    let mut memory_tag0 = None;
    let mut ram_tag4 = None;
    let mut ram_tag5 = None;

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
        } else if let Some(value) = arg.strip_prefix("--poke=") {
            pokes.push(parse_poke(value)?);
        } else if let Some(value) = arg.strip_prefix("--ram-tag5=") {
            ram_tag5 = Some(parse_ram_range(value)?);
        } else if let Some(value) = arg.strip_prefix("--memory-tag0=") {
            memory_tag0 = Some(parse_ram_range(value)?);
        } else if let Some(value) = arg.strip_prefix("--ram-tag4=") {
            ram_tag4 = Some(parse_ram_range(value)?);
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
        "usage: rm217-boot-probe <RM-217.zip> [--instructions=1000000] [--trace=96] [--poke=ADDRESS:VALUE] [--memory-tag0=BASE:SIZE] [--ram-tag4=BASE:SIZE] [--ram-tag5=BASE:SIZE] [--no-handoff] [--r0=0] [--r1=0] [--r2=0] [--r3=0]".to_string()
    })?;
    Ok(Options { archive, instructions, trace, initial_registers, no_handoff, pokes, memory_tag0, ram_tag4, ram_tag5 })
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

fn build_boot_tag_list(
    image_table: u32,
    memory_tag0: Option<(u32, u32)>,
    ram_tag4: Option<(u32, u32)>,
    ram_tag5: Option<(u32, u32)>,
) -> Vec<u8> {
    let mut tags = Vec::new();
    append_tag(&mut tags, 0x0D, &[1]); // power-on by key
    append_tag(&mut tags, 0x0A, &[image_table]); // image table pointer
    if let Some((base, size)) = memory_tag0 {
        // Experimental type 0: candidate 32 MiB NOR window for 256-Mbit flash.
        // The physical base/alias still needs confirmation from RM-217 documentation.
        append_tag(&mut tags, 0, &[size, base]);
    }
    if let Some((base, size)) = ram_tag4 {
        // BSE pre-init requires one tag 4; payload is [size, base].
        // The range is an experimental candidate, not a confirmed device map.
        append_tag(&mut tags, 4, &[size, base]);
    }
    if let Some((base, size)) = ram_tag5 {
        // BSE uses tag 5 for the separate stack-containing range.
        append_tag(&mut tags, 5, &[size, base]);
    }
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
    memory_tag0: Option<(u32, u32)>,
    ram_tag4: Option<(u32, u32)>,
    ram_tag5: Option<(u32, u32)>,
) -> Result<(), Box<dyn Error>> {
    println!("Installing provisional RM-217 boot hand-off (C3-00 tag ABI reference):");
    let image_count = write_image_table(uc, firmware, main_mcusw, xip_base)?;
    if let Some((base, size)) = memory_tag0 {
        println!(
            "  [experimental tag 0] candidate NOR window base=0x{base:08X} size=0x{size:08X}; inferred from 256-Mbit capacity, not yet hardware-validated"
        );
    }
    if let Some((base, size)) = ram_tag4 {
        println!(
            "  [experimental tag 4] primary RAM range base=0x{base:08X} size=0x{size:08X}; candidate only, not yet validated against RM-217 hardware documentation"
        );
    }
    if let Some((base, size)) = ram_tag5 {
        println!(
            "  [experimental tag 5] stack RAM range base=0x{base:08X} size=0x{size:08X}; not yet validated against RM-217 hardware documentation"
        );
    }
    let tags = build_boot_tag_list(IMAGE_TABLE_ADDR, memory_tag0, ram_tag4, ram_tag5);
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

    // Optional diagnostic initial values for RAM/MMIO-like locations. These are
    // explicitly experimental overrides; they are not asserted to be real device state.
    for (address, value) in &options.pokes {
        let page = (*address as u64) & !0xFFF;
        uc.mem_map(page, 0x1000, Prot::ALL).map_err(|error| {
            std::io::Error::other(format!("poke page map failed at 0x{page:08X}: {error:?}"))
        })?;
        uc.mem_write(*address as u64, &value.to_le_bytes()).map_err(|error| {
            std::io::Error::other(format!("poke write failed at 0x{address:08X}: {error:?}"))
        })?;
        println!(
            "  [experimental poke] 0x{address:08X} <- 0x{value:08X} (not a validated hardware model)"
        );
    }

    if !options.no_handoff {
        install_rm217_handoff(&mut uc, &firmware, main, xip_base, options.memory_tag0, options.ram_tag4, options.ram_tag5)?;
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

    fn dump_memory_window(cpu: &Uc<'_>, label: &str, base: u32, length: u32) {
        println!("  [{label}] 0x{base:08X}..0x{:08X}", base.saturating_add(length));
        for offset in (0..length).step_by(16) {
            let address = base.saturating_add(offset);
            let mut bytes = [0u8; 16];
            let available = (length - offset).min(16) as usize;
            if cpu.mem_read(address as u64, &mut bytes[..available]).is_err() {
                println!("    0x{address:08X}: <unreadable>");
                continue;
            }
            let mut words = Vec::new();
            for chunk in bytes[..available].chunks(4) {
                let mut word = [0u8; 4];
                word[..chunk.len()].copy_from_slice(chunk);
                words.push(format!("{:08X}", u32::from_le_bytes(word)));
            }
            println!("    0x{address:08X}: {}", words.join(" "));
        }
    }

    fn dump_cpu_context(cpu: &mut Uc<'_>, label: &str) {
        let read_reg = |cpu: &Uc<'_>, reg| cpu.reg_read(reg).unwrap_or(0) as u32;
        let pc = read_reg(cpu, RegisterARM::PC);
        let cpsr = read_reg(cpu, RegisterARM::CPSR);
        let sp = read_reg(cpu, RegisterARM::SP);
        println!(
            "  [{label}] PC=0x{pc:08X} CPSR=0x{cpsr:08X} SP=0x{sp:08X} LR=0x{:08X}",
            read_reg(cpu, RegisterARM::LR)
        );
        println!(
            "    r0=0x{:08X} r1=0x{:08X} r2=0x{:08X} r3=0x{:08X} r4=0x{:08X} r5=0x{:08X} r6=0x{:08X} r7=0x{:08X}",
            read_reg(cpu, RegisterARM::R0),
            read_reg(cpu, RegisterARM::R1),
            read_reg(cpu, RegisterARM::R2),
            read_reg(cpu, RegisterARM::R3),
            read_reg(cpu, RegisterARM::R4),
            read_reg(cpu, RegisterARM::R5),
            read_reg(cpu, RegisterARM::R6),
            read_reg(cpu, RegisterARM::R7)
        );
        // The BSE frame reserves 0x107C bytes below the incoming stack and builds
        // temporary memory-map records at SP+0x40 and a descriptor list at SP+0x840.
        // Capture those objects plus the original register-save area and boot tags.
        dump_memory_window(cpu, "BSE-memory-map-scratch", 0x0800_2FA0, 0x180);
        dump_memory_window(cpu, "BSE-descriptor-list", 0x0800_37A0, 0x100);
        dump_memory_window(cpu, "BSE-entry-register-save", 0x0800_3FD8, 0x28);
        dump_memory_window(cpu, "boot-tags", BOOT_TAGS_ADDR, 0x40);

        let begin = sp.saturating_sub(0x20);
        for offset in (0..0x70u32).step_by(4) {
            let address = begin.saturating_add(offset);
            let mut word = [0u8; 4];
            if cpu.mem_read(address as u64, &mut word).is_ok() {
                println!(
                    "    stack[0x{address:08X}] = 0x{:08X}",
                    u32::from_le_bytes(word)
                );
            }
        }
    }

    let log_port_bytes = Rc::new(RefCell::new(Vec::<u8>::new()));
    uc.add_mem_hook(
        HookType::MEM_WRITE,
        0x201E_F000,
        0x201E_F003,
        {
            let log_port_bytes = Rc::clone(&log_port_bytes);
            move |cpu, _kind, address, size, value| {
                let mut bytes = log_port_bytes.borrow_mut();
                let raw = value as u64;
                for index in 0..size.min(8) {
                    bytes.push((raw >> (index * 8)) as u8);
                }
                if bytes.len() > 512 {
                    let excess = bytes.len() - 512;
                    bytes.drain(0..excess);
                }
                let marker = b"Failed assertion @ 0x%08X";
                let marker_seen = bytes.windows(marker.len()).any(|window| window == marker);
                drop(bytes);
                if marker_seen {
                    dump_cpu_context(cpu, "assertion-format-output");
                    // Report once per process; the bounded text buffer is retained for
                    // reset-path diagnostics without adding per-instruction hooks.
                    log_port_bytes.borrow_mut().clear();
                }
                let pc = cpu.reg_read(RegisterARM::PC).unwrap_or(0);
                println!(
                    "  [log-port] addr=0x{address:08X} size={size} value=0x{value:X} pc=0x{pc:08X}"
                );
                true
            }
        },
    )
    .map_err(|error| std::io::Error::other(format!("log-port watch failed: {error:?}")))?;

    let reset_request = Rc::new(Cell::new(None::<(u32, u32, u32)>));
    for (begin, end) in [
        (0x201F_D800u64, 0x201F_D803u64),
        (0x2003_2D14u64, 0x2003_2D17),
        (0xFFD3_2D14u64, 0xFFD3_2D17),
    ] {
        let reset_request = Rc::clone(&reset_request);
        uc.add_mem_hook(
            HookType::MEM_WRITE,
            begin,
            end,
            move |cpu, _kind, address, size, value| {
                let pc = cpu.reg_read(RegisterARM::PC).unwrap_or(0);
                println!(
                    "  [watched-write] addr=0x{address:08X} size={size} value=0x{value:X} pc=0x{pc:08X}"
                );
                if address == 0x2003_2D14 || address == 0xFFD3_2D14 {
                    reset_request.set(Some((address as u32, value as u32, pc as u32)));
                    dump_cpu_context(cpu, "hardware-reset-register-write");
                    // This is a real guest reset request. The diagnostic CPU-only probe
                    // has no reset controller/boot ROM, so stop at the boundary rather
                    // than pretending the following spin loop is useful execution.
                    let _ = cpu.emu_stop();
                }
                true
            },
        )
        .map_err(|error| std::io::Error::other(format!("write watch failed: {error:?}")))?;
    }

    let status_read_count = Rc::new(Cell::new(0usize));
    for (begin, end) in [
        (0x2005_0014u64, 0x2005_0017u64),
        (0xFFD5_0014u64, 0xFFD5_0017u64),
    ] {
        let status_read_count = Rc::clone(&status_read_count);
        uc.add_mem_hook(
            HookType::MEM_READ,
            begin,
            end,
            move |cpu, _kind, address, size, value| {
                let count = status_read_count.get();
                if count < 24 {
                    status_read_count.set(count + 1);
                    let pc = cpu.reg_read(RegisterARM::PC).unwrap_or(0);
                    println!(
                        "  [status-read {count:02}] addr=0x{address:08X} size={size} value=0x{value:X} pc=0x{pc:08X}"
                    );
                }
                true
            },
        )
        .map_err(|error| std::io::Error::other(format!("status read watch failed: {error:?}")))?;
    }

    println!(
        "Initial registers: r0=0x{:08X} r1=0x{:08X} r2=0x{:08X} r3=0x{:08X}",
        uc.reg_read(RegisterARM::R0).unwrap_or(0) as u32,
        uc.reg_read(RegisterARM::R1).unwrap_or(0) as u32,
        uc.reg_read(RegisterARM::R2).unwrap_or(0) as u32,
        uc.reg_read(RegisterARM::R3).unwrap_or(0) as u32
    );

    // Do not install instruction code-hooks: on this interpreter build the
    // per-instruction hook callback can disturb the block-flow fast path.
    // Instead, execute bounded batches and sample architectural state between them.
    let sample_count = if options.trace == 0 {
        1usize
    } else {
        (options.trace as usize).min(options.instructions.max(1) as usize)
    };
    let sample_count = sample_count.max(1);
    let mut begin = entry as u64;
    let mut executed_total = 0u64;
    let mut stop = "instruction budget completed".to_string();

    for sample in 0..sample_count {
        let remaining = options.instructions.saturating_sub(executed_total);
        if remaining == 0 {
            break;
        }
        let samples_left = (sample_count - sample) as u64;
        let batch = ((remaining + samples_left - 1) / samples_left).max(1);
        let before = uc.icount;
        let result = uc.emu_start(begin, 0xFFFF_FFF0, 0, batch as usize);
        let after = uc.icount;
        let retired = after.saturating_sub(before);
        executed_total = executed_total.saturating_add(retired);

        let read = |register| uc.reg_read(register).unwrap_or(0) as u32;
        let pc = read(RegisterARM::PC);
        let cpsr = read(RegisterARM::CPSR);
        println!(
            "  [sample {sample:03}] +{retired} instructions (total {executed_total}) \
             pc=0x{pc:08X} {} cpsr=0x{cpsr:08X} \
             r0=0x{:08X} r1=0x{:08X} r2=0x{:08X} r3=0x{:08X} \
             sp=0x{:08X} lr=0x{:08X}",
            if cpsr & 0x20 != 0 { "T" } else { "A" },
            read(RegisterARM::R0),
            read(RegisterARM::R1),
            read(RegisterARM::R2),
            read(RegisterARM::R3),
            read(RegisterARM::SP),
            read(RegisterARM::LR)
        );

        if let Err(error) = result {
            stop = format!("CPU error: {error:?}");
            break;
        }
        if let Some((address, value, pc)) = reset_request.get() {
            stop = format!(
                "guest requested hardware reset: [0x{address:08X}] <- 0x{value:08X} at PC 0x{pc:08X}"
            );
            break;
        }
        if retired == 0 {
            stop = "CPU returned without retiring an instruction".to_string();
            break;
        }
        if pc >= 0xFFFF_FFF0 {
            stop = "guest reached the requested stop address".to_string();
            break;
        }
        if uc.wfi {
            stop = "CPU entered WFI".to_string();
            break;
        }

        begin = pc as u64 | u64::from(cpsr & 0x20 != 0);
    }

    let read = |register| uc.reg_read(register).unwrap_or(0) as u32;
    println!();
    println!("=== PROBE STOP ===");
    println!("{stop}");
    println!(
        "Instructions retired: {executed_total}; PC=0x{:08X}; CPSR=0x{:08X}; SP=0x{:08X}; LR=0x{:08X}",
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
    if let Some((address, value, pc)) = reset_request.get() {
        println!(
            "Hardware reset boundary captured: register=0x{address:08X}, value=0x{value:08X}, guest PC=0x{pc:08X}. Reset-controller behavior is not yet emulated."
        );
    }
    dump_cpu_context(&mut uc, "final-cpu-context");
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
            println!("Usage: rm217-boot-probe <RM-217.zip> [--instructions=1000000] [--trace=96] [--poke=ADDRESS:VALUE] [--memory-tag0=BASE:SIZE] [--ram-tag4=BASE:SIZE] [--ram-tag5=BASE:SIZE] [--no-handoff] [--r0=0] [--r1=0] [--r2=0] [--r3=0]");
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
    fn parses_aligned_32_bit_poke_specification() {
        assert_eq!(parse_poke("0x20050014:0x4").unwrap(), (0x2005_0014, 4));
        assert_eq!(parse_poke("536936468:4").unwrap(), (0x2005_0014, 4));
        assert!(parse_poke("0x20050015:4").is_err());
        assert!(parse_poke("0x100000000:4").is_err());
        assert!(parse_poke("0x20050014:0x100000000").is_err());
        assert!(parse_poke("0x20050014").is_err());
    }

    #[test]
    fn parses_experimental_tag5_ram_range_safely() {
        assert_eq!(
            parse_ram_range("0x08000000:0x10000").unwrap(),
            (0x0800_0000, 0x0001_0000)
        );
        assert!(parse_ram_range("0x08000001:0x10000").is_err());
        assert!(parse_ram_range("0x08000000:0").is_err());
        assert!(parse_ram_range("0xFFFFFFF0:0x10000").is_err());
        assert!(parse_ram_range("0x08000000").is_err());
    }

    #[test]
    fn builds_optional_tag5_with_size_then_base_payload() {
        let tags = build_boot_tag_list(
            IMAGE_TABLE_ADDR,
            Some((0xF000_0000, 0x0200_0000)),
            Some((0x4000_0000, 0x0100_0000)),
            Some((0x0800_0000, 0x0001_0000)),
        );
        // Tag D: 12 bytes, A: 12 bytes, tag 0: 16, tag 4: 16, tag 5: 16, end: 8.
        assert_eq!(u32::from_le_bytes(tags[0..4].try_into().unwrap()), 0x0D);
        assert_eq!(u32::from_le_bytes(tags[12..16].try_into().unwrap()), 0x0A);
        assert_eq!(u32::from_le_bytes(tags[24..28].try_into().unwrap()), 0);
        assert_eq!(u32::from_le_bytes(tags[28..32].try_into().unwrap()), 16);
        assert_eq!(u32::from_le_bytes(tags[32..36].try_into().unwrap()), 0x0200_0000);
        assert_eq!(u32::from_le_bytes(tags[36..40].try_into().unwrap()), 0xF000_0000);
        assert_eq!(u32::from_le_bytes(tags[40..44].try_into().unwrap()), 4);
        assert_eq!(u32::from_le_bytes(tags[48..52].try_into().unwrap()), 0x0100_0000);
        assert_eq!(u32::from_le_bytes(tags[52..56].try_into().unwrap()), 0x4000_0000);
        assert_eq!(u32::from_le_bytes(tags[56..60].try_into().unwrap()), 5);
        assert_eq!(u32::from_le_bytes(tags[64..68].try_into().unwrap()), 0x0001_0000);
        assert_eq!(u32::from_le_bytes(tags[68..72].try_into().unwrap()), 0x0800_0000);
        assert_eq!(u32::from_le_bytes(tags[72..76].try_into().unwrap()), 2);
    }

    #[test]
    fn builds_boot_info_with_power_on_and_image_table_tags() {
        let tags = build_boot_tag_list(IMAGE_TABLE_ADDR, None, None, None);
        assert_eq!(u32::from_le_bytes(tags[0..4].try_into().unwrap()), 0x0D);
        assert_eq!(u32::from_le_bytes(tags[8..12].try_into().unwrap()), 1);
        assert_eq!(u32::from_le_bytes(tags[12..16].try_into().unwrap()), 0x0A);
        assert_eq!(u32::from_le_bytes(tags[20..24].try_into().unwrap()), IMAGE_TABLE_ADDR);
        assert_eq!(u32::from_le_bytes(tags[24..28].try_into().unwrap()), 2);
    }
}
