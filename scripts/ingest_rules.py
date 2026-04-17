"""Ingest the WotC Comprehensive Rules from a .docx file.

Recognized rule-number patterns (in order of specificity):
  - 100.1a  (lettered sub-rule)
  - 100.1.  (sub-rule)
  - 100.    (rule)

Top-level section headers ("1. Game Concepts", "2. Parts of a Card", ...)
are tracked as `section_title` and attached to every rule beneath them.
"""
import re
import sqlite3
import sys
from pathlib import Path
from typing import Optional

try:
    from docx import Document
except ImportError:
    print("ERR python-docx not installed. Run: pip install python-docx")
    sys.exit(1)

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"

# Order matters — most specific first
RULE_PATTERNS = [
    re.compile(r"^(\d+\.\d+[a-z])\s+(.*)"),   # 100.1a
    re.compile(r"^(\d+\.\d+)\.\s+(.*)"),      # 100.1.
    re.compile(r"^(\d+)\.\s+(.*)"),           # 100.
]
SECTION_PATTERN = re.compile(r"^(\d)\.\s+([A-Z][A-Za-z ]+?)\s*$")


def compute_parent(rule_number: str) -> Optional[str]:
    if re.match(r"^\d+\.\d+[a-z]$", rule_number):
        return rule_number[:-1]              # 100.1a -> 100.1
    if re.match(r"^\d+\.\d+$", rule_number):
        return rule_number.split(".")[0]     # 100.1  -> 100
    return None


def parse_rules(docx_path: Path):
    doc = Document(docx_path)
    rules = []
    current_section = None

    for para in doc.paragraphs:
        text = para.text.strip()
        if not text:
            continue

        # Top-level section header (single digit followed by capitalized title)
        section_match = SECTION_PATTERN.match(text)
        if section_match:
            current_section = section_match.group(2).strip()
            continue

        for pat in RULE_PATTERNS:
            m = pat.match(text)
            if m:
                rule_number = m.group(1)
                rule_text = m.group(2).strip()
                rules.append((
                    rule_number,
                    compute_parent(rule_number),
                    current_section,
                    rule_text,
                ))
                break

    return rules


def main(docx_path: str) -> None:
    path = Path(docx_path)
    if not path.exists():
        print(f"ERR File not found: {path}")
        sys.exit(1)

    print(f"-> Parsing {path.name}...")
    rules = parse_rules(path)

    if not rules:
        print("ERR No rules parsed. Check that the docx has the expected format.")
        sys.exit(1)

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    cur.execute("DELETE FROM rules")
    cur.executemany(
        "INSERT OR REPLACE INTO rules (rule_number, parent_rule, section_title, text) "
        "VALUES (?, ?, ?, ?)",
        rules,
    )
    conn.commit()
    conn.close()

    print(f"OK Ingested {len(rules):,} rules")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python ingest_rules.py <path_to_MagicCompRules.docx>")
        sys.exit(1)
    main(sys.argv[1])
