#!/usr/bin/env python3
"""Inspect a user-supplied Nokia RM-217 Phoenix firmware ZIP without extracting it.

This read-only tool never executes installer files, writes firmware payloads,
modifies a phone, or copies proprietary assets into the project.
"""
from __future__ import annotations

import argparse
import hashlib
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile
import zlib
from dataclasses import dataclass
from pathlib import PurePosixPath

MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
MAX_COMPONENT_BYTES = 256 * 1024 * 1024
MAX_ENTRIES = 4096


@dataclass
class Region:
    name: str | None
    address: int
    payload: bytes
    blocks: int


def u32be(data: bytes, offset: int) -> int:
    if offset < 0 or offset + 4 > len(data):
        raise ValueError(f"truncated 32-bit field at 0x{offset:x}")
    return struct.unpack_from(">I", data, offset)[0]


def u32le(data: bytes, offset: int) -> int:
    if offset < 0 or offset + 4 > len(data):
        raise ValueError(f"truncated 32-bit field at 0x{offset:x}")
    return struct.unpack_from("<I", data, offset)[0]


def parse_fpsx(data: bytes) -> tuple[int, int, list[Region]]:
    if len(data) < 9 or data[0] != 0xB2:
        raise ValueError("not an FPSX (B2) container")
    header_length = u32be(data, 1)
    tlv_count = u32be(data, 5)
    header_end = 5 + header_length
    if header_end < 9 or header_end > len(data):
        raise ValueError("FPSX header length is outside the file")
    if tlv_count > 4096:
        raise ValueError("FPSX has too many TLV records")

    pos = 9
    for _ in range(tlv_count):
        if pos + 2 > header_end:
            raise ValueError("truncated FPSX TLV header")
        value_len = data[pos + 1]
        pos += 2 + value_len
        if pos > header_end:
            raise ValueError("FPSX TLV exceeds header boundary")

    regions: list[Region] = []
    current_name: str | None = None
    block_count = 0
    pos = header_end
    while pos < len(data):
        block_type = data[pos]
        if block_type == 0xFF:
            pos += 1
            continue
        block_count += 1
        if block_count > 32768:
            raise ValueError("FPSX has too many data blocks")
        if block_type == 0x54:
            header = pos + 4
            length_offset = header + 6
            data_offset = header + 15
        elif block_type == 0x5D:
            header = pos + 36
            if pos + 36 > len(data):
                raise ValueError("truncated FPSX named-block header")
            raw_name = data[pos + 24:pos + 36].split(b"\0", 1)[0]
            current_name = raw_name.decode("ascii", "replace") or None
            length_offset = header + 5
            data_offset = header + 14
        else:
            raise ValueError(f"unknown FPSX block type 0x{block_type:02x} at 0x{pos:x}")

        length = u32be(data, length_offset)
        address = u32be(data, length_offset + 4)
        if length > MAX_COMPONENT_BYTES or data_offset + length > len(data):
            raise ValueError(f"FPSX block at 0x{pos:x} extends beyond file bounds")
        # PAPUBKEYS in BB5 packages can use 0xFFFFFFFF as a special/non-mapped address.
        if address != 0xFFFFFFFF and address + length > 0x1_0000_0000:
            raise ValueError("FPSX flash address range overflows 32 bits")
        payload = data[data_offset:data_offset + length]

        if block_type == 0x54 and regions and regions[-1].address + len(regions[-1].payload) == address:
            regions[-1].payload += payload
            regions[-1].blocks += 1
        else:
            regions.append(Region(current_name if block_type == 0x5D else None, address, payload, 1))
        pos = data_offset + length

    if not regions:
        raise ValueError("FPSX contains no data regions")
    return header_length, tlv_count, regions


def select_main_mcusw(regions: list[Region]) -> Region:
    """Choose the main executable MCUSW when BB5 stores a duplicate header region."""
    candidates = [region for region in regions if region.name == "MCUSW"]
    if not candidates:
        raise ValueError("MCU contains no named MCUSW region")
    return max(candidates, key=lambda region: len(region.payload))


