import json
from pathlib import Path

Import("env")


def c_string(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


project_dir = Path(env["PROJECT_DIR"])
policy_path = project_dir.parent / "macro_policy.json"
header_path = project_dir / "src" / "macro_policy_generated.h"

with policy_path.open("r", encoding="utf-8") as f:
    policy = json.load(f)

limits = policy.get("limits", {})
blocked_text = policy.get("blocked_text", [])
allowed_key_lines = policy.get("allowed_key_lines", [])

header = [
    "#pragma once",
    "",
    "// Generated from macro_policy.json by scripts/generate_macro_policy.py.",
    "// Edit macro_policy.json, not this file.",
    f"static const size_t MACRO_POLICY_MAX_MACRO_BYTES = {int(limits.get('max_macro_bytes', 2048))};",
    f"static const int MACRO_POLICY_MAX_STRING_CHARS = {int(limits.get('max_string_chars', 160))};",
    f"static const int MACRO_POLICY_MAX_DELAY_MS = {int(limits.get('max_delay_ms', 5000))};",
    "",
    f"static const char *MACRO_POLICY_BLOCKED_TEXT[] = {{{', '.join(c_string(x) for x in blocked_text)}}};",
    f"static const size_t MACRO_POLICY_BLOCKED_TEXT_COUNT = {len(blocked_text)};",
    "",
    f"static const char *MACRO_POLICY_ALLOWED_KEY_LINES[] = {{{', '.join(c_string(x) for x in allowed_key_lines)}}};",
    f"static const size_t MACRO_POLICY_ALLOWED_KEY_LINES_COUNT = {len(allowed_key_lines)};",
    "",
]

header_path.write_text("\n".join(header), encoding="utf-8")
