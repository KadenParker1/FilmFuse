#!/usr/bin/env python3
"""
Compares client/types/api.ts against the core-api DTO records it mirrors.

api.ts is hand-maintained and nothing else checks it: a field added to a DTO and forgotten there is
invisible until something reads `undefined` at runtime. (Ported from Bellavera's checker.)

Checks, by type name:
  * every TS interface in api.ts names a Java DTO record, and the two have the same field *names*;
  * every Java DTO record has a mirror in api.ts, unless it is in SERVER_ONLY with a reason.

Field types are deliberately not compared: `Instant` and `UUID` are `string`, `int` is `number`, a
`Map` is `Record<string, ...>`, and encoding those mappings would cost more than it catches. A
missing or misspelled key is the failure this exists for.

Java DTOs are the records under core-api/src/main/java/com/pm/filmfuse/**/dto/ plus the shared
error shape in common/exception/ErrorResponseDTO.java.

Exit 0 when the mirror agrees, 1 when it does not. Stdlib only. Run from the repository root:

    python3 tools/check_api_type_drift.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BACKEND = ROOT / "core-api" / "src" / "main" / "java" / "com" / "pm" / "filmfuse"
EXTRA_DTO_FILES = [BACKEND / "common" / "exception" / "ErrorResponseDTO.java"]
MIRROR = ROOT / "client" / "types" / "api.ts"

# Java DTOs that legitimately never reach the client, so need no mirror in api.ts.
# Every entry needs a reason - an unexplained entry here is how a real drift gets waved through.
SERVER_ONLY: set[str] = set()

# TS interfaces in api.ts that mirror no Java record (e.g. client-side shapes). Same rule: give a reason.
CLIENT_ONLY: set[str] = set()


def read(path: pathlib.Path) -> str:
    # Explicit UTF-8: a CI runner with no LANG set gives Python an ASCII default.
    return path.read_text(encoding="utf-8")


def strip_comments_and_strings(text: str) -> str:
    # Comments can hold commas ("valid and not taken, ...") and string literals in annotation
    # messages can hold commas or parens; either would split a component in the wrong place.
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', text)


def java_record_fields(text: str) -> dict[str, set[str]]:
    """Record components by record name, with parentheses balanced so annotations survive."""
    text = strip_comments_and_strings(text)
    found: dict[str, set[str]] = {}
    for match in re.finditer(r"\brecord\s+(\w+)\s*(?:<[^>]*>)?\s*\(", text):
        open_paren = match.end() - 1
        depth, index = 0, open_paren
        while index < len(text):
            if text[index] == "(":
                depth += 1
            elif text[index] == ")":
                depth -= 1
                if depth == 0:
                    break
            index += 1
        found[match.group(1)] = split_components(text[open_paren + 1:index])
    return found


def split_components(params: str) -> set[str]:
    parts, current, depth = [], "", 0
    for char in params:
        if char in "<([":
            depth += 1
        elif char in ">)]":
            depth -= 1
        if char == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += char
    parts.append(current)

    names = set()
    for part in parts:
        part = re.sub(r"@[\w.]+(\s*\([^()]*\))?", "", part).strip()  # annotations
        if part:
            names.add(part.split()[-1])
    return names


def ts_interface_fields(text: str) -> dict[str, set[str]]:
    found = {}
    # Generic interfaces (`PageDTO<T>`) compare by bare name, as Java's `record PageDTO<T>(...)` does.
    for match in re.finditer(r"export interface (\w+)\s*(?:<[^>{]*>)?\s*\{(.*?)\n\}", text, re.S):
        body = re.sub(r"/\*.*?\*/", "", match.group(2), flags=re.S)
        body = re.sub(r"//[^\n]*", "", body)
        found[match.group(1)] = set(re.findall(r"^\s{2}(\w+)\??:", body, re.M))
    return found


def dto_files() -> list[pathlib.Path]:
    files = {p for p in BACKEND.rglob("*.java") if "dto" in p.relative_to(BACKEND).parts[:-1]}
    files.update(p for p in EXTRA_DTO_FILES if p.exists())
    return sorted(files)


def main() -> int:
    backend: dict[str, set[str]] = {}
    for path in dto_files():
        backend.update(java_record_fields(read(path)))
    mirror = ts_interface_fields(read(MIRROR))

    problems: list[str] = []
    checked = 0
    for name in sorted(set(backend) | set(mirror)):
        in_java, in_ts = name in backend, name in mirror
        if in_java and not in_ts:
            if name not in SERVER_ONLY:
                problems.append(
                    f"{name}: Java DTO has no interface in api.ts. Add one, or add it to "
                    f"SERVER_ONLY with a reason if the client never sees it."
                )
            continue
        if in_ts and not in_java:
            if name not in CLIENT_ONLY:
                problems.append(
                    f"{name}: api.ts interface has no Java DTO record. Renamed? Fix the name, or "
                    f"add it to CLIENT_ONLY with a reason if it mirrors nothing."
                )
            continue
        checked += 1
        missing = backend[name] - mirror[name]
        extra = mirror[name] - backend[name]
        if missing:
            problems.append(f"{name}: missing from api.ts: {sorted(missing)}")
        if extra:
            problems.append(f"{name}: in api.ts but not in the Java record: {sorted(extra)}")

    if problems:
        print(f"api.ts has drifted from the core-api DTOs ({len(problems)} problem(s)):\n")
        for problem in problems:
            print(f"  - {problem}")
        print(f"\nChecked {checked} mirrored types.")
        return 1

    print(f"api.ts agrees with the core-api DTOs ({checked} mirrored types checked).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
