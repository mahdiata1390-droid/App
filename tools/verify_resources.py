#!/usr/bin/env python3
"""Static sanity checks for the Android app (no SDK required).

Validates every XML file, then checks that every R.id / R.string / R.drawable / R.raw
reference in Kotlin sources resolves to a real resource, in both locales.
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main"
errors = []

# 1. XML well-formedness
xml_files = list(MAIN.rglob("*.xml"))
for path in xml_files:
    try:
        ET.parse(path)
    except ET.ParseError as exc:
        errors.append(f"XML parse error in {path}: {exc}")

# 2. Resource names
ids = set()
for path in (MAIN / "res/layout").glob("*.xml"):
    text = path.read_text()
    ids.update(re.findall(r'@\+id/([A-Za-z0-9_]+)', text))

def string_names(folder):
    path = MAIN / f"res/{folder}/strings.xml"
    tree = ET.parse(path)
    return {el.get("name") for el in tree.getroot().findall("string")}

strings_en = string_names("values")
strings_fa = string_names("values-fa")

drawables = {p.stem for p in (MAIN / "res/drawable").glob("*")}
drawables |= {p.stem for p in (MAIN / "res/drawable-nodpi").glob("*")}
raws = {p.stem for p in (MAIN / "res/raw").glob("*")}

# 3. Kotlin references
for path in (MAIN / "java").rglob("*.kt"):
    text = path.read_text()
    for name in set(re.findall(r"R\.id\.([A-Za-z0-9_]+)", text)):
        if name not in ids:
            errors.append(f"{path.name}: missing layout id R.id.{name}")
    for name in set(re.findall(r"R\.string\.([A-Za-z0-9_]+)", text)):
        if name not in strings_en:
            errors.append(f"{path.name}: missing string (values) {name}")
        if name not in strings_fa:
            errors.append(f"{path.name}: missing string (values-fa) {name}")
    for name in set(re.findall(r"R\.drawable\.([A-Za-z0-9_]+)", text)):
        if name not in drawables:
            errors.append(f"{path.name}: missing drawable {name}")
    for name in set(re.findall(r"R\.raw\.([A-Za-z0-9_]+)", text)):
        if name not in raws:
            errors.append(f"{path.name}: missing raw {name}")

# 4. Kotlin delimiter balance (strings and comments roughly stripped)
def strip_code(text):
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j == -1 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j == -1 else j + 2
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            i = n if j == -1 else j + 3
        elif ch == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
        elif ch == "'":
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == "'":
                    i += 1
                    break
                i += 1
        else:
            out.append(ch)
            i += 1
    return "".join(out)

for path in (MAIN / "java").rglob("*.kt"):
    code = strip_code(path.read_text())
    for opener, closer, label in (("{", "}", "braces"), ("(", ")", "parens"), ("[", "]", "brackets")):
        if code.count(opener) != code.count(closer):
            errors.append(
                f"{path.name}: unbalanced {label} "
                f"({code.count(opener)} vs {code.count(closer)})"
            )

if errors:
    print("FAILED")
    for err in errors:
        print(" -", err)
    sys.exit(1)

print(f"OK: {len(xml_files)} XML files valid, "
      f"{len(ids)} ids, {len(strings_en)} strings, "
      f"{len(drawables)} drawables checked")
