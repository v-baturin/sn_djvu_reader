"""Embed the GPLv2 DjVu.js decoder in the offline reader HTML."""

import json
import sys
from pathlib import Path

project = Path(__file__).resolve().parents[1]
decoder = Path(sys.argv[1]) if len(sys.argv) > 1 else project / "vendor" / "djvu.js"
html = (project / "reader" / "reader.html").read_text(encoding="utf-8")
source = decoder.read_text(encoding="utf-8")
if "/* DJVU_DECODER */" not in html:
    raise SystemExit("Decoder placeholder missing")
if "</script" in source.lower():
    raise SystemExit("Decoder contains an unsafe closing script tag")
html = html.replace("/* DJVU_DECODER */", source)
target = project / "vendor" / "reader-html.json"
target.parent.mkdir(exist_ok=True)
target.write_text(json.dumps(html, ensure_ascii=False), encoding="utf-8")
print(f"Wrote {target} ({target.stat().st_size:,} bytes)")
