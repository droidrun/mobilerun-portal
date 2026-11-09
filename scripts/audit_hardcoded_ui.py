"""Read-only scan for visible hard-coded English UI outside strings.xml."""
from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]/"app"/"src"/"main"
attrs=re.compile(r'\b(?:android|app):(?:text|hint|contentDescription|title|label)\s*=\s*"([^"]+)"')
assign=re.compile(r'\b(?:setText|setTitle|setHint|setContentDescription)\s*\(\s*"([^"]+)"')
kotlin=re.compile(r'\b(?:text|hint|title|contentDescription)\s*=\s*"([^"]+)"')
for file in sorted(root.rglob("*")):
    if file.suffix not in (".kt",".xml"):
        continue
    for lineno,line in enumerate(file.read_text(encoding="utf-8",errors="replace").splitlines(),1):
        pattern=attrs if file.suffix==".xml" else None
        found=(attrs.findall(line) if pattern else assign.findall(line)+kotlin.findall(line))
        for text in found:
            if not re.search("[a-zA-Z]{3,}",text):
                continue
            if text.startswith(("@","?","http","com.","android.","xmlns","@string/")):
                continue
            if "://" in text or text.startswith(("ACTION_","UTF","POST","GET")):
                continue
            print(f"{file.relative_to(root)}:{lineno}: {text[:170]}")
