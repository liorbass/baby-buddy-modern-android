#!/usr/bin/env python3
"""Glossary for translating the app from the Baby Buddy server's own translation (locale/<lang>/LC_MESSAGES/django.po),
so the app and the web UI use the same words.

    python3 tools/i18n/server_glossary.py de > glossary-de.md
    python3 tools/i18n/server_glossary.py pt_BR --po path/to/django.po

Prints the app strings whose English matches a server string (with the server's translation), then the server's
short terms (up to five words) as a reference for the rest.
"""

import argparse
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

PO_URL = "https://raw.githubusercontent.com/babybuddy/babybuddy/master/locale/{lang}/LC_MESSAGES/django.po"
STRINGS = Path(__file__).resolve().parents[2] / "app/src/main/res/values/strings.xml"


def unquote(s: str) -> str:
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), s)


def parse_po(text: str) -> dict[str, str]:
    """msgid -> translation, leaving out untranslated and fuzzy entries (plurals: the singular form)."""
    # Entries are separated by blank lines; a "#, fuzzy" comment comes before its msgid
    entries, entry, key = [], {}, None
    for line in text.splitlines():
        if not line.strip():
            entries.append(entry)
            entry, key = {}, None
        elif line.startswith("#,") and "fuzzy" in line:
            entry["fuzzy"] = True
        elif m := re.match(r'^(msgctxt|msgid|msgid_plural|msgstr|msgstr\[\d\]) "(.*)"$', line):
            entry[m.group(1)] = m.group(2)
            key = m.group(1)
        elif (m := re.match(r'^"(.*)"$', line)) and key:
            entry[key] += m.group(1)
    entries.append(entry)
    out = {}
    for e in entries:
        msgid, msgstr = e.get("msgid"), e.get("msgstr", e.get("msgstr[0]"))
        if msgid and msgstr and not e.get("fuzzy"):
            out[unquote(msgid)] = unquote(msgstr)
    return out


def app_strings() -> dict[str, str]:
    out = {}
    for el in ET.parse(STRINGS).getroot():
        if el.tag == "string":
            out[el.get("name")] = "".join(el.itertext()).replace("\\'", "'").replace('\\"', '"')
    return out


def norm(s: str) -> str:
    return s.strip().rstrip(".:").lower()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lang", help="server locale folder: de, nl, es, he, pt_BR, zh_Hans, ...")
    parser.add_argument("--po", type=Path, help="a local django.po instead of downloading it")
    args = parser.parse_args()

    if args.po:
        text = args.po.read_text(encoding="utf-8")
    else:
        with urllib.request.urlopen(PO_URL.format(lang=args.lang), timeout=30) as response:
            text = response.read().decode("utf-8")
    server = parse_po(text)
    by_norm = {norm(k): v for k, v in server.items()}

    out = sys.stdout
    out.write(f"# Baby Buddy server glossary: {args.lang}\n\n{len(server)} translated server strings.\n\n")
    out.write("## App strings the server translates\n\n| name | English | server |\n|---|---|---|\n")
    for name, english in app_strings().items():
        if norm(english) in by_norm:
            out.write(f"| {name} | {english} | {by_norm[norm(english)]} |\n")
    out.write("\n## Server terms (up to five words)\n\n| English | server |\n|---|---|\n")
    for english, translated in sorted(server.items(), key=lambda kv: kv[0].lower()):
        if len(english.split()) <= 5 and "\n" not in english and "%" not in english:
            out.write(f"| {english} | {translated} |\n")


if __name__ == "__main__":
    main()
