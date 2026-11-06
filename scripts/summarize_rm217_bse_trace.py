#!/usr/bin/env python3
"""Create a compact, evidence-only report from an RM-217 BSE probe trace."""

from __future__ import annotations

import argparse
import codecs
import re
from pathlib import Path

BSE_SLOT_BASE = 0x10090000
BSE_SLOT_COUNT = 0x1000
BSE_SLOT_BYTES = 4
BSE_SLOT_END = BSE_SLOT_BASE + BSE_SLOT_COUNT * BSE_SLOT_BYTES

MAPPER_RE = re.compile(
    r"\[BSE mapper args\]\s+caller=0x([0-9A-Fa-f]+)\s+"
    r"phys_base=0x([0-9A-Fa-f]+)\s+length=0x([0-9A-Fa-f]+)\s+"
    r"bse_alias=0x([0-9A-Fa-f]+)\s+alias_slot_1MiB=0x([0-9A-Fa-f]+)"
    r"(?:\s+slot_address=0x([0-9A-Fa-f]+))?"
)
SLOT_READ_RE = re.compile(
    r"\[BSE slot-table-read\s+(\d+)\]\s+VA=0x([0-9A-Fa-f]+)\s+"
    r"PA=0x([0-9A-Fa-f]+)\s+size=(\d+)\s+value=0x([0-9A-Fa-f]+)\s+"
    r"pc=0x([0-9A-Fa-f]+)"
)
ALLOC_WRITE_RE = re.compile(
    r"\[BSE-memory-trace BSE-alloc-buffer-write\s+(\d+)\]\s+"
    r"addr=0x([0-9A-Fa-f]+)\s+size=(\d+)\s+value=0x([0-9A-Fa-f]+)\s+"
    r"pc=0x([0-9A-Fa-f]+)"
)
MAPPER_WATCH_RE = re.compile(
    r"\[watch-pc hit=(\d+)\]\s+pc=0xF0103ECE\b.*?"
    r"\br0=0x([0-9A-Fa-f]+)\s+r1=0x([0-9A-Fa-f]+)\s+"
    r"r2=0x([0-9A-Fa-f]+).*?\blr=0x([0-9A-Fa-f]+)"
)
ASSERT_WATCH_RE = re.compile(
    r"\[watch-pc hit=\d+\]\s+pc=0xF0103C0A\b.*?\br0=0x([0-9A-Fa-f]+)"
)
CURSOR_RE = re.compile(
    r"\[watch-pc static-map (?:cursor|entry)\]\s+"
    r"(?:loop-index|index)=(\d+)\s+(?:cursor|entry)=0x([0-9A-Fa-f]+)"
)
TYPE8_PTR_RE = re.compile(
    r"\[watch-pc allocator type-8 record\]\s+ptr=0x([0-9A-Fa-f]+)"
)
ALLOCATOR_INTERVAL_RE = re.compile(
    r"\[watch-pc allocator interval (after type-8 scan|before mapper)\]\s+"
    r"base=0x([0-9A-Fa-f]+)\s+length=0x([0-9A-Fa-f]+)\s+"
    r"extra38=0x([0-9A-Fa-f]+)\s+extra3C=0x([0-9A-Fa-f]+)\s+"
    r"source-list=0x([0-9A-Fa-f]+)\s+map-root=0x([0-9A-Fa-f]+)"
)
MEMORY_WORDS_RE = re.compile(r"^\s*(0x[0-9A-Fa-f]+):\s+((?:[0-9A-Fa-f]{8}\s*)+)$")
EXIT_RE = re.compile(r"Probe exit code:\s*(-?\d+)")
STOP_RE = re.compile(r"(?m)^=== PROBE STOP ===\s*\n([^\r\n]+)")


def bse_slot_index(alias: int) -> int:
    """Return the 1-MiB alias slot index used by the observed firmware."""
    return alias >> 20


def bse_slot_address(alias: int) -> int:
    """Return the address of the u32 slot in the observed BSE table."""
    return BSE_SLOT_BASE + bse_slot_index(alias) * BSE_SLOT_BYTES


