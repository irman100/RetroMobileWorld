import struct
import unittest

from inspect_rm217_firmware import parse_fpsx, parse_vpl


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


if __name__ == "__main__":
    unittest.main()
