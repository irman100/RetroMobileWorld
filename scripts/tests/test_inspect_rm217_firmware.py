import struct
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from inspect_rm217_firmware import (
    Region,
    inspect_rm217_boot_entry,
    parse_fpsx,
    parse_vpl,
    select_main_mcusw,
)


def raw_block(address: int, payload: bytes) -> bytes:
    return (
        b"\x54\x01\x17\x0E"
        + bytes(6)
        + struct.pack(">II", len(payload), address)
        + b"\x00"
        + payload
    )


def named_block(name: str, address: int, payload: bytes) -> bytes:
    encoded_name = name.encode("ascii")
    if len(encoded_name) > 12:
        raise ValueError("FPSX region name is longer than 12 bytes")
    return (
        b"\x5D\x01\x27"
        + bytes(20)
        + b"q"
        + encoded_name.ljust(12, b"\x00")
        + bytes(5)
        + struct.pack(">II", len(payload), address)
        + b"\x00"
        + payload
    )


def fpsx(*blocks: bytes) -> bytes:
    return b"\xB2" + struct.pack(">II", 4, 0) + b"".join(blocks)


class Rm217FirmwareInspectionTests(unittest.TestCase):
    def test_named_region_and_contiguous_raw_block_are_joined(self):
        data = fpsx(
            named_block("MCUSW", 0x00320000, b"ABCD"),
            raw_block(0x00320004, b"EFGH"),
        )
        _, _, regions = parse_fpsx(data)
        self.assertEqual(len(regions), 1)
        self.assertEqual(regions[0].name, "MCUSW")
        self.assertEqual(regions[0].address, 0x00320000)
        self.assertEqual(regions[0].payload, b"ABCDEFGH")
        self.assertEqual(regions[0].blocks, 2)

    def test_bb5_special_unmapped_address_is_allowed(self):
        data = fpsx(raw_block(0xFFFFFFFF, b"KEYS"))
        _, _, regions = parse_fpsx(data)
        self.assertEqual(regions[0].address, 0xFFFFFFFF)
        self.assertEqual(regions[0].payload, b"KEYS")

    def test_vpl_manifest_extracts_variant_and_hex_crcs(self):
        xml = b"""<?xml version="1.0"?>
        <VariantPackingList>
          <Variant><VariantIdentification>
            <ProductCode>0537625</ProductCode>
            <Description>RM-217 EURO-C TR</Description>
          </VariantIdentification></Variant>
          <SwVersion>07.30</SwVersion>
          <FileList><File>
            <Name>rm217__07.30.mcusw</Name>
            <Crc>01C45DB7</Crc>
          </File></FileList>
        </VariantPackingList>"""
        manifest = parse_vpl(xml)
        self.assertEqual(manifest["product"], "0537625")
        self.assertEqual(manifest["version"], "07.30")
        self.assertEqual(manifest["files"]["rm217__07.30.mcusw"], 0x01C45DB7)

    def test_vpl_rejects_entity_declarations(self):
        xml = b"<!DOCTYPE x [<!ENTITY y 'z'>]><x/>"
        with self.assertRaises(ValueError):
            parse_vpl(xml)


    def test_selects_largest_mcusw_region_when_duplicate_names_exist(self):
        header = Region("MCUSW", 0x00320000, bytes(1024), 1)
        main = Region("MCUSW", 0x00320000, bytes(4096), 835)
        self.assertIs(select_main_mcusw([header, main]), main)

    def test_decodes_rm217_xip_entry_and_early_stack_literals(self):
        image = bytearray(0x2000)
        struct.pack_into("<I", image, 0, 0x809795A3)
        struct.pack_into("<I", image, 0x1C, 0x3D0)
        struct.pack_into("<I", image, 0x148, 0x3D0)
        struct.pack_into("<I", image, 0x164, 0)
        struct.pack_into("<I", image, 0x3D0, 0xE24F4008)
        struct.pack_into("<I", image, 0x3D0 + 0x68, 0x067308CA)
        struct.pack_into("<I", image, 0x3D0 + 0x6C, 0x1800)
        struct.pack_into("<I", image, 0x3D0 + 0x70, 0x08004000)
        struct.pack_into("<I", image, 0x3D0 + 0x74, 0xF01003D0)
        struct.pack_into("<I", image, 0x3D0 + 0x78, 0xF0100000)

        boot = inspect_rm217_boot_entry(image)
        self.assertIsNotNone(boot)
        assert boot is not None
        self.assertEqual(boot["candidate_entry"], 0xF01003D0)
        self.assertEqual(boot["thumb_entry"], 0xF0100410)
        self.assertEqual(boot["stack_pointer"], 0x08004000)
        self.assertEqual(boot["stack_cookie_address"], 0x08002800)
        self.assertEqual(boot["stack_cookie"], 0x067308CA)


if __name__ == "__main__":
    unittest.main()