def slot_index_from_address(address: int) -> int | None:
    offset = address - BSE_SLOT_BASE
    if offset < 0 or offset >= BSE_SLOT_COUNT * BSE_SLOT_BYTES:
        return None
    if offset % BSE_SLOT_BYTES:
        return None
    return offset // BSE_SLOT_BYTES


def _hex(value: int, width: int = 8) -> str:
    return f"0x{value:0{width}X}"


def decode_trace_bytes(data: bytes) -> str:
    """Decode UTF-8 or Windows PowerShell UTF-16 redirection output."""
    if data.startswith((codecs.BOM_UTF16_LE, codecs.BOM_UTF16_BE)):
        return data.decode("utf-16", errors="replace")
    if data.startswith(codecs.BOM_UTF8):
        return data.decode("utf-8-sig", errors="replace")
    # Windows PowerShell 5.1 may emit UTF-16LE without a BOM in some pipelines.
    sample = data[:4096]
    if b"\x00" in sample:
        even_nulls = sample[0::2].count(0)
        odd_nulls = sample[1::2].count(0)
        if odd_nulls > even_nulls:
            return data.decode("utf-16-le", errors="replace")
        if even_nulls > odd_nulls:
            return data.decode("utf-16-be", errors="replace")
    return data.decode("utf-8", errors="replace")