def inspect_rm217_boot_entry(payload: bytes) -> dict[str, int] | None:
    """Read RM-217 first-stage entry literals; static analysis, not a boot."""
    if len(payload) < 0x14C or u32le(payload, 0) != 0x809795A3:
        return None
    entry_offset = u32le(payload, 0x1C)
    header_hint = u32le(payload, 0x148)
    if entry_offset + 0x7C > len(payload):
        return None
    first_word = u32le(payload, entry_offset)
    xip_entry = u32le(payload, entry_offset + 0x74)
    xip_base = u32le(payload, entry_offset + 0x78)
    stack_pointer = u32le(payload, entry_offset + 0x70)
    stack_cookie = u32le(payload, entry_offset + 0x68)
    stack_reserve = u32le(payload, entry_offset + 0x6C)
    early_global = u32le(payload, 0x164) if len(payload) >= 0x168 else 0xFFFFFFFF
    thumb_offset = entry_offset + 0x40
    return {
        "entry_offset": entry_offset,
        "header_hint": header_hint,
        "first_word": first_word,
        "xip_entry_literal": xip_entry,
        "xip_base": xip_base,
        "candidate_entry": xip_base + entry_offset,
        "thumb_entry": xip_base + thumb_offset,
        "stack_pointer": stack_pointer,
        "stack_cookie": stack_cookie,
        "stack_reserve": stack_reserve,
        "stack_cookie_address": (stack_pointer - stack_reserve) & 0xFFFFFFFF,
        "early_global_0x164": early_global,
    }


def parse_vpl(data: bytes) -> dict:
    if len(data) > 1024 * 1024:
        raise ValueError("VPL manifest exceeds 1 MiB")
    upper = data.upper()
    if b"<!DOCTYPE" in upper or b"<!ENTITY" in upper:
        raise ValueError("VPL must not contain DTD or entity declarations")
    root = ET.fromstring(data)
    product = root.findtext(".//VariantIdentification/ProductCode")
    version = root.findtext(".//SwVersion")
    description = root.findtext(".//VariantIdentification/Description") or ""
    files: dict[str, int] = {}
    for item in root.findall(".//FileList/File"):
        name = item.findtext("Name")
        crc = item.findtext("Crc")
        if name and crc and len(crc.strip()) == 8:
            try:
                files[name.strip().lower()] = int(crc.strip(), 16)
            except ValueError:
                continue
    if not product or not version:
        raise ValueError("VPL is missing ProductCode or SwVersion")
    return {
        "product": product.strip(),
        "version": version.strip(),
        "description": description.strip(),
        "files": files,
    }


def _midlet_field(fragment: bytes, label: bytes) -> str:
    match = re.search(
        rb"(?m)^" + re.escape(label) + rb"\s*:\s*([^\r\n]+)",
        fragment,
    )
    return match.group(1).decode("ascii", "replace").strip() if match else "unknown"


