"""Prints 'x y' for the centre of an element whose text equals (or starts with) the given text.

Usage: find_text.py ui.xml TEXT [INDEX]
Exact matches win over prefix matches. INDEX picks among several matches (0 = first).
"""
import re
import sys
import xml.etree.ElementTree as ET

path, wanted = sys.argv[1], sys.argv[2]
index = int(sys.argv[3]) if len(sys.argv) > 3 else 0
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)

exact, prefix = [], []
for node in root.iter("node"):
    text = (node.get("text") or "").strip()
    desc = (node.get("content-desc") or "").strip()
    bounds = node.get("bounds") or ""
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    centre = f"{(x1 + x2) // 2} {(y1 + y2) // 2}"
    for value in (text, desc):
        if value == wanted:
            exact.append(centre)
        elif value.startswith(wanted):
            prefix.append(centre)

hits = exact + prefix
if len(hits) > index:
    print(hits[index])
