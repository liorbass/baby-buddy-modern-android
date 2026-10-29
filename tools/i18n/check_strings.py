#!/usr/bin/env python3
"""Checks translations against the English strings (app/src/main/res/values/strings.xml): same names, same kinds
(string / plurals), the same format arguments in each entry, escaped apostrophes and quotes, and an "other" form in
every plurals entry.

    python3 tools/i18n/check_strings.py                    # every values-*/strings.xml
    python3 tools/i18n/check_strings.py values-de/strings.xml

Exits 1 on any problem.
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parents[2] / "app/src/main/res"
# %1$s, %d, %02d, %.1f; %% is a literal percent sign
SPEC = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])")


def load(path: Path) -> dict[str, tuple[str, dict[str, str]]]:
    """name -> (kind, {quantity or "": raw text})"""
    out = {}
    for el in ET.parse(path).getroot():
        if el.tag == "string":
            out[el.get("name")] = ("string", {"": "".join(el.itertext())})
        elif el.tag == "plurals":
            out[el.get("name")] = ("plurals", {i.get("quantity"): "".join(i.itertext()) for i in el})
    return out


def args_of(text: str) -> set[tuple[int, str]]:
    """The format arguments as (position, conversion), so %2$02d matches %2$d."""
    found, n = set(), 0
    for m in SPEC.finditer(text):
        if m.group(2) == "%":
            continue
        n += 1
        found.add((int(m.group(1)) if m.group(1) else n, m.group(2)))
    return found


def check(path: Path, english: dict) -> list[str]:
    problems = []
    translated = load(path)
    for name in english.keys() - translated.keys():
        problems.append(f"missing: {name}")
    for name in translated.keys() - english.keys():
        problems.append(f"not in English: {name}")
    for name, (kind, forms) in translated.items():
        if name not in english:
            continue
        en_kind, en_forms = english[name]
        if kind != en_kind:
            problems.append(f"{name}: {kind}, English has {en_kind}")
            continue
        if kind == "plurals" and "other" not in forms:
            problems.append(f"{name}: no 'other' form")
        expected = set().union(*(args_of(t) for t in en_forms.values()))
        for quantity, text in forms.items():
            got = args_of(text)
            # A plural form may leave out the count ("une entrée"), never add arguments
            if got - expected or (kind == "string" and got != expected):
                problems.append(f"{name}{'/' + quantity if quantity else ''}: arguments {sorted(got)}, English {sorted(expected)}")
            if re.search(r"(?<!\\)['\"]", text) and not (text.startswith('"') and text.endswith('"')):
                problems.append(f"{name}{'/' + quantity if quantity else ''}: unescaped ' or \"")
            if not text.strip():
                problems.append(f"{name}{'/' + quantity if quantity else ''}: empty")
    return problems


def main() -> None:
    english = load(RES / "values/strings.xml")
    paths = [RES / a for a in sys.argv[1:]] if sys.argv[1:] else sorted(RES.glob("values-*/strings.xml"))
    failed = False
    for path in paths:
        problems = check(path, english)
        print(f"{path.parent.name}: {'OK' if not problems else f'{len(problems)} problems'}")
        for p in problems:
            print(f"  {p}")
        failed |= bool(problems)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
