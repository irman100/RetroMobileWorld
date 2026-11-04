#!/usr/bin/env python3
"""Dependency-free structural checks for the profile and catalogue registries."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
PROFILE_FILE = ROOT / "profiles" / "device-profiles.json"
SOURCE_FILE = ROOT / "catalog" / "sources.json"
ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]+$")
KEYS = {
    "UP", "DOWN", "LEFT", "RIGHT", "SELECT", "SOFT_LEFT", "SOFT_RIGHT", "BACK",
    "CALL", "END", "DIGIT_0", "DIGIT_1", "DIGIT_2", "DIGIT_3", "DIGIT_4",
    "DIGIT_5", "DIGIT_6", "DIGIT_7", "DIGIT_8", "DIGIT_9", "STAR", "HASH",
}
RUNTIMES = {"JAVA_ME", "SYMBIAN"}
STATUSES = {"verified_live", "verified_project", "archived", "historical", "unavailable", "not_checked"}
REDISTRIBUTION = {
    "metadata_only", "link_only", "redistribution_confirmed", "source_license_reviewed",
    "copyleft_review_required", "unknown",
}
VISUALS = {"hit_area", "soft_key", "back_key", "numeric_key", "call_key", "end_key"}
NAVIGATION = {"cross_dpad", "joystick", "s60_ring"}


def fail(message: str) -> None:
    raise ValueError(message)


def load_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:  # Keep CI output tied to the file that failed.
        fail(f"{path.relative_to(ROOT)}: {exc}")
    if not isinstance(value, dict):
        fail(f"{path.relative_to(ROOT)} must contain a JSON object")
    return value


def check_rect(rect: dict, label: str) -> None:
    if not isinstance(rect, dict):
        fail(f"{label}: expected rectangle object")
    try:
        x, y, width, height = (float(rect[k]) for k in ("x", "y", "width", "height"))
    except (KeyError, TypeError, ValueError):
        fail(f"{label}: x, y, width and height must be numeric")
    if min(x, y) < 0 or min(width, height) <= 0 or x + width > 1.0001 or y + height > 1.0001:
        fail(f"{label}: rectangle is outside normalized bounds")


def validate_profiles() -> int:
    doc = load_json(PROFILE_FILE)
    if doc.get("schemaVersion") != 1 or not isinstance(doc.get("profiles"), list) or not doc["profiles"]:
        fail("profiles/device-profiles.json: unsupported schema or empty profiles list")
    seen = set()
    for profile in doc["profiles"]:
        profile_id = profile.get("id", "")
        if not ID_RE.fullmatch(profile_id) or profile_id in seen:
            fail(f"Invalid or duplicate device profile id: {profile_id}")
        seen.add(profile_id)
        display = profile["display"]
        width, height = int(display["width"]), int(display["height"])
        if not (48 <= width <= 2048 and 48 <= height <= 2048):
            fail(f"{profile_id}: invalid logical display size")
        aperture = profile["shell"]["displayAperture"]
        check_rect(aperture, f"{profile_id}.shell.displayAperture")
        aperture_ratio = float(aperture["width"]) * 400 / (float(aperture["height"]) * 900)
        if abs(aperture_ratio - width / height) >= 0.02:
            fail(f"{profile_id}: display aperture aspect ratio does not match logical display")
        keypad = profile["keypad"]
        if keypad["navigation"]["style"] not in NAVIGATION:
            fail(f"{profile_id}: invalid navigation style")
        check_rect(keypad["navigation"]["bounds"], f"{profile_id}.keypad.navigation.bounds")
        mapping = ROOT / "profiles" / keypad["mapping"]
        if not mapping.is_file():
            fail(f"{profile_id}: missing keymap file {keypad['mapping']}")
        controls = keypad.get("controls", [])
        keys = [control.get("key") for control in controls]
        if set(keys) != KEYS or len(keys) != len(set(keys)):
            fail(f"{profile_id}: controls must contain each required canonical key exactly once")
        for control in controls:
            check_rect(control["bounds"], f"{profile_id}.keypad.{control['key']}.bounds")
            if control.get("visual") not in VISUALS or not control.get("label"):
                fail(f"{profile_id}: invalid visual/label for {control['key']}")
        targets = profile.get("runtimeTargets", [])
        families = [target.get("family") for target in targets]
        if not targets or len(families) != len(set(families)) or not set(families) <= RUNTIMES:
            fail(f"{profile_id}: invalid or duplicate runtime targets")
        if profile.get("defaultRuntime") not in families:
            fail(f"{profile_id}: default runtime is not declared")
        for target in targets:
            if target["family"] == "SYMBIAN" and target.get("systemImageRequired") is not True:
                fail(f"{profile_id}: Symbian target must require a user-provided system image")
    return len(doc["profiles"])


def validate_sources() -> int:
    doc = load_json(SOURCE_FILE)
    if doc.get("schemaVersion") != 1 or not isinstance(doc.get("sources"), list):
        fail("catalog/sources.json: unsupported schema or missing sources list")
    seen = set()
    for source in doc["sources"]:
        source_id = source.get("id", "")
        if not ID_RE.fullmatch(source_id) or source_id in seen:
            fail(f"Invalid or duplicate catalogue source id: {source_id}")
        seen.add(source_id)
        parsed = urlparse(source.get("url", ""))
        if parsed.scheme != "https" or not parsed.netloc:
            fail(f"{source_id}: source URL must be HTTPS")
        if source.get("status") not in STATUSES:
            fail(f"{source_id}: unknown source status")
        if source.get("redistribution") not in REDISTRIBUTION:
            fail(f"{source_id}: unknown redistribution status")
        if not source.get("notes"):
            fail(f"{source_id}: source verification notes are required")
        if source.get("status") == "verified_live" and not source.get("lastChecked"):
            fail(f"{source_id}: verified live source must record lastChecked")
    return len(doc["sources"])


def main() -> int:
    profiles = validate_profiles()
    sources = validate_sources()
    print(f"PASS: {profiles} device profiles validated")
    print("PASS: logical display/aperture ratios and per-key hit areas validated")
    print("PASS: all profiles declare the required canonical keys and runtime targets")
    print(f"PASS: {sources} catalogue sources validated with explicit status and rights metadata")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
