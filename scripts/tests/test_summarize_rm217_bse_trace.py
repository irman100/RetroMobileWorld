import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from summarize_rm217_bse_trace import (
    BSE_SLOT_BASE,
    BSE_SLOT_END,
    bse_slot_address,
    bse_slot_index,
    build_summary,
    decode_trace_bytes,
    slot_index_from_address,
)


class BseTraceSummaryTests(unittest.TestCase):
    def test_known_aliases_resolve_to_observed_slots(self):
        cases = {
            0x00000000: 0x10090000,
            0xFFB00000: 0x10093FEC,
            0xFF100000: 0x10093FC4,
            0xFF200000: 0x10093FC8,
            0xE0001000: 0x10093800,
            0xC1600000: 0x10093058,
        }
        for alias, expected_address in cases.items():
            with self.subTest(alias=hex(alias)):
                self.assertEqual(bse_slot_address(alias), expected_address)

    def test_slot_table_bounds_and_alignment(self):
        self.assertEqual(BSE_SLOT_END - BSE_SLOT_BASE, 0x4000)
        self.assertEqual(slot_index_from_address(0x10090000), 0)
        self.assertEqual(slot_index_from_address(0x10093FFC), 0xFFF)
        self.assertIsNone(slot_index_from_address(0x10094000))
        self.assertIsNone(slot_index_from_address(0x10090002))
        self.assertEqual(bse_slot_index(0xFF100000), 0xFF1)

    def test_decodes_utf8_and_windows_powershell_utf16_logs(self):
        sample = "[BSE slot-table-read 000] VA=0x10090000\\n"
        self.assertEqual(decode_trace_bytes(sample.encode("utf-8")), sample)
        self.assertEqual(decode_trace_bytes(sample.encode("utf-16")), sample)
        self.assertEqual(decode_trace_bytes(sample.encode("utf-16-le")), sample)

    def test_trace_reports_the_observed_alias_zero_collision(self):
        trace = """
[BSE mapper args] caller=0xF010489D phys_base=0x00000000 length=0x00100000 bse_alias=0x00000000 alias_slot_1MiB=0x0 slot_address=0x10090000 guest_va_to_pa=0x00000000
[BSE-memory-trace BSE-alloc-buffer-write 000] addr=0x10090000 size=4 value=0x3E pc=0xF0103C3E
[BSE mapper args] caller=0xF01043A5 phys_base=0x00000000 length=0x01600000 bse_alias=0x00000000 alias_slot_1MiB=0x0 slot_address=0x10090000 guest_va_to_pa=0x00000000
[BSE slot-table-read 015] VA=0x10090000 PA=0x10090000 size=4 value=0x3E pc=0xF0103C04
[watch-pc hit=0] pc=0xF0103C0A size=2 r0=0x0000003E
[assertion-format-output] PC=0xF0102FD0
Probe exit code: 0
"""
        summary = build_summary(trace, "fixture.txt")
        self.assertIn("FOUND: static/prelude mapper requests alias 0", summary)
        self.assertIn("FOUND: late NOR allocator requests alias 0", summary)
        self.assertIn("FOUND: guest writes marker 0x3E", summary)
        self.assertIn("FOUND: guest later reads marker 0x3E", summary)
        self.assertIn("Alias-0 collision signature is present", summary)
        self.assertIn("0x0000003E", summary)
        self.assertIn("host probe exit code 0 only means", summary.lower())

    def test_summary_coalesces_slot_values_and_omits_unrelated_mapper_details(self):
        trace = "\n".join([
            "[BSE mapper args] caller=0xF010489D phys_base=0x00000000 length=0x00100000 bse_alias=0x00000000 alias_slot_1MiB=0x0 slot_address=0x10090000 guest_va_to_pa=0x00000000",
            "[BSE mapper args] caller=0xF010489D phys_base=0x00000000 length=0x00100000 bse_alias=0xFF100000 alias_slot_1MiB=0xFF1 slot_address=0x10093FC4 guest_va_to_pa=0xFF100000",
            "[BSE mapper args] caller=0xF01043A5 phys_base=0x00000000 length=0x01600000 bse_alias=0x00000000 alias_slot_1MiB=0x0 slot_address=0x10090000 guest_va_to_pa=0x00000000",
            *[
                f"[BSE mapper args] caller=0xF0104000 phys_base=0x{i*0x100000:08X} length=0x00100000 bse_alias=0x{i*0x100000:08X} alias_slot_1MiB=0x{i:X} slot_address=0x{0x10090000+i*4:08X} guest_va_to_pa=0x{i*0x100000:08X}"
                for i in range(40)
            ],
            "[BSE-memory-trace BSE-alloc-buffer-write 000] addr=0x10090000 size=4 value=0x3E pc=0xF0103C3E",
            "[BSE slot-table-read 000] VA=0x10090000 PA=0x10090000 size=4 value=0x3E pc=0xF0103C04",
        ])
        summary = build_summary(trace)
        self.assertIn("KEY MAPPER CALLS", summary)
        self.assertIn("static prelude", summary)
        self.assertIn("static alias FF1", summary)
        self.assertIn("late NOR allocator", summary)
        self.assertIn(
            "idx=0x000 addr=0x10090000 read[PA:value]=[0x10090000:0x0000003E] write[value]=[0x0000003E]",
            summary,
        )
        self.assertNotIn("other", summary)
        self.assertIn("Mapper calls parsed: 43", summary)
        self.assertLess(len(summary.splitlines()), 60)

    def test_summary_flags_wrong_logged_slot_index(self):
        trace = (
            "[BSE mapper args] caller=0xF010489D phys_base=0x00000000 "
            "length=0x00100000 bse_alias=0xFF100000 alias_slot_1MiB=0x000 "
            "slot_address=0x10090000 guest_va_to_pa=0xFF100000\n"
        )
        summary = build_summary(trace)
        self.assertIn("WARNING:", summary)
        self.assertIn("logged alias index", summary)
        self.assertIn("logged slot address", summary)
        self.assertIn("NOT present", summary)


if __name__ == "__main__":
    unittest.main()
