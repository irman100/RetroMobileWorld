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
/// NOLO's own image lookup reads this base from its global at 0x080006A0.
const NOLO_TOC_ADDR: u32 = 0x0000_0400;
const PAPUBKEYS_RAM_ADDR: u32 = 0x0801_0000;
const NOLO_ROM_SEC_SERVICE: u32 = 0xFFFF_0808;
const SEC_SERVICE_ID: [u8; 12] =
    [0xAB, 0x0F, 0xBD, 0x96, 0xAF, 0x2A, 0xC2, 0x71, 0xD2, 0x62, 0x64, 0xAF];
const SEC_SERVICE6_THUMB_STUB: [u8; 4] = [0x00, 0x20, 0x70, 0x47]; // movs r0,#0; bx lr
const BB5_MAGIC: u32 = 0x8097_95A3;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum StartImage {
    Mcusw,
    Nolo,
}

#[derive(Debug)]
struct Options {
    archive: PathBuf,
    start_image: StartImage,
    instructions: u64,
    trace: u64,
    initial_registers: [u32; 4],
    ready_on_read: Vec<(u32, u8)>,
    nor_id: Option<(u16, u16)>,
    watch_pc: Vec<u32>,
    trace_bse_memory: bool,
    hle_secure_service6: bool,
    hle_secure_service6_launch: bool,
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

fn decode_secure_service(descriptor: &[u8]) -> Option<u16> {
    if descriptor.len() < 16 || descriptor.get(..12)? != SEC_SERVICE_ID {
        return None;
    }
    Some(u16::from_le_bytes([descriptor[14], descriptor[15]]))
}

/// Resolve the BB5 reset entry only when the supplied image prefix is internally
/// self-consistent: magic, entry offset, XIP base and entry literal must agree.
fn bb5_entry_from_prefix(prefix: &[u8], image_base: u32) -> Option<u32> {
    let word = |offset: usize| -> Option<u32> {
        let bytes = prefix.get(offset..offset.checked_add(4)?)?;
        Some(u32::from_le_bytes(bytes.try_into().ok()?))
    };
    if word(0)? != BB5_MAGIC {
        return None;
    }
    let entry_offset = word(0x1C)? as usize;
    let literal_end = entry_offset.checked_add(0x7C)?;
    if literal_end > prefix.len() {
        return None;
    }
    let entry = image_base.checked_add(entry_offset as u32)?;
    let entry_literal = word(entry_offset.checked_add(0x74)?)?;
    let xip_base = word(entry_offset.checked_add(0x78)?)?;
    if xip_base != image_base || entry_literal != entry {
        return None;
    }
    Some(entry)
}

/// Thumb trampoline for the candidate non-returning NOLO image-start service:
/// restore the four saved input registers from the caller's stack, then BX to
/// the validated ARM BB5 entry (the even target clears CPSR.T).
fn secure_service6_launch_trampoline(target: u32) -> Vec<u8> {
    let mut code = vec![
        0x00, 0x98, // ldr r0, [sp, #0]
        0x01, 0x99, // ldr r1, [sp, #4]
        0x02, 0x9A, // ldr r2, [sp, #8]
        0x03, 0x9B, // ldr r3, [sp, #12]
        0x01, 0x4C, // ldr r4, [pc, #4] -> literal at entry+16
        0x20, 0x47, // bx r4
        0xC0, 0x46, // nop (alignment / literal separation)
        0xC0, 0x46, // nop
        0, 0, 0, 0, // ARM entry literal
    ];
    code[16..20].copy_from_slice(&target.to_le_bytes());
    code
}

fn parse_watch_pc(value: &str) -> Result<u32, String> {
    let address = parse_u64(value)?;
    if address > u32::MAX as u64 || address & 1 != 0 {
        return Err("watch PC must be a halfword-aligned 32-bit address".to_string());
    }
    Ok(address as u32)
}

fn parse_ready_bit(value: &str) -> Result<(u32, u8), String> {
    let (address, bit) = value
        .split_once(':')
        .ok_or_else(|| format!("invalid --ready-bit={value}; expected ADDRESS:BIT"))?;
    let address = parse_u64(address)?;
    let bit = parse_u64(bit)?;
    if address > u32::MAX as u64 || address & 3 != 0 {
        return Err("ready-bit address must be a 4-byte-aligned 32-bit address".to_string());
    }
    if bit > 31 {
        return Err("ready-bit position must be from 0 through 31".to_string());
    }
    Ok((address as u32, bit as u8))
}

fn parse_nor_id(value: &str) -> Result<(u16, u16), String> {
    let (manufacturer, device) = value
        .split_once(':')
        .ok_or_else(|| format!("invalid --nor-id={value}; expected MANUFACTURER:DEVICE"))?;
    let manufacturer = parse_u64(manufacturer)?;
    let device = parse_u64(device)?;
    if manufacturer > u16::MAX as u64 || device > u16::MAX as u64 {
        return Err("NOR manufacturer and device IDs must each fit in 16 bits".to_string());
    }
    Ok((manufacturer as u16, device as u16))
}

fn parse_start_image(value: &str) -> Result<StartImage, String> {
    match value {
        "mcusw" => Ok(StartImage::Mcusw),
        "nolo" => Ok(StartImage::Nolo),
        _ => Err(format!("unknown entry image {value}; expected mcusw or nolo")),
    }
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
    let mut start_image = StartImage::Mcusw;
    let mut instructions = DEFAULT_INSTRUCTIONS;
    let mut trace = DEFAULT_TRACE;
    let mut initial_registers = [0u32; 4];
    let mut ready_on_read = Vec::new();
    let mut nor_id = None;
    let mut watch_pc = Vec::new();
    let mut trace_bse_memory = false;
    let mut hle_secure_service6 = false;
    let mut hle_secure_service6_launch = false;
    let mut no_handoff = false;
    let mut pokes = Vec::new();
    let mut memory_tag0 = None;
    let mut ram_tag4 = None;
    let mut ram_tag5 = None;

    for arg in std::env::args().skip(1) {
        if let Some(value) = arg.strip_prefix("--entry=") {
            start_image = parse_start_image(&value)?;
        } else if let Some(value) = arg.strip_prefix("--instructions=") {
            instructions = parse_u64(value)?;
        } else if let Some(value) = arg.strip_prefix("--trace=") {
            trace = parse_u64(value)?;
        } else if let Some(value) = arg.strip_prefix("--ready-bit=") {
            ready_on_read.push(parse_ready_bit(value)?);
        } else if let Some(value) = arg.strip_prefix("--nor-id=") {
            nor_id = Some(parse_nor_id(value)?);
        } else if let Some(value) = arg.strip_prefix("--watch-pc=") {
            watch_pc.push(parse_watch_pc(value)?);
        } else if arg == "--trace-bse-memory" {
            trace_bse_memory = true;
        } else if arg == "--hle-secure-service6" {
            hle_secure_service6 = true;
        } else if arg == "--hle-secure-service6-launch" {
            hle_secure_service6_launch = true;
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
        "usage: rm217-boot-probe <RM-217.zip> [--entry=mcusw|nolo] [--instructions=1000000] [--trace=96] [--ready-bit=ADDRESS:BIT ...] [--nor-id=MANUFACTURER:DEVICE] [--watch-pc=ADDRESS ...] [--trace-bse-memory] [--hle-secure-service6] [--hle-secure-service6-launch] [--poke=ADDRESS:VALUE] [--memory-tag0=BASE:SIZE] [--ram-tag4=BASE:SIZE] [--ram-tag5=BASE:SIZE] [--no-handoff] [--r0=0] [--r1=0] [--r2=0] [--r3=0]".to_string()
    })?;
    Ok(Options { archive, start_image, instructions, trace, initial_registers, ready_on_read, nor_id, watch_pc, trace_bse_memory, hle_secure_service6, hle_secure_service6_launch, no_handoff, pokes, memory_tag0, ram_tag4, ram_tag5 })
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


fn select_nolo(regions: &[Region]) -> Result<&Region, Box<dyn Error>> {
    regions
        .iter()
        .filter(|region| region.name.as_deref() == Some("NOLO"))
        .max_by_key(|region| region.data.len())
        .ok_or_else(|| "MCU FPSX has no named NOLO region".into())
}

fn map_region_once(
    uc: &mut Uc<'_>,
    mapped: &mut Vec<(u64, u64)>,
    base: u32,
    size: u32,
    label: &str,
) -> Result<(), Box<dyn Error>> {
    if size == 0 {
        return Err(format!("{label} memory range is empty").into());
    }
    let start = base as u64;
    let end = start.checked_add(size as u64).ok_or("memory range overflow")?;
    if end > (u32::MAX as u64) + 1 || start & 0xFFF != 0 || size as u64 & 0xFFF != 0 {
        return Err(format!("{label} memory range is not a valid page-aligned 32-bit interval").into());
    }
    if mapped.iter().any(|(b, e)| start >= *b && end <= *e) {
        println!("  {label} range 0x{base:08X}+0x{size:08X} already covered by an existing mapping");
        return Ok(());
    }
    if mapped.iter().any(|(b, e)| start < *e && *b < end) {
        return Err(format!("{label} range overlaps an existing mapping").into());
    }
    uc.mem_map(start, size as u64, Prot::ALL).map_err(|error| {
        std::io::Error::other(format!("could not map {label} at 0x{base:08X}+0x{size:08X}: {error:?}"))
    })?;
    mapped.push((start, end));
    println!("  mapped {label}: 0x{base:08X}..0x{:08X}", end as u32);
    Ok(())
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

fn image_table_relative_offset(load_address: u32, table_base: u32) -> u32 {
    load_address.wrapping_sub(table_base)
}

fn write_image_table(
    uc: &mut Uc<'_>,
    firmware: &Fpsx,
    main_mcusw: &Region,
    xip_base: u32,
    table_base: u32,
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
        let relative = image_table_relative_offset(destination, table_base);
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
    uc.mem_write(table_base as u64, &entries)
        .map_err(|error| std::io::Error::other(format!("image table write failed at 0x{table_base:08X}: {error:?}")))?;
    println!(
        "  image table written at 0x{table_base:08X}; all entry offsets are relative to this base"
    );
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
    let image_count = write_image_table(uc, firmware, main_mcusw, xip_base, IMAGE_TABLE_ADDR)?;
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
    let nolo = select_nolo(&firmware.regions)?;
    println!(
        "Image table source MCUSW: flash=0x{:08X}, bytes={}, blocks={}",
        main.addr,
        main.data.len(),
        main.blocks
    );
    println!(
        "NOLO image: flash=0x{:08X}, bytes={}, blocks={}",
        nolo.addr,
        nolo.data.len(),
        nolo.blocks
    );

    if main.data.len() < 0x14C || read_u32_le(&main.data, 0)? != 0x8097_95A3 {
        return Err("selected MCUSW does not contain a BB5 OS header".into());
    }
    if nolo.data.len() < 0x20 || read_u32_le(&nolo.data, 0)? != 0x8097_95A3 {
        return Err("selected NOLO image does not contain a BB5 OS header".into());
    }

    let entry_region = match options.start_image {
        StartImage::Mcusw => main,
        StartImage::Nolo => nolo,
    };
    let entry_offset = read_u32_le(&entry_region.data, 0x1C)? as usize;
    if entry_offset >= entry_region.data.len() {
        return Err("selected entry offset is outside the chosen image".into());
    }
    let entry_word = read_u32_le(&entry_region.data, entry_offset)?;
    let xip_base = read_u32_le(&main.data, 0x3D0 + 0x78)?;
    let entry = match options.start_image {
        StartImage::Mcusw => {
            let base = read_u32_le(&main.data, 0x3D0 + 0x78)?;
            base.wrapping_add(entry_offset as u32)
        }
        StartImage::Nolo => entry_region.addr.wrapping_add(entry_offset as u32),
    };
    let (thumb_entry, initial_sp, stack_cookie) = match options.start_image {
        StartImage::Mcusw => {
            let thumb = xip_base.wrapping_add(entry_offset as u32 + 0x40);
            let sp = read_u32_le(&main.data, entry_offset + 0x70)?;
            let cookie = read_u32_le(&main.data, entry_offset + 0x68)?;
            (thumb, sp, cookie)
        }
        StartImage::Nolo => (entry.wrapping_add(0x28), 0x0800_0DA0, 0),
    };

    println!("Selected start image: {:?}", options.start_image);
    println!("BB5 entry offset: 0x{entry_offset:X}");
    println!("First ARM word: 0x{entry_word:08X}");
    println!("Start entry: 0x{entry:08X}");
    println!("Candidate first Thumb entry: 0x{thumb_entry:08X}");
    if options.start_image == StartImage::Mcusw {
        let stack_reserve = read_u32_le(&main.data, entry_offset + 0x6C)?;
        let scratch = initial_sp.wrapping_sub(stack_reserve);
        println!("Early stack: 0x{initial_sp:08X}");
        println!("Early scratch: 0x{scratch:08X}, marker=0x{stack_cookie:08X}");
    } else {
        println!("NOLO entry saves r0-r3 at 0x08003FF0 and sets SP=0x08000DA0 before early init.");
    }

    // Use the c3emu ARM926 CPU core without its RM-614 Machine wrapper. The latter
    // installs a model-specific peripheral map and a tight-loop heuristic that can
    // mistake legitimate cache-maintenance polling for a fatal guest loop.
    let mut uc = new_cpu().map_err(|error| std::io::Error::other(format!("CPU creation failed: {error:?}")))?;
    let mut mapped = Vec::new();
    map_region_once(&mut uc, &mut mapped, 0x0000_0000, 0x1000_0000, "low memory")?;

    if let Some((base, size)) = options.ram_tag4 {
        map_region_once(&mut uc, &mut mapped, base, size, "candidate SDRAM/tag4")?;
    }
    if let Some((base, size)) = options.ram_tag5 {
        map_region_once(&mut uc, &mut mapped, base, size, "candidate stack SRAM/tag5")?;
    }
    if let Some((base, size)) = options.memory_tag0 {
        map_region_once(&mut uc, &mut mapped, base, size, "candidate NOR/tag0")?;
    } else {
        map_region_once(&mut uc, &mut mapped, 0x8000_0000, 0x0400_0000, "reference high RAM")?;
    }

    let xip_size = ((main.data.len() as u64 + 0xFFF) & !0xFFF).max(XIP_MINIMUM_MAP);
    if !mapped.iter().any(|(base, end)| {
        (xip_base as u64) >= *base && (xip_base as u64).saturating_add(xip_size) <= *end
    }) {
        map_region_once(&mut uc, &mut mapped, xip_base, xip_size as u32, "MCUSW XIP")?;
    }
    uc.mem_write(xip_base as u64, &main.data)
        .map_err(|error| std::io::Error::other(format!("MCUSW load failed: {error:?}")))?;

    // MCUSW's provisional hand-off points to IMAGE_TABLE_ADDR. NOLO does not consume
    // that tag list: its own lookup routine reads the TOC base from 0x080006A0, whose
    // startup value is 0x00000400. Build the same record format at the address the
    // actual NOLO lookup code scans, with offsets relative to that exact base.
    let image_table_base = match options.start_image {
        StartImage::Mcusw => IMAGE_TABLE_ADDR,
        StartImage::Nolo => NOLO_TOC_ADDR,
    };
    write_image_table(&mut uc, &firmware, main, xip_base, image_table_base)?;
    println!(
        "Selected image table base for {:?}: 0x{image_table_base:08X}",
        options.start_image
    );

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

    if options.start_image == StartImage::Mcusw && !options.no_handoff {
        install_rm217_handoff(&mut uc, &firmware, main, xip_base, options.memory_tag0, options.ram_tag4, options.ram_tag5)?;
    } else if options.start_image == StartImage::Nolo {
        println!("NOLO entry selected: no MCUSW boot-tag list is injected; r0-r3 remain explicit boot-ROM inputs.");
    } else {
        println!("MCUSW boot hand-off disabled; r0-r3 start from supplied values.");
    }

    uc.reg_write(RegisterARM::CPSR, 0xD3u64)
        .map_err(|error| std::io::Error::other(format!("CPSR setup failed: {error:?}")))?;
    let mut initial = options.initial_registers;
    if options.start_image == StartImage::Mcusw && !options.no_handoff && initial[2] == 0 {
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
    let secure_service6_calls = Rc::new(Cell::new(0usize));
    let unhandled_rom_fetch = Rc::new(Cell::new(None::<(u32, u32, u32)>));
    uc.add_mem_hook(HookType::MEM_UNMAPPED, 1, 0, {
        let unknown_count = Rc::clone(&unknown_count);
        let secure_service6_calls = Rc::clone(&secure_service6_calls);
        let unhandled_rom_fetch = Rc::clone(&unhandled_rom_fetch);
        let allow_secure_service6 = options.hle_secure_service6;
        let allow_secure_service6_launch = options.hle_secure_service6_launch;
        move |cpu, kind, address, size, value| {
            let pc = cpu.reg_read(RegisterARM::PC).unwrap_or(0) as u32;
            let operation = match kind {
                MemType::READ => "READ",
                MemType::WRITE => "WRITE",
                MemType::FETCH => "FETCH",
                MemType::READ_UNMAPPED => "READ_UNMAPPED",
                MemType::WRITE_UNMAPPED => "WRITE_UNMAPPED",
                MemType::FETCH_UNMAPPED => "FETCH_UNMAPPED",
            };

            if kind == MemType::FETCH_UNMAPPED && address == NOLO_ROM_SEC_SERVICE as u64 {
                let descriptor_ptr = cpu.reg_read(RegisterARM::R0).unwrap_or(0) as u32;
                let r1 = cpu.reg_read(RegisterARM::R1).unwrap_or(0) as u32;
                let r2 = cpu.reg_read(RegisterARM::R2).unwrap_or(0) as u32;
                let r3 = cpu.reg_read(RegisterARM::R3).unwrap_or(0) as u32;
                let mut descriptor = [0u8; 16];
                let descriptor_read = cpu.mem_read(descriptor_ptr as u64, &mut descriptor).is_ok();
                let service = if descriptor_read {
                    decode_secure_service(&descriptor)
                } else {
                    None
                };

                if (allow_secure_service6 || allow_secure_service6_launch)
                    && service == Some(0x0006)
                {
                    let page = address & !0xFFF;
                    if cpu.mem_map(page, 0x1000, Prot::ALL).is_err() {
                        println!("  [NOLO ROM HLE] failed to map ROM page 0x{page:08X}");
                        unhandled_rom_fetch.set(Some((address as u32, pc, service.unwrap() as u32)));
                        let _ = cpu.emu_stop();
                        return false;
                    }
                    let (stub, description) = if allow_secure_service6_launch {
                        let mut prefix = vec![0u8; 0x500];
                        if cpu.mem_read(r2 as u64, &mut prefix).is_err() {
                            println!("  [NOLO ROM HLE] cannot read BB5 image prefix at 0x{r2:08X}");
                            unhandled_rom_fetch.set(Some((address as u32, pc, 6)));
                            let _ = cpu.emu_stop();
                            return false;
                        }
                        let Some(target) = bb5_entry_from_prefix(&prefix, r2) else {
                            println!("  [NOLO ROM HLE] BB5 image at 0x{r2:08X} has no validated entry literals");
                            unhandled_rom_fetch.set(Some((address as u32, pc, 6)));
                            let _ = cpu.emu_stop();
                            return false;
                        };
                        let stack = cpu.reg_read(RegisterARM::SP).unwrap_or(0);
                        let mut saved = [0u8; 16];
                        if cpu.mem_read(stack, &mut saved).is_err() {
                            println!("  [NOLO ROM HLE] cannot read four saved startup registers from SP=0x{stack:08X}");
                            unhandled_rom_fetch.set(Some((address as u32, pc, 6)));
                            let _ = cpu.emu_stop();
                            return false;
                        }
                        let regs = [
                            u32::from_le_bytes(saved[0..4].try_into().unwrap()),
                            u32::from_le_bytes(saved[4..8].try_into().unwrap()),
                            u32::from_le_bytes(saved[8..12].try_into().unwrap()),
                            u32::from_le_bytes(saved[12..16].try_into().unwrap()),
                        ];
                        println!(
                            "  [NOLO launch candidate] service=0x06; stack=0x{stack:08X}; saved r0-r3={:08X?}; image=0x{r2:08X}; validated BB5 ARM entry=0x{target:08X}",
                            regs
                        );
                        (
                            secure_service6_launch_trampoline(target),
                            format!("candidate non-returning image launch to ARM BB5 entry 0x{target:08X}; restores r0-r3 from service stack"),
                        )
                    } else {
                        (
                            SEC_SERVICE6_THUMB_STUB.to_vec(),
                            "diagnostic return stub: MOVS r0,#0; BX LR".to_string(),
                        )
                    };
                    if cpu.mem_write(address, &stub).is_err() {
                        println!("  [NOLO ROM HLE] failed to install service-6 Thumb stub");
                        unhandled_rom_fetch.set(Some((address as u32, pc, 6)));
                        let _ = cpu.emu_stop();
                        return false;
                    }
                    secure_service6_calls.set(secure_service6_calls.get() + 1);
                    println!(
                        "  [NOLO ROM HLE] secure service 0x06 at 0x{address:08X}: desc=0x{descriptor_ptr:08X}, r1=0x{r1:08X}, r2=0x{r2:08X}, r3=0x{r3:08X}; {description}"
                    );
                    return true;
                }

                println!(
                    "  [NOLO ROM] unhandled fetch 0x{address:08X} from PC 0x{pc:08X}: desc=0x{descriptor_ptr:08X}, secure_service={service:?}, r1=0x{r1:08X}, r2=0x{r2:08X}, r3=0x{r3:08X}, HLE-enabled={allow_secure_service6}"
                );
                unhandled_rom_fetch.set(Some((address as u32, pc, service.unwrap_or(0xFFFF) as u32)));
                let _ = cpu.emu_stop();
                return false;
            }

            // Do not execute zero-filled pages as if they were ROM. A future ROM entry
            // must be identified and HLE'd explicitly; otherwise stop at the boundary.
            if kind == MemType::FETCH_UNMAPPED && address >= 0xFFFF_0000 {
                println!(
                    "  [NOLO ROM] unsupported fetch 0x{address:08X} from PC 0x{pc:08X}; stopping instead of mapping zero-filled ROM"
                );
                unhandled_rom_fetch.set(Some((address as u32, pc, 0xFFFF)));
                let _ = cpu.emu_stop();
                return false;
            }

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
        dump_memory_window(cpu, "BSE-region-records", 0x0800_2F60, 0xC0);
        dump_memory_window(cpu, "BSE-region-map-tables", 0x0800_3200, 0x200);
        dump_memory_window(cpu, "BSE-memory-targets", 0x0800_3FA0, 0x180);
        dump_memory_window(cpu, "BSE-descriptor-list", 0x0800_37A0, 0x100);
        dump_memory_window(cpu, "BSE-entry-register-save", 0x0800_3FD8, 0x28);
        dump_memory_window(cpu, "boot-tags", BOOT_TAGS_ADDR, 0x40);
        dump_memory_window(cpu, "NOLO image-table global", 0x0800_06A0, 0x10);
        dump_memory_window(cpu, "NOLO TOC at firmware-selected base", NOLO_TOC_ADDR, 0x180);
        dump_memory_window(cpu, "MCUSW boot-info image table", IMAGE_TABLE_ADDR, 0x180);

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
                let out_of_ram = b"out of RAM: can't allocate";
                let assertion = b"Failed assertion @ 0x%08X";
                let reset_call = b"hw_reset_generate(%d)";
                let out_of_ram_seen = bytes.windows(out_of_ram.len()).any(|window| window == out_of_ram);
                let assertion_seen = bytes.windows(assertion.len()).any(|window| window == assertion);
                let reset_seen = bytes.windows(reset_call.len()).any(|window| window == reset_call);
                drop(bytes);
                if out_of_ram_seen {
                    dump_cpu_context(cpu, "BSE-out-of-RAM-allocation");
                    log_port_bytes.borrow_mut().clear();
                } else if assertion_seen {
                    dump_cpu_context(cpu, "assertion-format-output");
                    log_port_bytes.borrow_mut().clear();
                } else if reset_seen {
                    dump_cpu_context(cpu, "hardware-reset-generator-call");
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

    if options.trace_bse_memory {
        // Trace the data flow that feeds BSE's early memory-region setup. These
        // ranges are diagnostic workspaces observed in the firmware, not asserted
        // to be a validated RM-217 physical memory map.
        let watches = [
            ("boot-tags-read", HookType::MEM_READ, 0x0800_6000u64, 0x0800_607Fu64),
            ("BSE-records-read", HookType::MEM_READ, 0x0800_2F60, 0x0800_301F),
            ("BSE-records-write", HookType::MEM_WRITE, 0x0800_2F60, 0x0800_301F),
            ("BSE-map-read", HookType::MEM_READ, 0x0800_32A0, 0x0800_331F),
            ("BSE-map-write", HookType::MEM_WRITE, 0x0800_32A0, 0x0800_331F),
            // Focused windows avoid losing later non-zero writes after the broad
            // map-clear loop fills the first 64 events with zeros.
            ("BSE-map-entry-read", HookType::MEM_READ, 0x0800_32B0, 0x0800_32EF),
            ("BSE-map-entry-write", HookType::MEM_WRITE, 0x0800_32B0, 0x0800_32EF),
            // The first missing tag-list lookup observed at F0104172 used this
            // pointer from SP+0x44. Observe the list pages independently.
            ("BSE-tag-list-read", HookType::MEM_READ, 0x1009_0000, 0x1009_3FFF),
            ("BSE-tag-list-write", HookType::MEM_WRITE, 0x1009_0000, 0x1009_3FFF),
            // This pointer is consumed as the root of the boot-configuration
            // descriptor tree. Trace it separately to establish where type 0
            // should originate; do not infer its contents from the pointer alone.
            ("BSE-boot-config-read", HookType::MEM_READ, 0x10FF_FF80, 0x10FF_FFFF),
            ("BSE-boot-config-write", HookType::MEM_WRITE, 0x10FF_FF80, 0x10FF_FFFF),
            // NOLO explicitly logged "NOR Flash 0 not found" before building
            // the configuration message. Observe the candidate NOR bus windows
            // without synthesizing chip IDs or claiming a validated mapping.
            ("NOR-candidate-low-read", HookType::MEM_READ, 0xF000_0000, 0xF00F_FFFF),
            ("NOR-candidate-low-write", HookType::MEM_WRITE, 0xF000_0000, 0xF00F_FFFF),
            ("NOR-candidate-high-read", HookType::MEM_READ, 0xF110_0000, 0xF1FF_FFFF),
            ("NOR-candidate-high-write", HookType::MEM_WRITE, 0xF110_0000, 0xF1FF_FFFF),
            // The firmware's failed NOR discovery also touches these candidate
            // controller/status windows immediately before reporting "not found".
            ("NOR-controller-read", HookType::MEM_READ, 0x2003_6900, 0x2003_693F),
            ("NOR-controller-write", HookType::MEM_WRITE, 0x2003_6900, 0x2003_693F),
            ("NOR-interface-read", HookType::MEM_READ, 0x1000_4000, 0x1000_403F),
            ("NOR-interface-write", HookType::MEM_WRITE, 0x1000_4000, 0x1000_403F),
            // NOLO's flash-ID routine calls the flash at the base passed in r4.
            // For the observed NOR0 call that base is zero, so capture command
            // writes and ID reads at the physical low-memory window directly.
            ("NOR-command-read", HookType::MEM_READ, 0x0000_0000, 0x0000_000F),
            ("NOR-command-write", HookType::MEM_WRITE, 0x0000_0000, 0x0000_000F),
            // The NOR-identification routine itself resides in a low-memory
            // helper area; watch for NOLO copying/installing it before the call.
            ("NOR-helper-code-write", HookType::MEM_WRITE, 0x0800_0860, 0x0800_09FF),
            ("BSE-descriptors-read", HookType::MEM_READ, 0x0800_37A0, 0x0800_389F),
            ("BSE-descriptors-write", HookType::MEM_WRITE, 0x0800_37A0, 0x0800_389F),
            ("BSE-targets-read", HookType::MEM_READ, 0x0800_3FA0, 0x0800_411F),
            ("BSE-targets-write", HookType::MEM_WRITE, 0x0800_3FA0, 0x0800_411F),
        ];
        for (label, hook_type, begin, end) in watches {
            let count = Rc::new(Cell::new(0usize));
            // The focused descriptor/map windows must outlive their initial clear
            // pass; keep enough events to catch the subsequent record construction.
            let event_limit = if label.contains("map-entry") || label.contains("descriptors") || label.contains("tag-list") || label.contains("boot-config") || label.contains("NOR-candidate") || label.contains("NOR-controller") || label.contains("NOR-interface") || label.contains("NOR-command") || label.contains("NOR-helper-code") {
                256usize
            } else {
                64usize
            };
            uc.add_mem_hook(hook_type, begin, end, {
                let count = Rc::clone(&count);
                move |cpu, _kind, address, size, value| {
                    let index = count.get();
                    count.set(index + 1);
                    if index < event_limit {
                        let pc = cpu.reg_read(RegisterARM::PC).unwrap_or(0) as u32;
                        println!(
                            "  [BSE-memory-trace {label} {index:03}] addr=0x{address:08X} size={size} value=0x{value:X} pc=0x{pc:08X}"
                        );
                    } else if index == event_limit {
                        println!("  [BSE-memory-trace {label}] further events suppressed");
                    }
                    true
                }
            })
            .map_err(|error| std::io::Error::other(format!("BSE memory trace hook failed for {label}: {error:?}")))?;
        }
    }

    if let Some((manufacturer, device)) = options.nor_id {
        // A narrow diagnostic NOR model for the boot-ROM autoselect operation.
        // It reports only the JEDEC x16 IDs; it does not emulate array data,
        // programming, erase, timing, protection, or the complete memory controller.
        let autoselect = Rc::new(Cell::new(false));
        let command_count = Rc::new(Cell::new(0usize));
        uc.add_mem_hook(HookType::MEM_WRITE, 0, 0x01FF_FFFF, {
            let autoselect = Rc::clone(&autoselect);
            let command_count = Rc::clone(&command_count);
            move |_cpu, _kind, address, _size, value| {
                let offset = address as u32;
                let command = value as u16;
                if command == 0x90 && offset <= 0x0001_0000 {
                    autoselect.set(true);
                    let count = command_count.get();
                    command_count.set(count + 1);
                    if count < 8 {
                        println!("  [experimental NOR0] autoselect command 0x90 @0x{offset:08X}");
                    }
                } else if (command == 0xFF && offset == 0) || command == 0xF0 {
                    autoselect.set(false);
                    let count = command_count.get();
                    command_count.set(count + 1);
                    if count < 8 {
                        println!("  [experimental NOR0] read-array/reset command 0x{command:04X} @0x{offset:08X}");
                    }
                }
                true
            }
        })
        .map_err(|error| std::io::Error::other(format!("NOR write hook failed: {error:?}")))?;

        let response_count = Rc::new(Cell::new(0usize));
        uc.add_mem_hook(HookType::MEM_READ, 0, 0x01FF_FFFF, {
            let autoselect = Rc::clone(&autoselect);
            let response_count = Rc::clone(&response_count);
            move |cpu, _kind, address, size, _value| {
                if !autoselect.get() {
                    return true;
                }
                let offset = address as u32;
                let response = match (offset, size) {
                    (0, 2) => Some(manufacturer),
                    (2, 2) => Some(device),
                    // x16 autoselect sector-protection word: neutral unprotected value.
                    (6, 2) => Some(0),
                    _ => None,
                };
                let Some(word) = response else {
                    return true;
                };
                let bytes = word.to_le_bytes();
                let write = cpu.mem_write(address, &bytes);
                let count = response_count.get();
                response_count.set(count + 1);
                if count < 16 {
                    println!(
                        "  [experimental NOR0] ID read @0x{offset:08X} size={size} -> 0x{word:04X} (writeback={})",
                        if write.is_ok() { "ok" } else { "failed" }
                    );
                }
                true
            }
        })
        .map_err(|error| std::io::Error::other(format!("NOR read hook failed: {error:?}")))?;

        println!(
            "  [experimental NOR0] ID candidate 0x{manufacturer:04X}:0x{device:04X}; autoselect only, not a complete NOR model"
        );
    }

    for (address, bit) in options.ready_on_read.iter().copied() {
        let ready_applied = Rc::new(Cell::new(false));
        uc.add_mem_hook(
            HookType::MEM_READ,
            address as u64,
            address as u64 + 3,
            {
                let ready_applied = Rc::clone(&ready_applied);
                move |cpu, _kind, accessed, _size, value| {
                    if !ready_applied.get() {
                        let mask = 1u32 << bit;
                        let next_value = (value as u32) | mask;
                        ready_applied.set(true);
                        let result = cpu.mem_write(accessed, &next_value.to_le_bytes());
                        println!(
                            "  [experimental ready-bit] first read @0x{accessed:08X}: 0x{:08X} -> 0x{next_value:08X} (bit {bit}, writeback={})",
                            value as u32,
                            if result.is_ok() { "ok" } else { "failed" }
                        );
                    }
                    true
                }
            },
        )
        .map_err(|error| std::io::Error::other(format!("ready-bit hook failed at 0x{address:08X}: {error:?}")))?;
    }

    for address in options.watch_pc.iter().copied() {
        let fired = Rc::new(Cell::new(false));
        uc.add_code_hook(address as u64, address as u64 + 1, {
            let fired = Rc::clone(&fired);
            move |cpu, pc, size| {
                if fired.replace(true) {
                    return;
                }
                let read = |register| cpu.reg_read(register).unwrap_or(0) as u32;
                println!(
                    "  [watch-pc] pc=0x{pc:08X} size={size} cpsr=0x{:08X}                      r0=0x{:08X} r1=0x{:08X} r2=0x{:08X} r3=0x{:08X}                      r4=0x{:08X} r5=0x{:08X} r6=0x{:08X} r7=0x{:08X}                      sp=0x{:08X} lr=0x{:08X}",
                    read(RegisterARM::CPSR),
                    read(RegisterARM::R0),
                    read(RegisterARM::R1),
                    read(RegisterARM::R2),
                    read(RegisterARM::R3),
                    read(RegisterARM::R4),
                    read(RegisterARM::R5),
                    read(RegisterARM::R6),
                    read(RegisterARM::R7),
                    read(RegisterARM::SP),
                    read(RegisterARM::LR)
                );
                let code_dump_len = if address == 0x0800_0870 { 0x180 } else { 0x50 };
                dump_memory_window(cpu, "watch-pc code bytes", (pc as u32).saturating_sub(0x10), code_dump_len);
                let pointer = read(RegisterARM::R0);
                if pointer != 0 && pointer <= 0x0FFF_FFFF {
                    dump_memory_window(cpu, "watch-pc r0 target", pointer, 0x100);
                }
                let pointer_r1 = read(RegisterARM::R1);
                if (0x0800_0000..=0x1FFF_FFFF).contains(&pointer_r1) {
                    dump_memory_window(cpu, "watch-pc r1 source", pointer_r1.saturating_sub(0x10), 0x100);
                }
                let pointer_r2 = read(RegisterARM::R2);
                if (0x1000_0000..=0x1FFF_FFFF).contains(&pointer_r2) {
                    // NOLO's r2 points at the generated HW configuration message
                    // near the top of the 16-MiB SDRAM window. Dump through the
                    // mapped end of SDRAM without reading beyond it.
                    dump_memory_window(
                        cpu,
                        "watch-pc r2 HW-config",
                        pointer_r2.saturating_sub(0x20),
                        0x94,
                    );
                    let mut table_ptr_bytes = [0u8; 4];
                    if cpu.mem_read(pointer_r2 as u64 + 0x70, &mut table_ptr_bytes).is_ok() {
                        let table_ptr = u32::from_le_bytes(table_ptr_bytes);
                        println!("  [watch-pc HW-config table pointer] = 0x{table_ptr:08X}");
                        if (0x0002_0000..=0x00FF_FFFF).contains(&table_ptr) {
                            dump_memory_window(cpu, "watch-pc fixed HW-config table", table_ptr, 0x200);
                        }
                    }
                } else if pointer_r2 != 0 && pointer_r2 <= 0x0FFF_FFFF {
                    dump_memory_window(cpu, "watch-pc r2 target", pointer_r2, 0x100);
                }
                let sp = read(RegisterARM::SP);
                let mut stack_argument = [0u8; 4];
                if cpu.mem_read(sp as u64 + 0x44, &mut stack_argument).is_ok() {
                    let pointer = u32::from_le_bytes(stack_argument);
                    println!("  [watch-pc stack+0x44] = 0x{pointer:08X}");
                    if (0x0800_0000..=0x1FFF_FFFF).contains(&pointer) {
                        dump_memory_window(cpu, "watch-pc stack+0x44 target", pointer, 0x100);
                    }
                }
                if sp != 0 && sp <= 0x0FFF_FFFF {
                    dump_memory_window(cpu, "watch-pc stack", sp.saturating_sub(0x10), 0x70);
                }
            }
        })
        .map_err(|error| std::io::Error::other(format!("watch-pc hook failed at 0x{address:08X}: {error:?}")))?;
    }

    let asic_register_access_count = Rc::new(Cell::new(0usize));
    for (begin, end) in [
        (0x2003_2C00u64, 0x2003_2DFFu64),
        (0xFFD3_2C00u64, 0xFFD3_2DFFu64),
    ] {
        let asic_register_access_count = Rc::clone(&asic_register_access_count);
        uc.add_mem_hook(HookType::MEM_WRITE, begin, end, {
            let asic_register_access_count = Rc::clone(&asic_register_access_count);
            move |cpu, _kind, address, size, value| {
                let count = asic_register_access_count.get();
                if count < 160 {
                    asic_register_access_count.set(count + 1);
                    let pc = cpu.reg_read(RegisterARM::PC).unwrap_or(0);
                    println!(
                        "  [ASIC-write {count:03}] addr=0x{address:08X} size={size} value=0x{value:X} pc=0x{pc:08X}"
                    );
                }
                true
            }
        }).map_err(|error| std::io::Error::other(format!("ASIC write watch failed: {error:?}")))?;
    }

    let status_read_count = Rc::new(Cell::new(0usize));
    // NOLO currently polls this candidate ASIC status register while bringing
    // up the SUBC path. Keep both the 0x200... view and observed 0xFFD... alias
    // visible in the trace. This is diagnostic only; it does not synthesize
    // readiness or claim a completed peripheral model.
    for (begin, end) in [
        (0x2003_2D80u64, 0x2003_2D83u64),
        (0xFFD3_2D80u64, 0xFFD3_2D83u64),
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

    if options.start_image == StartImage::Nolo {
        dump_memory_window(&uc, "NOR helper bytes before first CPU instruction", 0x0800_0860, 0xA0);
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
            if let Some((address, source_pc, service)) = unhandled_rom_fetch.get() {
                stop = format!(
                    "unhandled NOLO ROM fetch 0x{address:08X} from 0x{source_pc:08X} (secure service {service:#06X})"
                );
            }
            break;
        }
        if let Some((address, source_pc, service)) = unhandled_rom_fetch.get() {
            stop = format!(
                "unhandled NOLO ROM fetch 0x{address:08X} from 0x{source_pc:08X} (secure service {service:#06X})"
            );
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
    println!("NOLO secure-service 0x06 HLE calls: {}", secure_service6_calls.get());
    if let Some((address, source_pc, service)) = unhandled_rom_fetch.get() {
        println!(
            "Unhandled ROM boundary: target=0x{address:08X}, caller=0x{source_pc:08X}, service=0x{service:04X}"
        );
    }
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
            println!("Usage: rm217-boot-probe <RM-217.zip> [--entry=mcusw|nolo] [--instructions=1000000] [--trace=96] [--ready-bit=ADDRESS:BIT ...] [--nor-id=MANUFACTURER:DEVICE] [--watch-pc=ADDRESS ...] [--trace-bse-memory] [--hle-secure-service6] [--hle-secure-service6-launch] [--poke=ADDRESS:VALUE] [--memory-tag0=BASE:SIZE] [--ram-tag4=BASE:SIZE] [--ram-tag5=BASE:SIZE] [--no-handoff] [--r0=0] [--r1=0] [--r2=0] [--r3=0]");
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
    fn decodes_only_the_known_secure_service_descriptor() {
        let mut descriptor = [0u8; 16];
        descriptor[..12].copy_from_slice(&SEC_SERVICE_ID);
        descriptor[14..16].copy_from_slice(&6u16.to_le_bytes());
        assert_eq!(decode_secure_service(&descriptor), Some(6));
        descriptor[0] ^= 0xFF;
        assert_eq!(decode_secure_service(&descriptor), None);
        assert_eq!(decode_secure_service(&descriptor[..12]), None);
    }

    #[test]
    fn service6_return_stub_is_explicit_and_separate() {
        assert_eq!(SEC_SERVICE6_THUMB_STUB, [0x00, 0x20, 0x70, 0x47]);
    }

    #[test]
    fn validates_main_mcusw_bb5_launch_entry() {
        let mut image = vec![0u8; 0x500];
        image[0..4].copy_from_slice(&BB5_MAGIC.to_le_bytes());
        image[0x1C..0x20].copy_from_slice(&0x3D0u32.to_le_bytes());
        image[0x3D0 + 0x74..0x3D0 + 0x78]
            .copy_from_slice(&0xF010_03D0u32.to_le_bytes());
        image[0x3D0 + 0x78..0x3D0 + 0x7C]
            .copy_from_slice(&0xF010_0000u32.to_le_bytes());
        assert_eq!(bb5_entry_from_prefix(&image, 0xF010_0000), Some(0xF010_03D0));
        assert_eq!(bb5_entry_from_prefix(&image, 0xF020_0000), None);
        image[0] = 0;
        assert_eq!(bb5_entry_from_prefix(&image, 0xF010_0000), None);
    }

    #[test]
    fn builds_thumb_launch_trampoline_with_four_saved_register_loads() {
        let code = secure_service6_launch_trampoline(0xF010_03D0);
        assert_eq!(&code[0..12], &[0x00, 0x98, 0x01, 0x99, 0x02, 0x9A, 0x03, 0x9B, 0x01, 0x4C, 0x20, 0x47]);
        assert_eq!(u32::from_le_bytes(code[16..20].try_into().unwrap()), 0xF010_03D0);
    }

    #[test]
    fn parses_halfword_aligned_watch_pcs() {
        assert_eq!(parse_watch_pc("0xF0104172").unwrap(), 0xF010_4172);
        assert_eq!(parse_watch_pc("402759534").unwrap(), 402_759_534);
        assert!(parse_watch_pc("0xF0104173").is_err());
        assert!(parse_watch_pc("0x100000000").is_err());
    }

    #[test]
    fn parses_experimental_nor_manufacturer_and_device_ids() {
        assert_eq!(parse_nor_id("0x00EC:0x2208").unwrap(), (0x00EC, 0x2208));
        assert_eq!(parse_nor_id("236:8712").unwrap(), (0x00EC, 0x2208));
        assert!(parse_nor_id("0x10000:0x2208").is_err());
        assert!(parse_nor_id("0xEC:0x10000").is_err());
        assert!(parse_nor_id("0xEC").is_err());
    }

    #[test]
    fn parses_multiple_ready_bit_specifications() {
        let ready = [
            parse_ready_bit("0x20032D20:0").unwrap(),
            parse_ready_bit("0x20032D40:0").unwrap(),
            parse_ready_bit("0x20032D80:0").unwrap(),
        ];
        assert_eq!(ready, [(0x2003_2D20, 0), (0x2003_2D40, 0), (0x2003_2D80, 0)]);
        assert!(parse_ready_bit("0x20032D81:0").is_err());
        assert!(parse_ready_bit("0x100000000:0").is_err());
        assert!(parse_ready_bit("0x20032D80:32").is_err());
        assert!(parse_ready_bit("0x20032D80").is_err());
    }

    #[test]
    fn resolves_nolo_toc_relative_image_addresses() {
        let destinations = [
            0x0020_0000,
            0x0002_5300,
            0xF010_0000,
            0x0002_0300,
            0x0801_0000,
            0x0002_9300,
        ];
        for destination in destinations {
            let relative = image_table_relative_offset(destination, NOLO_TOC_ADDR);
            assert_eq!(NOLO_TOC_ADDR.wrapping_add(relative), destination);
        }
        assert_eq!(NOLO_TOC_ADDR, 0x0000_0400);
    }

    #[test]
    fn parses_supported_start_images() {
        assert_eq!(parse_start_image("mcusw").unwrap(), StartImage::Mcusw);
        assert_eq!(parse_start_image("nolo").unwrap(), StartImage::Nolo);
        assert!(parse_start_image("unknown").is_err());
    }

    #[test]
    fn parses_aligned_32_bit_poke_specification() {
        assert_eq!(parse_poke("0x20050014:0x4").unwrap(), (0x2005_0014, 4));
        assert_eq!(parse_poke("537198612:4").unwrap(), (0x2005_0014, 4));
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