def inspect_archive(path: str, product_code: str) -> int:
    with zipfile.ZipFile(path, "r") as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ENTRIES:
            raise ValueError(f"archive contains more than {MAX_ENTRIES} entries")
        expanded = sum(i.file_size for i in infos)
        if expanded > MAX_ARCHIVE_BYTES:
            raise ValueError("archive expanded size exceeds 512 MiB")
        bad = archive.testzip()
        if bad:
            raise ValueError(f"ZIP CRC failure in {bad}")

        by_basename: dict[str, list[str]] = {}
        for info in infos:
            basename = PurePosixPath(info.filename.replace("\\", "/")).name
            by_basename.setdefault(basename.lower(), []).append(info.filename)

        manifests = []
        for info in infos:
            if not info.filename.lower().endswith(".vpl") or info.file_size > 1024 * 1024:
                continue
            try:
                vpl = parse_vpl(archive.read(info))
                if vpl["product"] == product_code:
                    manifests.append((info.filename, vpl))
            except (ValueError, ET.ParseError):
                continue

        if len(manifests) != 1:
            raise ValueError(
                f"expected one VPL for product code {product_code}, found {len(manifests)}"
            )
        vpl_name, manifest = manifests[0]
        version = manifest["version"]
        print(f"Archive: {path}")
        print(f"ZIP integrity: OK ({len(infos)} entries; {expanded:,} expanded bytes)")
        print(f"VPL: {vpl_name}")
        print(f"Product code: {product_code}")
        print(f"Variant: {manifest['description']}")
        print(f"Software version: {version}")
        print()

        expected_names = [
            f"rm217__{version}.mcusw",
            f"rm217__{version}.ppm_c",
            f"rm217__{version}.image_c_tr",
        ]
        result: dict[str, bytes] = {}
        failures = 0
        for name in expected_names:
            matches = by_basename.get(name.lower(), [])
            if len(matches) != 1:
                print(f"MISSING/AMBIGUOUS: {name} ({len(matches)} matching archive entries)")
                failures += 1
                continue
            info = archive.getinfo(matches[0])
            if info.file_size > MAX_COMPONENT_BYTES:
                print(f"TOO LARGE: {name}")
                failures += 1
                continue

            data = archive.read(info)
            actual_crc = zlib.crc32(data) & 0xFFFFFFFF
            expected_crc = manifest["files"].get(name.lower())
            print(name)
            print(f"  size: {len(data):,} bytes")
            print(f"  SHA-256: {hashlib.sha256(data).hexdigest()}")
            print(f"  ZIP CRC-32: {info.CRC:08X}")
            print(f"  VPL CRC-32: {expected_crc:08X}" if expected_crc is not None else "  VPL CRC-32: MISSING")
            print(f"  Actual CRC-32: {actual_crc:08X}")
            if expected_crc is None or actual_crc != expected_crc or info.CRC != actual_crc:
                print("  Integrity: MISMATCH")
                failures += 1
            else:
                print("  Integrity: MATCH")
            result[name] = data

            try:
                header_length, tlv_count, regions = parse_fpsx(data)
                block_total = sum(region.blocks for region in regions)
                print(
                    f"  FPSX: header={header_length} bytes; TLVs={tlv_count}; "
                    f"blocks={block_total}; regions={len(regions)}"
                )
                for region in regions:
                    label = region.name or "(unnamed)"
                    print(
                        f"    {label}: flash=0x{region.address:08X}; "
                        f"size={len(region.payload):,}; blocks={region.blocks}"
                    )
            if name.lower().endswith(".mcusw"):
                main_region = select_main_mcusw(regions)
                print(
                    f"  selected MCUSW: flash=0x{main_region.address:08X}; "
                    f"size={len(main_region.payload):,}; blocks={main_region.blocks}"
                )
                boot = inspect_rm217_boot_entry(main_region.payload)
                if boot is None:
                    print("  RM-217 boot probe: BB5 entry header could not be decoded")
                else:
                    print(
                        f"  RM-217 boot probe: file offset=0x{boot['entry_offset']:X}; "
                        f"header hint=0x{boot['header_hint']:08X}"
                    )
                    print(
                        f"    first ARM word=0x{boot['first_word']:08X}; "
                        f"candidate XIP base=0x{boot['xip_base']:08X}"
                    )
                    print(
                        f"    candidate ARM entry=0x{boot['candidate_entry']:08X}; "
                        f"candidate Thumb continuation=0x{boot['thumb_entry']:08X}"
                    )
                    print(
                        f"    early stack=0x{boot['stack_pointer']:08X}; "
                        f"reserved scratch=0x{boot['stack_cookie_address']:08X}; "
                        f"scratch marker=0x{boot['stack_cookie']:08X}"
                    )
                    print(
                        f"    early global [image+0x164]=0x{boot['early_global_0x164']:08X}"
                    )
                    print(
                        "    NOTE: candidate mapping/entry only; this does not prove "
                        "that the machine can boot."
                    )
            except ValueError as error:
                print(f"  FPSX parse error: {error}")
                failures += 1
            print()

        image_name = f"rm217__{version}.image_c_tr"
        if image_name in result:
            image = result[image_name]
            for token in (b"OPERA_~1JAD", b"OPERA_~1JAR"):
                print(
                    f"Factory content has file entry {token.decode('ascii')}: "
                    f"{'yes' if token in image else 'no'}"
                )
            opera_marker = image.find(b"MIDlet-1: Opera Mini")
            manifest_at = image.rfind(b"MIDlet-Version:", 0, opera_marker) if opera_marker >= 0 else -1
            if manifest_at >= 0:
                fragment = image[manifest_at:manifest_at + 4096]
                print("Factory Opera Mini MIDlet metadata:")
                labels = (
                    b"MIDlet-Version",
                    b"MIDlet-1",
                    b"MIDlet-Jar-Size",
                    b"MIDlet-Jar-URL",
                    b"MIDlet-Name",
                    b"MIDlet-Vendor",
                    b"MicroEdition-Configuration",
                    b"MicroEdition-Profile",
                )
                for label in labels:
                    print(f"  {label.decode('ascii')}: {_midlet_field(fragment, label)}")
        print()

        if failures:
            print(
                f"RESULT: {failures} integrity/format issue(s); "
                "do not treat this set as fully verified."
            )
            return 2
        print("RESULT: selected VPL components and FPSX containers verified.")
        return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", help="user-supplied RM-217 Phoenix ZIP archive")
    parser.add_argument(
        "--product-code",
        default="0537625",
        help="Nokia product code (default: 0537625)",
    )
    args = parser.parse_args()
    try:
        return inspect_archive(args.archive, args.product_code)
    except (OSError, zipfile.BadZipFile, ValueError, ET.ParseError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
