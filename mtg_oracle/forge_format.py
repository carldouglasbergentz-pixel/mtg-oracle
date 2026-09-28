"""Forge's file and output formats, as pure functions.

Everything here takes plain values and returns plain values: deck rows in,
`.dck` text out; Forge's sim output in, per-game results out. The files and
the subprocess are `forge_client`'s job, the database `forge_data`'s. That
split is what lets every parsing rule below be tested against real Forge
output without Forge installed.

This module imports nothing from the project (tests/test_layering.py).
"""
from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field
from typing import Iterable, Optional

# --- card names ----------------------------------------------------------

def forge_card_name(name: str) -> str:
    """The name Forge knows a card by: its front face.

    Forge's card scripts name every multi-face card after its first face,
    split cards included — `f/fire_ice.txt` says `Name:Fire` — and a `.dck`
    line naming `Fire // Ice` is a card Forge doesn't have.
    """
    return name.split(" // ", 1)[0].strip()


# --- .dck files ----------------------------------------------------------

SECTIONS = ("Commander", "Main", "Sideboard")

# The ownership marker. `Description=` is the metadata key Forge's own decks
# use for free text (9,321 of the 13,994 .dck files under res/ carry it), so
# Forge shows it and keeps it. The digest covers the card sections, so a file
# edited in Forge's deck editor after export reads as not-ours-any-more.
MARKER_PREFIX = "mtg-oracle export"
_MARKER_RE = re.compile(
    r"\[mtg-oracle export deck=(\d+) sha1=([0-9a-f]{40})\]")


def _section_of(row: dict) -> str:
    if row.get("is_commander"):
        return "Commander"
    if row.get("is_sideboard"):
        return "Sideboard"
    return "Main"


def dck_sections(rows: Iterable[dict]) -> dict[str, list[tuple[int, str]]]:
    """Deck rows -> {section: [(quantity, forge name), ...]}, merged and sorted.

    Rows are `decks.get_deck()["cards"]` shaped: `card_name`, `quantity`,
    `is_commander`, `is_sideboard`. Two rows that map to one Forge name in
    the same section (a substitution onto a basic already in the deck) are
    one line with the summed quantity.
    """
    merged: dict[str, dict[str, int]] = {s: {} for s in SECTIONS}
    for row in rows:
        section = _section_of(row)
        name = forge_card_name(row["card_name"])
        merged[section][name] = merged[section].get(name, 0) + int(row["quantity"])
    return {
        section: sorted(((qty, name) for name, qty in cards.items()),
                        key=lambda item: item[1].casefold())
        for section, cards in merged.items() if cards
    }


def _single_line(text: str) -> str:
    """Metadata values are one line; a newline would start a new key."""
    return re.sub(r"[\r\n]+", " ", text).strip()


def _body(sections: dict[str, list[tuple[int, str]]]) -> str:
    lines: list[str] = []
    for section in SECTIONS:
        cards = sections.get(section)
        if cards:
            lines.append(f"[{section}]")
            lines.extend(f"{qty} {name}" for qty, name in cards)
    return "\n".join(lines) + "\n"


def body_digest(body: str) -> str:
    """sha1 of the card sections, newline-normalised."""
    normalised = "\n".join(line.rstrip() for line in body.strip().splitlines())
    return hashlib.sha1(normalised.encode("utf-8")).hexdigest()


def render_dck(deck_name: str, deck_id: int,
               sections: dict[str, list[tuple[int, str]]]) -> str:
    """A complete `.dck` file, ownership marker included."""
    body = _body(sections)
    marker = (f"[{MARKER_PREFIX} deck={deck_id} sha1={body_digest(body)}] "
              f"Edit this deck in mtg-oracle; a copy edited here is left alone.")
    return (f"[metadata]\nName={_single_line(deck_name)}\n"
            f"Description={marker}\n{body}")


@dataclass(frozen=True)
class Ownership:
    """What a `.dck` on disk says about who wrote it."""
    ours: bool                     # carries our marker
    deck_id: Optional[int] = None  # the deck it was exported from
    edited: bool = False           # ours, but the cards changed since


def read_ownership(text: str) -> Ownership:
    """Whether `text` is a `.dck` this project wrote, and left untouched."""
    match = _MARKER_RE.search(text)
    if not match:
        return Ownership(ours=False)
    # Everything from the first section header on is the body we hashed.
    start = re.search(r"^\[(Commander|Main|Sideboard)\]", text, re.MULTILINE)
    body = text[start.start():] if start else ""
    return Ownership(ours=True, deck_id=int(match.group(1)),
                     edited=body_digest(body) != match.group(2))


# --- AI substitutions -----------------------------------------------------

def apply_substitutions(rows: Iterable[dict],
                        substitutions: dict[str, str]) -> list[dict]:
    """Deck rows with each substituted card renamed; everything else as is.

    `substitutions` maps card name -> substitute, matched case-insensitively.
    Merging a substitute into a card already in the same section is
    `dck_sections`' job, so the rows here stay one-to-one with the input.
    """
    folded = {card.casefold(): sub for card, sub in substitutions.items()}
    out = []
    for row in rows:
        sub = folded.get(row["card_name"].casefold())
        out.append({**row, "card_name": sub} if sub else dict(row))
    return out


# --- filenames ------------------------------------------------------------

