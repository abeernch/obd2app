"""Prints 'x y' for the centre of the text field on the same row as a label: find_field.py ui.xml LABEL

The fields in the app's forms sit to the right of their label and carry no name of their own, so the
field is found by its row: the first label (text equal to LABEL) that has an EditText level with it.
"""
import re
import sys
import xml.etree.ElementTree as ET

path, wanted = sys.argv[1], sys.argv[2]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)


def box(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
    return tuple(map(int, m.groups())) if m else None


labels, fields = [], []
for node in root.iter("node"):
    b = box(node)
    if not b:
        continue
    if (node.get("class") or "").endswith("EditText"):
        fields.append(b)
    elif (node.get("text") or "").strip() == wanted:
        labels.append(b)

for lx1, ly1, lx2, ly2 in labels:
    mid = (ly1 + ly2) // 2
    for fx1, fy1, fx2, fy2 in fields:
        if fy1 <= mid <= fy2 and fx1 >= lx2:
            print(f"{(fx1 + fx2) // 2} {(fy1 + fy2) // 2}")
            sys.exit(0)
