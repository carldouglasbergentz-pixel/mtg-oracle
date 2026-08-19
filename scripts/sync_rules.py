"""Sync the WotC Comprehensive Rules from magic.wizards.com.

Scrapes the public rules page to discover the current `.txt` release URL,
downloads it, and re-ingests the `rules` table. The release date (embedded
in the filename, e.g. `MagicCompRules 20260227.txt`) is used as the
upstream `updated_at` in `sync_state`, so re-runs skip when unchanged.

Run:
    python scripts/sync_rules.py            # skip if unchanged
    python scripts/sync_rules.py --force    # re-ingest regardless
"""
import argparse
import datetime as dt
import re
import sqlite3
import sys
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Optional

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"
RAW_DIR = Path(__file__).parent.parent / "data" / "raw"
RULES_PAGE = "https://magic.wizards.com/en/rules"
# Browsers-y UA; the default Python UA gets a stripped page with no links.
BROWSER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

# Matches "https://media.wizards.com/<year>/downloads/MagicCompRules YYYYMMDD.txt"
# in both regular HTML and Nuxt-escaped JSON (\u002F for slashes).
CR_URL_PATTERN = re.compile(
    r"https?[:\\u003A][/\\u002F]+media\.wizards\.com"
    r"[/\\u002F]+\d{4}[/\\u002F]+downloads[/\\u002F]+"
    r"MagicCompRules[ %20]+(\d{8})\.txt",
    re.IGNORECASE,
)

# Order matters — most specific first. Mirrors scripts/ingest_rules.py.
RULE_PATTERNS = [
    re.compile(r"^(\d+\.\d+[a-z])\s+(.*)"),   # 100.1a
    re.compile(r"^(\d+\.\d+)\.\s+(.*)"),      # 100.1.
    re.compile(r"^(\d+)\.\s+(.*)"),           # 100.
]
SECTION_PATTERN = re.compile(r"^(\d)\.\s+([A-Z][A-Za-z ,'\-]+?)\s*$")


def _http_get(url: str, accept: str = "text/html") -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": BROWSER_UA, "Accept": accept})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def discover_cr_url() -> tuple[str, str]:
    """Return (download_url, release_date_YYYYMMDD) for the current CR .txt."""
    print(f"-> GET {RULES_PAGE}")
    html = _http_get(RULES_PAGE).decode("utf-8", errors="replace")

    candidates = []
    for m in CR_URL_PATTERN.finditer(html):
        raw = m.group(0)
        date = m.group(1)
        # Normalize Nuxt-escaped slashes and encoded spaces.
        clean = raw.replace("\\u002F", "/").replace("\\u003A", ":").replace(" ", "%20")
        candidates.append((clean, date))

    if not candidates:
        raise RuntimeError(
            "Could not locate MagicCompRules .txt URL on the rules page. "
            "The page layout may have changed."
        )

    # Pick the highest date if multiple (e.g. prerelease + live).
    candidates.sort(key=lambda c: c[1], reverse=True)
    url, date = candidates[0]
    print(f"OK Found CR release {date}: {url}")
    return url, date


def parse_rules_text(text: str) -> list[tuple[str, Optional[str], Optional[str], str]]:
    """Parse a plain-text Comprehensive Rules dump into (number, parent, section, text) tuples.

    The file begins with a table of contents (lines like `100. General` without
    rule body), then the actual rules. TOC entries match the top-level rule
    pattern but carry only a short title — when we later encounter the real
    `100. General` rule, it simply overwrites the TOC entry via the PK.
    Glossary/credits sections at the end do not match any pattern and are skipped.
    """
    rules: list[tuple[str, Optional[str], Optional[str], str]] = []
    current_section: Optional[str] = None

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue

        section_match = SECTION_PATTERN.match(line)
        if section_match:
            current_section = section_match.group(2).strip()
            continue

        for pat in RULE_PATTERNS:
            m = pat.match(line)
            if m:
                rule_number = m.group(1)
                rule_text = m.group(2).strip()
                rules.append((rule_number, _compute_parent(rule_number), current_section, rule_text))
                break

    return rules


def _compute_parent(rule_number: str) -> Optional[str]:
    if re.match(r"^\d+\.\d+[a-z]$", rule_number):
        return rule_number[:-1]
    if re.match(r"^\d+\.\d+$", rule_number):
        return rule_number.split(".")[0]
    return None


def _get_sync_state(cur: sqlite3.Cursor, source: str) -> Optional[str]:
    cur.execute("SELECT updated_at FROM sync_state WHERE source = ?", (source,))
    row = cur.fetchone()
    return row[0] if row else None


def _set_sync_state(cur: sqlite3.Cursor, source: str, updated_at: str, row_count: int) -> None:
    now = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    cur.execute(
        """
        INSERT INTO sync_state (source, updated_at, last_sync, row_count)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(source) DO UPDATE SET
            updated_at = excluded.updated_at,
            last_sync = excluded.last_sync,
            row_count = excluded.row_count
        """,
        (source, updated_at, now, row_count),
    )


def sync(force: bool = False) -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}. Run init_db.py first.")
        sys.exit(1)

    url, release_date = discover_cr_url()
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()

    local_updated = _get_sync_state(cur, "wizards_cr")
    if local_updated == release_date and not force:
        print(f"-- rules: up to date (release {release_date}), skipping")
        conn.close()
        return

    RAW_DIR.mkdir(parents=True, exist_ok=True)
    target = RAW_DIR / "MagicCompRules.txt"
    print(f"-> Downloading {url}")
    data = _http_get(url, accept="text/plain")
    target.write_bytes(data)
    print(f"OK Saved {target} ({len(data) / 1_000_000:.2f} MB)")

    # Wizards publishes as UTF-8 with BOM.
    text = data.decode("utf-8-sig", errors="replace")
    rules = parse_rules_text(text)

    if not rules:
        print("ERR Parser found no rules — aborting without touching the DB.")
        conn.close()
        sys.exit(1)

    cur.execute("DELETE FROM rules")
    cur.executemany(
        "INSERT OR REPLACE INTO rules (rule_number, parent_rule, section_title, text) "
        "VALUES (?, ?, ?, ?)",
        rules,
    )
    # The parsed list is larger than the table: table-of-contents lines match
    # the same patterns as real rules and are overwritten by the real body
    # later in the file (same PK). Report what actually landed.
    stored = cur.execute("SELECT COUNT(*) FROM rules").fetchone()[0]
    _set_sync_state(cur, "wizards_cr", release_date, stored)
    conn.commit()
    conn.close()
    print(f"OK Ingested {stored:,} rules (release {release_date}; "
          f"{len(rules) - stored:,} table-of-contents duplicates collapsed)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="Re-ingest even if release is unchanged")
    args = parser.parse_args()
    sync(force=args.force)