# Windows' forbidden filename characters, plus cmd.exe's metacharacters
# (& ^ % !): the filename is also a command-line argument, and if the java
# launcher were ever a .bat/.cmd shim, cmd.exe would parse `x&calc&y`.
_FORBIDDEN_CHARS = re.compile(r'[\\/:*?"<>|&^%!\x00-\x1f]')
_RESERVED_NAMES = {"CON", "PRN", "AUX", "NUL",
                   *(f"COM{i}" for i in range(1, 10)),
                   *(f"LPT{i}" for i in range(1, 10))}
MAX_STEM = 120


def safe_filename(name: str, suffix: str = ".dck") -> str:
    """A deck name as a single, harmless Windows filename.

    Deck names are user text and they become paths. So: no separators
    (`../x` cannot climb out), no characters Windows rejects, no trailing
    dots or spaces (Windows strips them, and two names would collide), no
    reserved device names (`CON.dck` opens the console), no leading `-` (the
    name is also a Forge command-line argument, and `-n.dck` reads as a
    flag), and a length cap.
    """
    stem = _FORBIDDEN_CHARS.sub("_", name).strip()
    stem = stem.rstrip(". ")
    if stem.startswith("-"):
        stem = "_" + stem[1:]
    if stem.split(".", 1)[0].upper() in _RESERVED_NAMES:
        stem = "_" + stem
    stem = stem[:MAX_STEM].rstrip(". ") or "_"
    return stem + suffix


# --- sim output -----------------------------------------------------------

@dataclass(frozen=True)
class SimGame:
    game_no: int
    winner: Optional[int]   # 1 or 2 (the -d order), None for a draw
    turns: Optional[int]
    duration_ms: int
    clock_draw: bool = False  # stopped by Forge's clock, not decided


@dataclass(frozen=True)
class SimMatch:
    games: list[SimGame] = field(default_factory=list)

    @property
    def wins(self) -> dict[int, int]:
        return {side: sum(1 for g in self.games if g.winner == side)
                for side in (1, 2)}

    @property
    def draws(self) -> int:
        return sum(1 for g in self.games if g.winner is None)


_RESULT_WON = re.compile(
    r"^Game Result: Game (\d+) ended in (\d+) ms\. Ai\((\d+)\)-.* has won!\s*$")
_RESULT_DRAW = re.compile(
    r"^Game Result: Game (\d+) ended in a Draw! Took (\d+) ms\.\s*$")
_OUTCOME_TURN = re.compile(r"^Game Outcome: Turn (\d+)\s*$")
_CLOCK_STOP = "Stopping slow match as draw"


def parse_sim_output(stdout: str) -> SimMatch:
    """Per-game results from Forge's `sim` stdout, with or without `-q`.

    The winner is read from the `Ai(N)-` index, never from the name: the
    name is the deck's `Name=` and can contain anything, while N is the
    position in `-d`. Pass stdout only — Forge writes stack traces to
    stderr, and merged streams split a `Game Result:` line mid-way.

    A game Forge's clock stopped is recorded as a draw even though Forge
    then prints a winner: `SimulateMatch` logs "Stopping slow match as
    draw" and goes on to report player 1 as having won (seen on 2.0.14 with
    `-c 1`), which would hand every slow game to deck A.
    """
    games: list[SimGame] = []
    turns: Optional[int] = None
    clock_stopped = False
    for line in stdout.splitlines():
        if _CLOCK_STOP in line:
            clock_stopped = True
            continue
        m = _OUTCOME_TURN.match(line)
        if m:
            turns = int(m.group(1))
            continue
        won = _RESULT_WON.match(line)
        draw = _RESULT_DRAW.match(line)
        if won or draw:
            if won:
                game_no, ms = int(won.group(1)), int(won.group(2))
                winner = None if clock_stopped else int(won.group(3))
            else:
                game_no, ms, winner = int(draw.group(1)), int(draw.group(2)), None
            games.append(SimGame(game_no=game_no, winner=winner, turns=turns,
                                 duration_ms=ms, clock_draw=clock_stopped))
            turns, clock_stopped = None, False
    return SimMatch(games=games)


_ERROR_LINES = re.compile(
    r"^(Could not load deck.*|No deck found in.*|Unknown AI profile.*|"
    r"Illegal parameter usage.*|Exception.*|.*Error:.*)$")


def sim_errors(output: str) -> list[str]:
    """Lines in Forge's output that explain why a sim produced no games."""
    return [line.strip() for line in output.splitlines()
            if _ERROR_LINES.match(line.strip())]


# --- card scripts ---------------------------------------------------------

_SCRIPT_NAME = re.compile(rb"^Name:(.+?)\r?$", re.MULTILINE)
_SCRIPT_AI = re.compile(rb"^AI:RemoveDeck:(\w+)", re.MULTILINE)


def parse_card_script(script: bytes) -> Optional[tuple[str, Optional[str]]]:
    """(front-face name, AI:RemoveDeck flag or None) from one card script.

    The first `Name:` is the front face; faces after `ALTERNATE` repeat the
    key. `All` means Forge's AI cannot play the card at all, `Random` that
    it plays it only situationally, `NonCommander` that it drops it outside
    Commander.
    """
    name = _SCRIPT_NAME.search(script)
    if not name:
        return None
    flag = _SCRIPT_AI.search(script)
    return (name.group(1).decode("utf-8", "replace").strip(),
            flag.group(1).decode("ascii", "replace") if flag else None)