def build_summary(text: str, source_name: str = "<trace>") -> str:
    lines = text.splitlines()
    mapper_calls: list[dict[str, int]] = []
    slot_reads: list[dict[str, int]] = []
    slot_writes: list[dict[str, int]] = []
    cursor_rows: list[tuple[int, int]] = []
    type8_pointers: list[int] = []
    allocator_intervals: list[dict[str, int | str]] = []
    memory_words: dict[int, int] = {}
    warnings: list[str] = []

    for line in lines:
        match = MAPPER_RE.search(line)
        if match:
            caller, phys, length, alias, logged_index, logged_address = match.groups()
            record = {
                "caller": int(caller, 16),
                "phys": int(phys, 16),
                "length": int(length, 16),
                "alias": int(alias, 16),
                "logged_index": int(logged_index, 16),
            }
            record["index"] = bse_slot_index(record["alias"])
            record["slot_address"] = bse_slot_address(record["alias"])
            if logged_address is not None:
                record["logged_address"] = int(logged_address, 16)
                if record["logged_address"] != record["slot_address"]:
                    warnings.append(
                        f"mapper caller {_hex(record['caller'])}: logged slot address "
                        f"{_hex(record['logged_address'])} != formula {_hex(record['slot_address'])}"
                    )
            if record["logged_index"] != record["index"]:
                warnings.append(
                    f"mapper caller {_hex(record['caller'])}: logged alias index "
                    f"{_hex(record['logged_index'], 3)} != formula {_hex(record['index'], 3)}"
                )
            mapper_calls.append(record)

        match = SLOT_READ_RE.search(line)
        if match:
            sequence, va, pa, size, value, pc = match.groups()
            address = int(va, 16)
            slot_reads.append({
                "sequence": int(sequence),
                "va": address,
                "pa": int(pa, 16),
                "size": int(size),
                "value": int(value, 16),
                "pc": int(pc, 16),
                "index": slot_index_from_address(address),
            })

        match = ALLOC_WRITE_RE.search(line)
        if match:
            sequence, address, size, value, pc = match.groups()
            address_value = int(address, 16)
            if BSE_SLOT_BASE <= address_value < BSE_SLOT_END:
                slot_writes.append({
                    "sequence": int(sequence),
                    "address": address_value,
                    "size": int(size),
                    "value": int(value, 16),
                    "pc": int(pc, 16),
                    "index": slot_index_from_address(address_value),
                })

        match = CURSOR_RE.search(line)
        if match:
            cursor_rows.append((int(match.group(1)), int(match.group(2), 16)))

        match = TYPE8_PTR_RE.search(line)
        if match:
            type8_pointers.append(int(match.group(1), 16))

        match = ALLOCATOR_INTERVAL_RE.search(line)
        if match:
            phase, base, length, extra38, extra3c, source_list, map_root = match.groups()
            allocator_intervals.append({
                "phase": phase,
                "base": int(base, 16),
                "length": int(length, 16),
                "alias_extra38": int(extra38, 16),
                "extra3c": int(extra3c, 16),
                "source_list": int(source_list, 16),
                "map_root": int(map_root, 16),
            })

        match = MEMORY_WORDS_RE.match(line)
        if match:
            address, words = match.groups()
            address_value = int(address, 16)
            # BSE descriptors and stacks are in this observed SDRAM window.
            # Ignore code dumps and unrelated target windows.
            if 0x08000000 <= address_value < 0x08100000:
                for word_index, word in enumerate(words.split()):
                    memory_words[address_value + 4 * word_index] = int(word, 16)

    exit_match = EXIT_RE.search(text)
    stop_match = STOP_RE.search(text)
    assertion_values = [int(x, 16) for x in ASSERT_WATCH_RE.findall(text)]
    assertion_formatter = "assertion-format-output" in text

    static_zero = [
        call for call in mapper_calls
        if call["caller"] == 0xF010489D
        and call["alias"] == 0
        and call["length"] == 0x00100000
    ]
    nor_zero = [
        call for call in mapper_calls
        if call["caller"] == 0xF01043A5
        and call["alias"] == 0
        and call["length"] == 0x01600000
    ]
    marker_reads = [
        read for read in slot_reads
        if read["va"] == BSE_SLOT_BASE and read["value"] == 0x3E
    ]
    marker_writes = [
        write for write in slot_writes
        if write["address"] == BSE_SLOT_BASE and write["value"] == 0x3E
    ]

    out = [
        "RM-217 BSE TRACE SUMMARY",
        f"Source: {source_name}",
        f"Lines scanned: {len(lines)}",
        f"Mapper calls parsed: {len(mapper_calls)}",
        f"Slot-table reads parsed: {len(slot_reads)}",
        f"Slot-table writes parsed: {len(slot_writes)}",
    ]

    if exit_match:
        out.append(f"Host probe exit code: {exit_match.group(1)}")
    if stop_match:
        out.append(f"Probe stop: {stop_match.group(1)}")

    out.append("")
    out.append("KEY MAPPER CALLS (other calls counted, details omitted)")
    key_callers = {0xF01041CF, 0xF01042E1, 0xF01043A5}
    key_calls = [
        call for call in mapper_calls
        if (call["caller"] == 0xF010489D and call["alias"] in (0x00000000, 0xFF100000))
        or call["caller"] in key_callers
    ]
    for call in key_calls:
        if call["caller"] == 0xF010489D and call["alias"] == 0:
            role = "static prelude"
        elif call["caller"] == 0xF010489D and call["alias"] == 0xFF100000:
            role = "static alias FF1"
        elif call["caller"] == 0xF01041CF:
            role = "earlier high range"
        elif call["caller"] == 0xF01042E1:
            role = "earlier low window"
        else:
            role = "late NOR allocator"
        out.append(
            f"- {role:18s} base={_hex(call['phys'])} len={_hex(call['length'])} "
            f"alias={_hex(call['alias'])} index={_hex(call['index'], 3)} "
            f"slot={_hex(call['slot_address'])}"
        )
    if not key_calls:
        out.append("- No expected static-prelude, alias-FF1 or late NOR mapper calls parsed.")

    out.append("")
    out.append("TYPE-8 OCCUPIED RANGE / FREE INTERVAL")
    for pointer in dict.fromkeys(type8_pointers):
        size = memory_words.get(pointer + 0x08)
        base = memory_words.get(pointer + 0x0C)
        if size is not None and base is not None:
            end = base + size
            out.append(
                f"- descriptor={_hex(pointer)} raw+08(size)={_hex(size)} "
                f"raw+0C(start)={_hex(base)} end={_hex(end)}"
            )
        else:
            out.append(f"- descriptor={_hex(pointer)} fields +08/+0C not both present in trace dumps")
    if not type8_pointers:
        out.append("- No type-8 record pointer captured.")
    for interval in allocator_intervals:
        out.append(
            f"- {interval['phase']}: base={_hex(int(interval['base']))} "
            f"length={_hex(int(interval['length']))} "
            f"alias-extra38={_hex(int(interval['alias_extra38']))} "
            f"source={_hex(int(interval['source_list']))} map-root={_hex(int(interval['map_root']))}"
        )

    # Coalesce individual reads/writes by guest slot address. Keep PA only
    # beside real MEM_READ observations; do not invent a PA for write events.
    slot_events: dict[tuple[int | None, int], dict[str, list[tuple[int, int] | int]]] = {}
    for read in slot_reads:
        key = (read["index"], read["va"])
        slot_events.setdefault(key, {"reads": [], "writes": []})["reads"].append(
            (read["pa"], read["value"])
        )
    for write in slot_writes:
        index = slot_index_from_address(write["address"])
        key = (index, write["address"])
        slot_events.setdefault(key, {"reads": [], "writes": []})["writes"].append(write["value"])

    out.append("")
    out.append("BSE SLOT VALUES (one row per observed guest slot)")
    rows = sorted(slot_events.items(), key=lambda item: (
        item[0][0] is None,
        item[0][0] if item[0][0] is not None else item[0][1],
        item[0][1],
    ))
    for (index, address), values in rows[:24]:
        index_text = "outside" if index is None else _hex(index, 3)
        reads = ",".join(
            f"{_hex(pa)}:{_hex(value)}"
            for pa, value in dict.fromkeys(values["reads"])
        ) or "-"
        writes = ",".join(_hex(value) for value in dict.fromkeys(values["writes"])) or "-"
        out.append(
            f"- idx={index_text} addr={_hex(address)} read[PA:value]=[{reads}] "
            f"write[value]=[{writes}]"
        )
    if len(rows) > 24:
        out.append(f"- ... {len(rows) - 24} additional slots omitted")

    out.append("")
    out.append("TRACE SIGNATURES")
    if static_zero:
        out.append("FOUND: static/prelude mapper requests alias 0 for a 1-MiB range.")
    else:
        out.append("NOT FOUND: expected static/prelude alias-0 mapping was not parsed.")
    if nor_zero:
        out.append("FOUND: late NOR allocator requests alias 0 for a 0x01600000 range.")
    else:
        out.append("NOT FOUND: expected late NOR alias-0 allocator call was not parsed.")
    if marker_writes:
        out.append("FOUND: guest writes marker 0x3E to BSE slot 0 (0x10090000).")
    else:
        out.append("NOT FOUND: marker write 0x3E to BSE slot 0 was not parsed.")
    if marker_reads:
        out.append("FOUND: guest later reads marker 0x3E from BSE slot 0.")
    else:
        out.append("NOT FOUND: marker read 0x3E from BSE slot 0 was not parsed.")
    if assertion_values:
        out.append(
            "Observed PC 0xF0103C0A register r0 values: "
            + ", ".join(_hex(value) for value in assertion_values)
        )
    if assertion_formatter:
        out.append("Observed: guest assertion formatter path was entered.")

    out.append("")
    out.append("VALIDATION")
    if static_zero and nor_zero and marker_writes and marker_reads:
        out.append(
            "Alias-0 collision signature is present: the static/prelude call and "
            "late NOR allocator resolve to the same BSE slot, and marker 0x3E "
            "is written then read there."
        )
    else:
        out.append(
            "The complete alias-0 collision signature is NOT present in the parsed "
            "trace; inspect the missing section before drawing a conclusion."
        )
    if warnings:
        for warning in warnings:
            out.append(f"WARNING: {warning}")
    else:
        out.append("No mismatch found between parsed alias indices and the slot formula.")

    out.append("")
    out.append(
        "IMPORTANT: host probe exit code 0 only means the probe process completed; "
        "it is not evidence that the guest firmware booted successfully."
    )
    return "\n".join(out) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path, help="full rm217-bse-alias trace text file")
    parser.add_argument("--output", type=Path, help="also write the compact summary to this path")
    args = parser.parse_args()

    try:
        raw = decode_trace_bytes(args.trace.read_bytes())
    except OSError as exc:
        parser.error(f"cannot read trace {args.trace}: {exc}")

    report = build_summary(raw, str(args.trace))
    print(report, end="")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding="utf-8")
        print(f"Summary saved: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
