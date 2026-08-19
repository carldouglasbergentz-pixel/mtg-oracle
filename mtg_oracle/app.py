"""MTG Oracle — Textual TUI app.

Runs in any terminal on Windows / Linux / Mac. Imports
`mtg_oracle.queries` directly; no HTTP, no network.

Launch:
    python scripts/mtg_app.py
"""
from __future__ import annotations

import json
import os
import subprocess
import sqlite3
import sys
from pathlib import Path
from typing import Callable, Optional

from rich.style import Style
from rich.text import Text
from textual import work
from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal
from textual.css.query import NoMatches
from textual.suggester import Suggester
from textual.widgets import Footer, Header, Input, RichLog, Static

from mtg_oracle import analytics as a
from mtg_oracle import queries as q
from mtg_oracle.queries import LEGALITY_FORMATS
from mtg_oracle import renderer as r
from mtg_oracle import scryfall_search as ss
from mtg_oracle import decks as d
from mtg_oracle.deck_parser import parse_deckstring


# Persistent user preferences (theme so far). Lives next to the DB —
# already gitignored as part of `data/`. Single JSON dict so adding more
# settings later is a one-key addition.
CONFIG_PATH = Path(__file__).parent.parent / "data" / "config.json"


def _load_config() -> dict:
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            data = json.load(f)
            return data if isinstance(data, dict) else {}
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}


def _save_config(config: dict) -> None:
    try:
        CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(config, f, indent=2)
    except OSError:
        # Persistence is best-effort; never crash the app over a config write.
        pass


# Width of the left navigation pane (matches the CSS rule). Wide enough that
# the live deck view doesn't truncate names aggressively — and card names got
# longer once points markers were appended to them.
NAV_WIDTH = 52

# Columns actually available to the renderer inside #nav: the CSS width less
# the 1-char border and 1-char padding on each side. Passing NAV_WIDTH - 2
# subtracted the padding but not the border, so the widest rows overflowed
# the pane by two characters.
NAV_CONTENT_WIDTH = NAV_WIDTH - 4


COMMANDS = [
    # Card / rules / combo lookup
    "card", "ruling", "combo", "combos", "combo-info",
    "rule", "search-rules", "search",
    "next", "prev", "page",
    "correction",
    # Terminal-style navigation
    "cd", "pwd", "ls", "mkdir", "rmdir",
    "add", "remove", "show", "rename", "move",
    "commander", "points", "import", "paste",
    # Maintenance
    "sync",
    # Misc
    "copy", "help", "clear", "quit",
]


def _read_clipboard() -> str:
    """Best-effort cross-platform clipboard read.

    Returns the clipboard contents as a string. Raises RuntimeError if no
    supported tool is available on the current platform.
    """
    if sys.platform.startswith("win"):
        # Get-Clipboard is built into PowerShell on every modern Windows.
        out = subprocess.run(
            ["powershell", "-NoProfile", "-Command", "Get-Clipboard"],
            capture_output=True, text=True, timeout=5,
        )
        if out.returncode == 0:
            return out.stdout
        raise RuntimeError(out.stderr or "Get-Clipboard failed")
    if sys.platform == "darwin":
        out = subprocess.run(["pbpaste"], capture_output=True, text=True, timeout=5)
        if out.returncode == 0:
            return out.stdout
        raise RuntimeError("pbpaste failed")
    # Linux / BSD: try xclip then wl-paste then xsel.
    for cmd in (
        ["xclip", "-selection", "clipboard", "-o"],
        ["wl-paste"],
        ["xsel", "--clipboard", "--output"],
    ):
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
        except FileNotFoundError:
            continue
        if out.returncode == 0:
            return out.stdout
    raise RuntimeError(
        "no clipboard tool found — install xclip, wl-clipboard, or xsel"
    )


DECK_HELP = """\
Decks work like a terminal filesystem:
    /                       root — your folders + unsorted decks
    /<folder>/              decks inside a folder
    /<folder>/<deck>/       cards inside a deck

Commands change meaning by where you are. The status line shows your path.

NAVIGATION (works anywhere)
  pwd                       print current path
  ls                        list contents of current location
  ls all                    flat list of every deck across all folders,
                            each row prefixed with /folder/deck-name
  cd <name>                 enter folder or deck (auto-detects)
  cd <deck>                 from root, jumps directly into a deck if its
                            name is unique across all folders
  cd <folder>/<deck>        explicit path (disambiguates ambiguous names)
  cd ..                     up one level
  cd /                      go to root

UNIFIED `add` / `remove` (context-aware)
  At root:    folders are managed with `mkdir` / `rmdir` (see below).
  In folder:  `add <deck>` creates a deck here; `remove <deck>` deletes it.
  In deck:    `add <card> [<qty>]` adds; `remove <card> [<qty>]` removes.

AT ROOT (`/`)
  mkdir <name>              create folder
  rmdir <name>              delete an empty folder
  show <deck>               render a deck without entering it

INSIDE A FOLDER (`/<folder>/`)
  add <deck>                create deck in this folder
  remove <deck>             delete deck in this folder
  rename <old>; <new>       rename deck
  move <deck>; <folder>     move deck (empty folder = unsorted)
  show <deck>               render a deck without entering

INSIDE A DECK (`/<folder>/<deck>/`)
  ls                        one-line summary (full contents are in the
                            left panel, live-updated as you edit)
  show                      render the full deck to the right pane
                            (useful if you want to scroll / copy it out)
  add <card> [<qty>]        add card (qty defaults to 1)
  add --force <card>        bypass commander-CI and singleton checks
  remove <card> [<qty>]     remove qty copies; omit qty to remove all
  commander <card>          promote a card to commander (adds it if
                            missing, flips is_commander on existing
                            row otherwise; multiple commanders allowed
                            for Partner / Background / Friends Forever)
  commander --unset <card>  demote a commander back to the main deck
  commander --force <card>  promote past the legality check (some cards are
                            legal in the deck but banned as commander)
  points                    points spent / budget, in formats that have a
                            points list (Canadian Highlander). Pointed
                            cards are marked `<3p>` in `show`.
  combos                    list Spellbook combos fully contained here
  paste                     read deckstring from system clipboard and
                            append to current deck (Windows / macOS / Linux)
  import <filepath>         load a deckstring from a text file
                            (appended to the current deck)

Card-name resolution is tolerant of `/` vs ` // ` and front-face-only DFC
names: `add fire/ice` resolves to the canonical `Fire // Ice`.
"""


SEARCH_HELP = f"""\
Scryfall-style search. AND is implicit (space-separated). OR, NOT, and
parentheses are supported. `-` is a shortcut for NOT.

Operators:
  o:TEXT      oracle text contains TEXT  (quote for spaces: o:"draw a card")
  t:TEXT      type line contains TEXT    (t:creature, t:planeswalker)
  n:TEXT      name contains TEXT         (substring)
  n=TEXT      name is exactly TEXT       (n:"Lightning Bolt" also matches
                                          'Emeritus of Conflict // Lightning Bolt')
  kw:KW       card has keyword ability   (flying, trample, prowess, ward, ...)
  c:COLORS    colors subset-contains     (c:u any-blue; c:wu contains W and U)
  c=COLORS    colors equal exactly       (c=wu exactly W+U, not tri-colored)
  ci<=COLORS  color identity fits        (commander legality; ci:, ci=, ci>= too)
  mv:N        mana value comparisons     (also mv=, mv<, mv>, mv<=, mv>=, mv!=)
  pow:S       power                      (string match on :/=, numeric for <, >, etc.)
  tou:S       toughness                  (same shape as pow)
  r:RARITY    rarity                     (common | uncommon | rare | mythic | bonus | special)
  layout:X    card layout                (normal | transform | modal_dfc | split | flip | ...)

Format legality:
  f:FORMAT    legal (or restricted) in FORMAT   (aliases: format:, legal:)
  banned:F    on that format's ban list
  restricted:F  restricted in that format. Means "one copy only" in
              Vintage / Old School, but "may not be your commander" in
              Duel Commander (`duel`) and Tiny Leaders (`tlr`).
  game:X      paper | arena | mtgo — `game:paper` drops Arena-only
              Alchemy rebalances (the `A-` cards)
  is:reserved on the Reserved List

  Formats: {", ".join(sorted(LEGALITY_FORMATS))}
  Spaces and hyphens are ignored, so f:"competitive brawl" works.
  Aliases: edh, pdh, duelcommander, pennydreadful, cbrawl.

Sorting:
  order:asc_FIELD / order:desc_FIELD  (alias sort:) — direction is required.
  Fields: mv, name, power, toughness, rarity, color, ci, edhrec.
  `order:asc_edhrec` is most-played-first. NULLs always sort last.

Colors can be letters (`u`, `uw`), words (`blue`, `white`, `blue white`), or
braced (`{{W}}{{U}}`). Bare words and quoted strings default to oracle text:
    "enters the battlefield"     <=>  o:"enters the battlefield"

Inside a deck, `search` is automatically restricted to what that deck can
play — the commander's color identity and the deck's format. The active
filters are shown above the results; `cd ..` searches the full pool.

Examples:
    o:"enters the battlefield" t:creature c:u mv<=3
    kw:flying (c:w or c:u) -t:artifact
    f:competitivebrawl ci<=UR t:instant order:asc_edhrec
    f:commander game:paper t:artifact mv<=2 order:asc_edhrec
    banned:commander
    c=wu t:instant
    pow>=4 t:creature r:mythic
    (kw:flying or kw:trample) c:g mv<=3
"""


HELP_TEXT = """\
MTG Oracle - local knowledge base

LOOKUP
  card <name>                         full card profile + tags + rulings + combos
  card <N>                            expand the N-th row of the last search
  ruling <name>                       rulings for a card
  rule <number>                       rule text + children (e.g. '605.1a')
  search-rules <text>                 search rule bodies
  correction [<card-or-topic>]        list relevant feedback-loop corrections

COMBOS
  combo <card>                        combos featuring a card (auto-expands if 1 match)
  combos <card1>; <card2>[; ...]      combos containing ALL named cards (auto-expand on 1)
  combos                              (inside a deck) combos fully contained in it
  combo-info <id-or-number>           full combo detail; <N> refers to the last list

SEARCH
  search <query>                      Scryfall-style card search
  next / prev / page <N>              navigate search results

DECKS                                 (terminal-style: cd / ls / pwd / add / remove ...)
  cd <name>  ls  pwd                  navigate folders and decks
  add / remove                        meaning follows your location — see `help decks`

MAINTENANCE
  sync [force]                        refresh data from Scryfall / Wizards / Spellbook
  copy [last|all|nav]                 copy pane content to clipboard
                                      (last = output since last command; all = full
                                      right pane; nav = left pane)
  clear                               clear the output pane
  quit                                exit

MORE HELP
  help decks                          the deck / folder filesystem model, in full
  help search                         Scryfall-style search syntax and examples

Mouse:
  Clickable, in both panes — they underline when you hover:
    a folder or deck in the left tree   -> cd into it
    a card name anywhere               -> its full profile, right pane
    a combo's [ N ] row number         -> expands that combo
  Everything is still reachable by typing; the mouse is a shortcut, not a
  second interface. Shift+drag still selects text.

Typing:
  Autofill suggestions appear as gray text after your command (prefix match).
  Tab or Right Arrow accepts the suggestion. Inside a deck, `remove` completes
  from the cards actually in that deck.
  Up / Down  cycle through previously submitted commands (shell-style).

Keys:
  :            focus the command input
  Enter        run the command
  Up / Down    previous / next command in history
  Esc          unfocus
  Ctrl+L       clear
  Ctrl+P       command palette (e.g. change theme — remembered next launch)
  Ctrl+Q       quit
  Shift+drag   bypass mouse capture to select text (then Ctrl+Shift+C to copy)
"""


# --- suggester --------------------------------------------------------


class MtgSuggester(Suggester):
    """Command-aware autofill.

    Inspects the partial input to decide which list to suggest from:
      - no command yet          -> COMMANDS list
      - `card|ruling|combo|correction <X>`  -> card names
      - `combos ...; <X>`       -> card names (complete the last segment after `;`)
      - `rule <X>`              -> rule numbers
      - `remove <X>` in a deck  -> only the cards actually in that deck
      - `commander <X>` in deck -> deck cards first, then all card names
    """

    def __init__(
        self,
        card_names: list[str],
        rule_numbers: list[str],
        in_deck: Callable[[], bool] = lambda: False,
        deck_cards: Callable[[], list[str]] = lambda: [],
        case_sensitive: bool = False,
    ) -> None:
        super().__init__(case_sensitive=case_sensitive, use_cache=False)
        self._card_names = card_names
        self._rule_numbers = rule_numbers
        # Lowercased prefix index for O(n) prefix match per keypress.
        # n is small (35k names), microseconds per call — no bisect needed yet.
        self._card_names_lc = [n.lower() for n in card_names]
        # Late-binding cwd checks — the App passes callables so the
        # suggester follows the user around without being rebuilt. `add` /
        # `remove` only autofill card names in deck context (where they
        # mean "add card", not "create deck"), and `remove` narrows to the
        # deck's own contents because that's the only legal input.
        self._in_deck = in_deck
        self._deck_cards = deck_cards

    async def get_suggestion(self, value: str) -> Optional[str]:  # noqa: D401
        if not value:
            return None

        # No space yet -> complete the command itself.
        if " " not in value:
            v_lc = value.lower()
            for c in COMMANDS:
                if c.startswith(v_lc):
                    return c if c != value else None
            return None

        cmd, _, rest = value.partition(" ")
        cmd_lc = cmd.lower()

        if cmd_lc in ("card", "ruling", "rulings", "combo", "correction", "corrections"):
            return self._suggest_card(cmd, rest)
        if cmd_lc == "commander" and self._in_deck():
            # Usually you promote a card already in the deck, but adding a
            # brand-new one is allowed too — deck first, whole index after.
            return (
                self._suggest_from(cmd, rest, self._deck_cards())
                or self._suggest_card(cmd, rest)
            )
        if cmd_lc == "remove" and self._in_deck():
            return self._suggest_from(cmd, rest, self._deck_cards())
        if cmd_lc == "add" and self._in_deck():
            return self._suggest_card(cmd, rest)
        if cmd_lc == "combos":
            return self._suggest_combos_intersection(cmd, rest)
        if cmd_lc == "rule":
            return self._suggest_rule(cmd, rest)
        return None

    # --- per-command suggestion helpers ---

    def _suggest_from(
        self,
        cmd: str,
        rest: str,
        names: list[str],
        names_lc: Optional[list[str]] = None,
    ) -> Optional[str]:
        """First prefix match in `names`, rendered as a full input line.

        `names_lc` is an optional pre-lowered parallel index — worth having
        for the 35k-name card list, not worth building for a 100-card deck.
        """
        if not rest or not names:
            return None
        rest_lc = rest.lower()
        lowered = names_lc if names_lc is not None else [n.lower() for n in names]
        for name, name_lc in zip(names, lowered):
            if name_lc.startswith(rest_lc):
                suggestion = f"{cmd} {name}"
                # Don't re-suggest what the user already has exactly.
                if suggestion.lower() != f"{cmd} {rest}".lower():
                    return suggestion
                return None
        return None

    def _suggest_card(self, cmd: str, rest: str) -> Optional[str]:
        """Complete from the full card index, using the pre-lowered copy."""
        return self._suggest_from(
            cmd, rest, self._card_names, self._card_names_lc,
        )

    def _suggest_combos_intersection(self, cmd: str, rest: str) -> Optional[str]:
        # Split on ';' — complete only the last segment.
        if ";" in rest:
            prefix, _, tail = rest.rpartition(";")
            tail = tail.lstrip()
            if not tail:
                return None
            rest_lc = tail.lower()
            for name, name_lc in zip(self._card_names, self._card_names_lc):
                if name_lc.startswith(rest_lc):
                    return f"{cmd} {prefix};{(' ' if not prefix.endswith(' ') else '')}{name}"
            return None
        # No ';' yet — treat as the first card.
        return self._suggest_card(cmd, rest)

    def _suggest_rule(self, cmd: str, rest: str) -> Optional[str]:
        if not rest:
            return None
        rest_lc = rest.lower()
        for rn in self._rule_numbers:
            if rn.lower().startswith(rest_lc):
                return f"{cmd} {rn}"
        return None


# --- app --------------------------------------------------------------


class MtgOracleApp(App):
    CSS = """
    Screen {
        layout: vertical;
        background: $background;
    }
    Header { dock: top; }
    Footer { dock: bottom; }
    #main {
        layout: horizontal;
        height: 1fr;
    }
    #nav {
        width: 52;
        border: solid $accent;
        padding: 0 1;
        background: $background;
    }
    #output {
        width: 1fr;
        border: solid $accent;
        padding: 0 1;
        background: $background;
    }
    /* Clickable regions (card names, deck names, combo row numbers) carry
       no decoration at rest, so the plain-text look is unchanged. They
       announce themselves on hover instead. */
    #nav, #output {
        link-color: $text;
        link-background: transparent;
        link-style: not underline;
        link-color-hover: $accent;
        link-background-hover: transparent;
        link-style-hover: bold underline;
    }
    #cmd {
        dock: bottom;
        border: solid $accent;
        margin: 0 0 1 0;
    }
    #cmd-label {
        dock: bottom;
        height: 1;
        padding: 0 1;
        color: $text-muted;
    }
    """

    BINDINGS = [
        Binding("ctrl+q", "quit", "Quit"),
        Binding(":", "focus_cmd", "Command", show=True),
        Binding("escape", "unfocus_cmd", "Unfocus", show=False),
        Binding("ctrl+l", "clear_output", "Clear", show=True),
        # Shell-style history navigation while focused on the input.
        Binding("up", "history_prev", show=False),
        Binding("down", "history_next", show=False),
    ]

    TITLE = "MTG Oracle"

    SEARCH_PAGE_SIZE = 50

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        # The most recent list-of-combos response. Used by `combo-info <N>`
        # so the user can refer to a result by list index instead of the
        # opaque Spellbook id.
        self._last_combos: list[dict] = []

        # Most recent search state — powers pagination (next/prev/page) and
        # the `card <N>` expand shortcut.
        self._search_query: Optional[str] = None
        self._search_page: int = 1
        self._search_total: int = 0
        self._search_rows: list[dict] = []

        # Terminal-style cwd over decks and folders.
        # Both None = root. folder set, deck None = inside a folder.
        # folder optional, deck set = inside a deck (folder may be None for
        # unsorted decks).
        self._cwd_folder: Optional[str] = None
        self._cwd_deck: Optional[str] = None

        # Card names in the deck we're currently inside — kept fresh by
        # `_refresh_nav` (which already loads the deck) so `remove` can
        # autofill from the deck instead of the whole 35k-card index.
        self._deck_card_names: list[str] = []

        # Shell-style history. Most-recent at the end. `_history_idx` is
        # -1 when the user is editing fresh input (not browsing history);
        # 0 = most recent, 1 = second-most-recent, etc. `_history_pending`
        # preserves whatever was typed before history browsing started so
        # `Down` past the newest entry restores it.
        self._history: list[str] = []
        self._history_idx: int = -1
        self._history_pending: str = ""

        # True while the background sync worker is alive, so a second
        # `sync` gets a clear message instead of two competing writers.
        self._sync_running: bool = False

        # Mouse click targets. The `@click` meta in a Rich style is a string
        # parsed by Textual's action parser, and card names are full of
        # apostrophes, commas and `//` — so we never put a name in there.
        # Each clickable region gets an integer ticket into this table.
        #
        # The meta must say `app.click_target(...)`, not `click_target(...)`:
        # a click is brokered with the *widget* as the default namespace, so
        # an unqualified name is looked up on the RichLog and silently never
        # found. Textual's own widgets get away with bare names because their
        # actions live on the widget (Markdown's `link`, Bar's
        # `range_clicked`); ours live on the App.
        self._click_targets: dict[int, tuple[str, tuple]] = {}
        self._click_seq: int = 0
        # Tickets owned by the nav pane, dropped when it re-renders (the
        # pane is cleared, so those lines are gone and can't be clicked).
        # Output-pane tickets stay: old lines scroll back and still work.
        self._nav_click_ids: list[int] = []

    def compose(self) -> ComposeResult:
        yield Header(show_clock=False)
        with Horizontal(id="main"):
            yield RichLog(
                id="nav", wrap=False, markup=False, highlight=False,
                auto_scroll=False,
            )
            yield RichLog(
                id="output", wrap=False, markup=False, highlight=False,
                auto_scroll=True,
            )
        yield Static(
            "Press : to enter a command  |  Ctrl+L clear  |  Ctrl+Q quit  |  Shift+drag to select/copy",
            id="cmd-label",
        )
        yield Input(placeholder="type a command (try 'help' or 'card Deathrite Shaman')", id="cmd")
        yield Footer()

    # Set to True after on_mount has applied the saved theme; until then,
    # watch_theme writes are suppressed so Textual's own default-theme
    # assignment doesn't overwrite the user's saved choice.
    _config_ready: bool = False

    def on_mount(self) -> None:
        log = self.query_one("#output", RichLog)
        log.write(HELP_TEXT)
        self._install_suggester()
        self._refresh_status()
        self._refresh_nav()
        # Restore last-used theme (Ctrl+P palette → "Change theme") if any.
        config = _load_config()
        saved_theme = config.get("theme")
        if saved_theme and saved_theme != self.theme:
            try:
                self.theme = saved_theme
            except Exception:
                # Theme name might be invalid (renamed in a Textual upgrade).
                # Fall back silently — user can pick a new one.
                pass
        self._config_ready = True

    def watch_theme(self, theme: str) -> None:
        """Persist theme picks made via the command palette."""
        if not self._config_ready:
            return
        config = _load_config()
        config["theme"] = theme
        _save_config(config)

    def _install_suggester(self) -> None:
        """Preload autofill sources and wire them into the input widget."""
        try:
            card_names, rule_numbers = self._load_suggestion_data()
        except Exception as e:
            self._write(f"(autofill disabled: {type(e).__name__}: {e})")
            return
        suggester = MtgSuggester(
            card_names, rule_numbers,
            in_deck=lambda: self._cwd_deck is not None,
            deck_cards=lambda: self._deck_card_names,
        )
        self.query_one("#cmd", Input).suggester = suggester

    @staticmethod
    def _load_suggestion_data() -> tuple[list[str], list[str]]:
        # Read lists once at app startup. Same DB_PATH as queries.py.
        from mtg_oracle.queries import DB_PATH
        conn = sqlite3.connect(f"file:{DB_PATH}?mode=ro", uri=True)
        try:
            cur = conn.cursor()
            cards = [row[0] for row in cur.execute(
                "SELECT name FROM cards ORDER BY name COLLATE NOCASE"
            )]
            rules = [row[0] for row in cur.execute(
                "SELECT rule_number FROM rules ORDER BY rule_number"
            )]
        finally:
            conn.close()
        return cards, rules

    # --- actions ---------------------------------------------------

    def action_focus_cmd(self) -> None:
        self.query_one("#cmd", Input).focus()

    def action_unfocus_cmd(self) -> None:
        self.query_one("#output", RichLog).focus()

    def action_clear_output(self) -> None:
        self.query_one("#output", RichLog).clear()

    # --- input handling --------------------------------------------

    def on_input_submitted(self, event: Input.Submitted) -> None:
        raw = event.value.strip()
        event.input.value = ""
        if not raw:
            return
        # Push to history (skip duplicates of the immediately previous entry).
        if not self._history or self._history[-1] != raw:
            self._history.append(raw)
        self._history_idx = -1
        self._history_pending = ""
        self._write(f"> {raw}")
        try:
            self._dispatch(raw)
        except sqlite3.OperationalError as e:
            # "no such column: games" means the DB predates this build. The
            # raw message is true but tells the user nothing they can act on.
            hint = ""
            if "no such column" in str(e) or "no such table" in str(e):
                hint = ("\n   Your database predates this version of the app. "
                        "Run `sync` to migrate it.")
            self._write(f"ERR database: {e}{hint}")
        except Exception as e:
            self._write(f"ERR {type(e).__name__}: {e}")

    # --- history navigation ---------------------------------------

    def action_history_prev(self) -> None:
        if not self._history:
            return
        inp = self.query_one("#cmd", Input)
        if self._history_idx == -1:
            # Save what's currently being typed so Down can restore it.
            self._history_pending = inp.value
            self._history_idx = 0
        elif self._history_idx + 1 < len(self._history):
            self._history_idx += 1
        inp.value = self._history[-(self._history_idx + 1)]
        inp.cursor_position = len(inp.value)

    def action_history_next(self) -> None:
        if self._history_idx == -1:
            return
        inp = self.query_one("#cmd", Input)
        if self._history_idx == 0:
            self._history_idx = -1
            inp.value = self._history_pending
            inp.cursor_position = len(inp.value)
            return
        self._history_idx -= 1
        inp.value = self._history[-(self._history_idx + 1)]
        inp.cursor_position = len(inp.value)

    def _write(self, text: str) -> None:
        log = self.query_one("#output", RichLog)
        log.write(text)

    # --- mouse: clickable regions ----------------------------------

    def _click_ticket(self, kind: str, args: tuple, *, nav: bool) -> int:
        self._click_seq += 1
        self._click_targets[self._click_seq] = (kind, args)
        if nav:
            self._nav_click_ids.append(self._click_seq)
        return self._click_seq

    def _linked_text(
        self, body: str, links: list[r.LinkSpan], *, nav: bool = False,
    ) -> Text:
        """Turn a rendered block plus its link spans into a clickable Text.

        The renderers report spans in (line, column) coordinates because they
        are the only code that knows the column widths and truncation rules.
        """
        by_line: dict[int, list[r.LinkSpan]] = {}
        for span in links:
            by_line.setdefault(span.line, []).append(span)
        out = Text(no_wrap=True)
        lines = body.split("\n")
        for i, line in enumerate(lines):
            cursor = 0
            for span in sorted(by_line.get(i, []), key=lambda s: s.start):
                if span.start < cursor or span.end > len(line):
                    continue  # overlapping or past end of line — skip it
                out.append(line[cursor:span.start])
                ticket = self._click_ticket(span.kind, span.args, nav=nav)
                out.append(
                    line[span.start:span.end],
                    Style.from_meta({"@click": f"app.click_target({ticket})"}),
                )
                cursor = span.end
            out.append(line[cursor:])
            if i < len(lines) - 1:
                out.append("\n")
        return out

    def _write_linked(self, body: str, links: list[r.LinkSpan]) -> None:
        """Write a clickable block to the output pane."""
        if not links:
            self._write(body)
            return
        self.query_one("#output", RichLog).write(self._linked_text(body, links))

    def action_click_target(self, ticket: int) -> None:
        """Run whatever the clicked region stands for."""
        target = self._click_targets.get(ticket)
        if target is None:
            return
        # Clicking a pane focuses it (RichLog is focusable, for scrolling),
        # which would leave the next keystroke going nowhere useful. A click
        # is a shortcut for typing a command, so hand the keyboard back.
        try:
            self.query_one("#cmd", Input).focus()
        except NoMatches:
            pass
        kind, args = target
        if kind == "card":
            self._write(f"> card {args[0]}")
            self._cmd_card(args[0])
        elif kind == "combo":
            self._write(f"> combo-info {args[0]}")
            self._cmd_combo_info(str(args[0]))
        elif kind == "deck":
            folder, deck = args
            path = f"{folder}/{deck}" if folder else deck
            self._write(f"> cd {path}")
            self._cmd_cd(path)
        elif kind == "folder":
            self._write(f"> cd {args[0]}")
            self._cmd_cd(args[0])

    # --- command dispatch ------------------------------------------

    def _dispatch(self, raw: str) -> None:
        head, _, rest = raw.partition(" ")
        head = head.lower()
        rest = rest.strip()

        handler: dict[str, Callable[[str], None]] = {
            "card": self._cmd_card,
            "ruling": self._cmd_ruling,
            "rulings": self._cmd_ruling,
            "combo": self._cmd_combo_single,
            "combos": self._cmd_combo_intersection,
            "combo-info": self._cmd_combo_info,
            "rule": self._cmd_rule,
            "search-rules": self._cmd_search_rules,
            "search": self._cmd_search,
            "next": self._cmd_search_next,
            "prev": self._cmd_search_prev,
            "page": self._cmd_search_page,
            "correction": self._cmd_correction,
            "corrections": self._cmd_correction,
            # cwd-style verbs:
            "cd": self._cmd_cd,
            "pwd": self._cmd_pwd,
            "ls": self._cmd_ls,
            "mkdir": self._cmd_mkdir,
            "rmdir": self._cmd_rmdir,
            "rename": self._cmd_rename,
            "move": self._cmd_move,
            "add": self._cmd_add,
            "remove": self._cmd_remove,
            "commander": self._cmd_commander,
            "points": self._cmd_points,
            "show": self._cmd_show,
            "import": self._cmd_import,
            "paste": self._cmd_paste,
            "sync": self._cmd_sync,
            "copy": self._cmd_copy,
            "help": self._cmd_help,
            "?": self._cmd_help,
            "clear": lambda _: self.action_clear_output(),
            "quit": lambda _: self.exit(),
            "q": lambda _: self.exit(),
            "exit": lambda _: self.exit(),
        }
        fn = handler.get(head)
        if fn is None:
            self._write(f"(unknown command: {head!r}; type 'help')")
            return
        fn(rest)

    # --- command implementations -----------------------------------

    # Sub-topics for `help <topic>`. DECK_HELP and SEARCH_HELP are long
    # enough that inlining them in the overview buried everything else.
    HELP_TOPICS = {
        "decks": DECK_HELP,
        "deck": DECK_HELP,
        "search": SEARCH_HELP,
    }

    def _cmd_help(self, arg: str) -> None:
        topic = arg.strip().lower()
        if not topic:
            self._write(HELP_TEXT)
            return
        body = self.HELP_TOPICS.get(topic)
        if body is None:
            known = ", ".join(sorted({"decks", "search"}))
            self._write(f"(no help topic {topic!r}; try: {known} — or bare `help`)")
            return
        self._write(body)

    def _cmd_card(self, arg: str) -> None:
        if not arg:
            self._write("usage: card <name>  |  or `card <N>` for the N-th row of last search")
            return
        # `card <N>` expands a result from the most recent search, the same
        # pattern combo-info uses for the last combo list. Small integers
        # only; no card is actually named '1'/'2'/...
        if arg.isdigit() and self._search_rows:
            idx = int(arg) - 1
            if 0 <= idx < len(self._search_rows):
                arg = self._search_rows[idx]["name"]
            else:
                self._write(
                    f"(no row #{arg} in last search; valid range is 1..{len(self._search_rows)})"
                )
                return
        # When inside a deck whose commander defines a CI, restrict the
        # embedded "Top combos featuring this card" list to combos that
        # are actually playable in the deck — so e.g. Ashnod's Altar in
        # a Savra (BG) deck doesn't list its UB / GU / W combos.
        restrict_to_ci = None
        if self._cwd_deck:
            try:
                restrict_to_ci = d.get_deck_color_identity(
                    self._cwd_deck, folder=self._cwd_folder,
                )
            except d.DeckError:
                restrict_to_ci = None
        card = q.get_card(arg, restrict_to_ci=restrict_to_ci)
        if not card:
            self._write(f"(card not found: {arg})")
            return
        self._write(r.render_card(card))

    def _cmd_ruling(self, arg: str) -> None:
        if not arg:
            self._write("usage: ruling <name>")
            return
        rulings = q.get_rulings(arg)
        self._write(r.render_rulings(arg, rulings))

    def _cmd_combo_single(self, arg: str) -> None:
        if not arg:
            self._write("usage: combo <card>")
            return
        combos = q.find_combos_with_card(arg, limit=50)
        self._last_combos = combos
        # Auto-expand if there's only one result — the user clearly wants detail.
        if len(combos) == 1:
            full = q.get_combo(combos[0]["id"])
            if full:
                self._write(r.render_combo(full))
                return
        self._write_combo_list(
            combos, f"{len(combos)} combo(s) featuring {arg}:"
        )

    def _cmd_combo_intersection(self, arg: str) -> None:
        # Inside a deck with no args, interpret as "combos in this deck".
        if not arg and self._cwd_deck:
            try:
                combos = d.combos_in_deck(self._cwd_deck, folder=self._cwd_folder)
            except d.DeckError as e:
                self._write(f"combos: {e}")
                return
            # Numbered, not id-keyed: the side pane already shows these as
            # [1]..[N] and `combo-info <N>` resolves against the same list,
            # so showing raw Spellbook ids here made the two panes disagree.
            self._last_combos = combos
            self._write_combo_list(
                combos,
                f"{len(combos)} combo(s) fully contained in {self._cwd_deck!r}:",
            )
            return
        if not arg:
            self._write("usage: combos <card1>; <card2>[; ...]  (or `cd <deck>` and run `combos`)")
            return
        cards = [c.strip() for c in arg.split(";") if c.strip()]
        if len(cards) < 2:
            self._write("need at least 2 cards separated by ';'")
            return
        combos = q.find_combos_with_all(cards, limit=50)
        self._last_combos = combos
        if len(combos) == 1:
            full = q.get_combo(combos[0]["id"])
            if full:
                self._write(r.render_combo(full))
                return
        joined = " + ".join(cards)
        self._write_combo_list(
            combos, f"{len(combos)} combo(s) containing ALL of: {joined}"
        )

    def _cmd_combo_info(self, arg: str) -> None:
        if not arg:
            self._write("usage: combo-info <id-or-number>")
            return
        # If arg is a small integer, treat it as a 1-based index into the
        # most recent combo list (produced by `combo` or `combos`).
        if arg.isdigit() and self._last_combos:
            idx = int(arg) - 1
            if 0 <= idx < len(self._last_combos):
                arg = self._last_combos[idx]["id"]
            else:
                n = len(self._last_combos)
                self._write(f"(no combo #{arg} in last list; valid range is 1..{n})")
                return
        combo = q.get_combo(arg)
        if not combo:
            self._write(f"(combo not found: {arg})")
            return
        self._write(r.render_combo(combo))

    def _cmd_rule(self, arg: str) -> None:
        if not arg:
            self._write("usage: rule <rule_number>")
            return
        rule = q.get_rule(arg)
        if not rule:
            self._write(f"(rule not found: {arg})")
            return
        self._write(r.render_rule(rule))

    def _cmd_search_rules(self, arg: str) -> None:
        if not arg:
            self._write("usage: search-rules <text>")
            return
        rules = q.search_rules(arg, limit=25)
        self._write(r.render_rules_search(arg, rules))

    def _cmd_search(self, arg: str) -> None:
        arg = arg.strip()
        if not arg or arg.lower() in ("help", "?"):
            self._write(SEARCH_HELP)
            return

        # Inside a deck, searches are hard-filtered to what that deck can
        # actually play: the commander's color identity and the deck's
        # format legality. Both are announced rather than applied silently;
        # `cd ..` searches the whole card pool again.
        effective = arg
        filters: list[str] = []
        if self._cwd_deck:
            try:
                deck_ci = d.get_deck_color_identity(
                    self._cwd_deck, folder=self._cwd_folder,
                )
            except d.DeckError:
                deck_ci = None
            if deck_ci is not None:
                ci_token = "".join(deck_ci) if deck_ci else "c"
                effective = f"({effective}) ci<={ci_token}"
                filters.append(f"ci<={''.join(deck_ci) or 'C'}")
            try:
                info = d.get_deck_format_info(
                    self._cwd_deck, folder=self._cwd_folder,
                )
            except d.DeckError:
                info = None
            if info and info["legality_key"]:
                effective = f"({effective}) f:{info['legality_key']}"
                # Show the deck's own format name, but name the inherited
                # pool too — "f:Canadian Highlander" alone would look like
                # a filter we don't actually have data for.
                filters.append(
                    f"f:{info['label']}"
                    + (f" (={info['legality_key']} pool)" if info["custom"] else "")
                )

        ci_notice = ""
        if filters:
            ci_notice = (
                f"[deck filter: {'  '.join(filters)}"
                f"  (`cd ..` to search the full pool)]"
            )

        try:
            total = ss.count_query(effective)
            rows = ss.run_query(effective, limit=self.SEARCH_PAGE_SIZE, offset=0)
        except ss.SearchError as e:
            self._write(f"search error: {e}\n(type `search help` for syntax)")
            return
        self._search_query = effective
        self._search_page = 1
        self._search_total = total
        self._search_rows = rows
        if ci_notice:
            self._write(ci_notice)
        self._render_current_page()

    def _cmd_search_next(self, _: str) -> None:
        if not self._search_query:
            self._write("(no prior search — run `search <query>` first)")
            return
        last_page = max(1, (self._search_total + self.SEARCH_PAGE_SIZE - 1) // self.SEARCH_PAGE_SIZE)
        if self._search_page >= last_page:
            self._write(f"(already on last page {last_page})")
            return
        self._load_search_page(self._search_page + 1)

    def _cmd_search_prev(self, _: str) -> None:
        if not self._search_query:
            self._write("(no prior search — run `search <query>` first)")
            return
        if self._search_page <= 1:
            self._write("(already on first page)")
            return
        self._load_search_page(self._search_page - 1)

    def _cmd_search_page(self, arg: str) -> None:
        if not self._search_query:
            self._write("(no prior search — run `search <query>` first)")
            return
        arg = arg.strip()
        if not arg.isdigit():
            self._write("usage: page <N>")
            return
        n = int(arg)
        last_page = max(1, (self._search_total + self.SEARCH_PAGE_SIZE - 1) // self.SEARCH_PAGE_SIZE)
        if not (1 <= n <= last_page):
            self._write(f"(valid pages are 1..{last_page})")
            return
        self._load_search_page(n)

    def _load_search_page(self, page: int) -> None:
        offset = (page - 1) * self.SEARCH_PAGE_SIZE
        try:
            rows = ss.run_query(
                self._search_query, limit=self.SEARCH_PAGE_SIZE, offset=offset,
            )
        except ss.SearchError as e:
            self._write(f"search error: {e}")
            return
        self._search_page = page
        self._search_rows = rows
        self._render_current_page()

    def _render_current_page(self) -> None:
        last_page = max(1, (self._search_total + self.SEARCH_PAGE_SIZE - 1) // self.SEARCH_PAGE_SIZE)
        hint_parts = []
        if self._search_page < last_page:
            hint_parts.append("`next`")
        if self._search_page > 1:
            hint_parts.append("`prev`")
        hint_parts.append("`card <N>` to expand row")
        nav_hint = "| " + "  |  ".join(hint_parts)
        links: list[r.LinkSpan] = []
        body = r.render_search(
            self._search_rows,
            page=self._search_page,
            total=self._search_total,
            page_size=self.SEARCH_PAGE_SIZE,
            nav_hint=nav_hint,
            links=links,
        )
        self._write_linked(body, links)

    def _cmd_correction(self, arg: str) -> None:
        # One free-text box → OR across relates_to / topic / incorrect_claim.
        # Passing the same term as both `card` and `topic` ANDed the two,
        # so a correction whose topic slug didn't repeat the card name was
        # unfindable by card name.
        rows = q.get_corrections(text=arg.strip() or None, limit=50)
        self._write(r.render_corrections(rows))

    # --- copy to clipboard ----------------------------------------

    def _cmd_copy(self, arg: str) -> None:
        """Copy pane content to the system clipboard via OSC 52.

        Modes:
          copy            -> last command's output (everything after the
                             most recent `> ...` echo in the right pane)
          copy all        -> entire right pane (output)
          copy nav        -> left pane (folder tree or live deck view)
        """
        mode = arg.strip().lower() or "last"
        try:
            if mode == "nav":
                pane = self.query_one("#nav", RichLog)
                text = "\n".join(getattr(l, "text", "") for l in pane.lines)
                source = "left pane"
            elif mode == "all":
                pane = self.query_one("#output", RichLog)
                text = "\n".join(getattr(l, "text", "") for l in pane.lines)
                source = "right pane (full)"
            elif mode == "last":
                pane = self.query_one("#output", RichLog)
                all_lines = [getattr(l, "text", "") for l in pane.lines]
                # Find all `> command` echoes. The last one is `> copy`
                # itself (just inserted by `on_input_submitted`); we want
                # the output that lives between the previous echo and this
                # one — i.e. the result of the user's actual prior command.
                echoes = [
                    i for i, line in enumerate(all_lines)
                    if line.startswith("> ")
                ]
                if len(echoes) < 2:
                    text = ""
                else:
                    text = "\n".join(all_lines[echoes[-2] + 1 : echoes[-1]])
                source = "last command output"
            else:
                self._write("usage: copy            (last command output)")
                self._write("       copy all        (entire right pane)")
                self._write("       copy nav        (left pane)")
                return
        except Exception as e:
            self._write(f"copy: could not read pane: {e}")
            return

        if not text.strip():
            self._write("(nothing to copy)")
            return

        try:
            self.copy_to_clipboard(text)
        except Exception as e:
            self._write(f"copy: clipboard write failed: {e}")
            return
        self._write(f"copied {source} to clipboard ({len(text)} chars)")

    # --- maintenance: sync ----------------------------------------

    def _cmd_sync(self, arg: str) -> None:
        """Run scripts/sync.py in a worker thread, streaming its output.

        A full refresh downloads ~650 MB and takes a couple of minutes;
        doing that on the UI thread froze the app with no sign of life.
        Pass `force` to re-ingest sources whose upstream hasn't moved.
        """
        arg = arg.strip().lower()
        if arg and arg != "force":
            self._write("usage: sync   (or `sync force` to re-ingest unchanged sources)")
            return
        if self._sync_running:
            self._write("(a sync is already running — wait for it to finish)")
            return
        self._sync_running = True
        self._write(
            "Running sync in the background — progress streams in below. "
            "The app stays usable while it works."
        )
        self._run_sync(force=(arg == "force"))

    @work(thread=True, exclusive=True)
    def _run_sync(self, force: bool) -> None:
        """Worker body: spawn sync.py and pump its stdout into the log."""
        sync_path = Path(__file__).resolve().parent.parent / "scripts" / "sync.py"
        cmd = [sys.executable, "-u", str(sync_path)]
        if force:
            cmd.append("--force")
        # Force UTF-8 on the child's stdout: on Windows a piped stdout
        # defaults to cp1252, and one em-dash in an upstream message would
        # kill the sync with a UnicodeEncodeError.
        env = dict(os.environ, PYTHONIOENCODING="utf-8")
        proc = None
        code: Optional[int] = None
        try:
            proc = subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", errors="replace", bufsize=1,
                env=env,
            )
            for line in proc.stdout or ():
                line = line.rstrip()
                if line:
                    self.call_from_thread(self._write, line)
            code = proc.wait()
        except BaseException as e:
            # Anything at all — a failure to spawn, a broken pipe, worker
            # cancellation. Without this the flag below stayed True for the
            # rest of the session and `sync` was permanently unavailable,
            # with the child left running against the same database.
            self.call_from_thread(
                self._write, f"sync aborted: {type(e).__name__}: {e}"
            )
            if proc and proc.poll() is None:
                proc.kill()
                proc.wait()
            raise
        finally:
            self.call_from_thread(self._sync_finished, code)

    def _sync_finished(self, code: Optional[int]) -> None:
        self._sync_running = False
        if code is not None:
            self._write(f"sync exit: {code}")
        # New cards / rules make the autofill index stale, and a `formats`
        # run may have changed the custom-format table the query layer caches.
        q.clear_format_cache()
        self._install_suggester()
        self._refresh_nav()


    # --- cwd-style verbs --------------------------------------------

    def _path_str(self) -> str:
        """Render the current cwd as a unix-style path."""
        if self._cwd_deck:
            base = f"/{self._cwd_folder}" if self._cwd_folder else ""
            return f"{base}/{self._cwd_deck}"
        if self._cwd_folder:
            return f"/{self._cwd_folder}"
        return "/"

    def _refresh_status(self) -> None:
        """Update the bottom status bar with the current path."""
        try:
            label = self.query_one("#cmd-label", Static)
        except NoMatches:
            # Called during init before compose() has run; harmless.
            return
        path = self._path_str()
        label.update(
            f"{path}  |  : focus  |  Ctrl+L clear  |  Ctrl+Q quit  |  Shift+drag to copy"
        )

    def _refresh_nav(self) -> None:
        """Re-render the left navigation panel.

        - In a deck: show a compact, type-grouped render of that deck,
          live-updated as cards are added / removed / imported. The
          right pane stays free for searches, rulings, and combos.
        - At root or in a folder: show the folder/deck tree with the
          current cwd marked by `>`.
        """
        try:
            nav = self.query_one("#nav", RichLog)
        except NoMatches:
            # Called during init before compose() has run; harmless.
            return
        nav.clear()
        # The pane's old lines are gone, so their click tickets are dead.
        for ticket in self._nav_click_ids:
            self._click_targets.pop(ticket, None)
        self._nav_click_ids.clear()

        # Inside a deck → live deck contents + analytics + combos. Width
        # matches CSS #nav width minus the 1-char padding on either side.
        # Combos and analytics live in the side pane so the right pane
        # stays free for searches, card profiles, rulings, and expanded
        # combo detail (`combo-info <N>` against the numbered list).
        if self._cwd_deck:
            try:
                deck = d.get_deck(self._cwd_deck, folder=self._cwd_folder)
            except Exception as e:
                nav.write(f"(deck error: {e})")
                return
            if not deck:
                nav.write(f"(deck disappeared: {self._cwd_deck!r})")
                return
            self._deck_card_names = [
                c["card_name"] for c in deck.get("cards", [])
            ]
            try:
                analytics = a.compute_deck_analytics(deck)
            except Exception as e:
                # Don't crash the side pane — but surface the error so the
                # user can see why the analytics block disappeared.
                nav.write(f"(analytics error: {type(e).__name__}: {e})")
                analytics = None
            try:
                combos = d.combos_in_deck(self._cwd_deck, folder=self._cwd_folder)
            except Exception as e:
                nav.write(f"(combos lookup error: {type(e).__name__}: {e})")
                combos = None
            # Keep _last_combos in sync with what the side pane shows so
            # `combo-info <N>` resolves the same numbered list the user sees.
            if combos is not None:
                self._last_combos = combos
            links: list[r.LinkSpan] = []
            body = r.render_deck_compact(
                deck, width=NAV_CONTENT_WIDTH,
                analytics=analytics, combos=combos, links=links,
            )
            nav.write(self._linked_text(body, links, nav=True))
            return

        # Otherwise → folder/deck tree with cwd marker.
        self._deck_card_names = []
        nav.write("folders / decks")
        nav.write("-" * 16)
        try:
            folders = d.list_folders()
        except Exception as e:
            nav.write(f"(nav error: {e})")
            return

        real_folders = [f for f in folders if f["id"] is not None]
        unsorted_count = next(
            (f["deck_count"] for f in folders if f["id"] is None), 0
        )

        def nav_link(prefix: str, label: str, kind: str, args: tuple) -> None:
            """Write one tree row with the label as a click target."""
            ticket = self._click_ticket(kind, args, nav=True)
            row = Text(prefix, no_wrap=True)
            row.append(label, Style.from_meta({"@click": f"app.click_target({ticket})"}))
            nav.write(row)

        for f in real_folders:
            name = f["name"]
            here = name == self._cwd_folder
            marker = ">" if here else " "
            nav_link(f"{marker} ", name, "folder", (name,))
            try:
                decks_in_folder = d.list_decks(folder=name)
            except Exception as e:
                nav.write(f"    (error listing decks: {type(e).__name__}: {e})")
                continue
            for x in decks_in_folder:
                nav_link("    ", x["name"], "deck", (name, x["name"]))

        if unsorted_count:
            nav.write("")
            nav.write("  (unsorted)")
            unsorted = [
                x for x in d.list_decks(folder=None)
                if x.get("folder") is None
            ]
            for x in unsorted:
                nav_link("    ", x["name"], "deck", (None, x["name"]))

    def _cmd_pwd(self, _: str) -> None:
        self._write(self._path_str())

    def _cmd_cd(self, arg: str) -> None:
        # Collapse any run of whitespace to a single space so a stray
        # double-space (paste artifact, double-press) doesn't break a
        # COLLATE-NOCASE deck-name match.
        target = " ".join(arg.split())
        if not target or target == "/":
            self._cwd_folder = None
            self._cwd_deck = None
            self._refresh_status()
            self._refresh_nav()
            self._write(self._path_str())
            return
        if target == "..":
            if self._cwd_deck:
                self._cwd_deck = None
            elif self._cwd_folder:
                self._cwd_folder = None
            self._refresh_status()
            self._refresh_nav()
            self._write(self._path_str())
            return

        # Path-style: `cd Modern/UR Murktide` or `cd /Modern/UR Murktide`
        # is treated as an absolute jump from root regardless of cwd.
        if "/" in target:
            parts = [p for p in target.split("/") if p]
            if len(parts) == 1:
                # `cd /SoloName` — same as a bareword, but anchored to root.
                self._cwd_folder = None
                self._cwd_deck = None
                target = parts[0]  # fall through to bareword handling
            elif len(parts) == 2:
                folder_name, deck_name = parts
                # Resolve folder -> deck.
                folders = d.list_folders()
                fmatch = next(
                    (f["name"] for f in folders
                     if f["id"] is not None and f["name"].lower() == folder_name.lower()),
                    None,
                )
                if fmatch is None:
                    self._write(f"cd: no folder named {folder_name!r}")
                    return
                deck = d.get_deck(deck_name, folder=fmatch)
                if not deck:
                    self._write(f"cd: no deck named {deck_name!r} in {fmatch!r}")
                    return
                self._cwd_folder = fmatch
                self._cwd_deck = deck["name"]
                self._refresh_status()
                self._refresh_nav()
                self._on_entered_deck()
                return
            else:
                self._write("cd: paths support at most <folder>/<deck>")
                return

        # Inside a deck, only `..` and `/` are meaningful.
        if self._cwd_deck:
            self._write("(already inside a deck — use `cd ..` or `cd /`)")
            return

        # 1) At root or in a folder: try folder first if at root.
        if not self._cwd_folder:
            folders = {
                f["name"].lower(): f["name"]
                for f in d.list_folders() if f["id"] is not None
            }
            if target.lower() in folders:
                self._cwd_folder = folders[target.lower()]
                self._refresh_status()
                self._refresh_nav()
                self._write(self._path_str())
                return

        # 2) Try a deck in the current scope (folder if set, else unsorted).
        try:
            deck = d.get_deck(target, folder=self._cwd_folder)
        except d.DeckError as e:
            # Ambiguous — let the user disambiguate via path syntax.
            self._write(f"cd: {e}")
            return
        if deck:
            self._cwd_folder = deck.get("folder")
            self._cwd_deck = deck["name"]
            self._refresh_status()
            self._refresh_nav()
            self._on_entered_deck()
            return

        # 3) From root only: look for the deck across ALL folders. If exactly
        # one matches, jump there. If several, list them so the user can use
        # `cd <folder>/<deck>` to disambiguate.
        if not self._cwd_folder:
            all_decks = d.list_decks()
            matches = [x for x in all_decks if x["name"].lower() == target.lower()]
            if len(matches) == 1:
                m = matches[0]
                self._cwd_folder = m.get("folder")
                self._cwd_deck = m["name"]
                self._refresh_status()
                self._refresh_nav()
                self._on_entered_deck()
                return
            if len(matches) > 1:
                lines = [f"cd: {len(matches)} decks named {target!r} — use a folder path:"]
                for m in matches:
                    folder = m.get("folder") or "(unsorted)"
                    lines.append(f"  cd {folder}/{m['name']}")
                self._write("\n".join(lines))
                return

        scope = self._cwd_folder or "(any folder)"
        self._write(f"cd: no folder or deck named {target!r} in {scope}")

    def _cmd_ls(self, arg: str) -> None:
        flag = arg.strip().lower()
        # `ls all` / `ls -a` / `ls --all` -> flat list of every deck with
        # its full folder/deck path, regardless of cwd.
        if flag in ("all", "-a", "--all"):
            all_decks = d.list_decks()
            if not all_decks:
                self._write("(no decks)")
                return
            lines = [f"All decks ({len(all_decks)}):"]
            for x in all_decks:
                folder = x.get("folder") or "(unsorted)"
                cards = x.get("card_count", 0)
                fmt = f" [{x['format']}]" if x.get("format") else ""
                updated = (x.get("updated_at") or "")[:10]
                path = f"/{folder}/{x['name']}"
                lines.append(
                    f"  {path:<55} {cards:>4} cards{fmt}  updated {updated}"
                )
            self._write("\n".join(lines))
            return

        # In a deck the full contents already render in the left pane —
        # `ls` here only echoes a one-line summary instead of dumping the
        # whole deck to the right pane. Use `show` if you want the full
        # render in the scrollable output (e.g. to copy out).
        if self._cwd_deck:
            try:
                deck = d.get_deck(self._cwd_deck, folder=self._cwd_folder)
            except Exception as e:
                self._write(f"ls: {e}")
                return
            if not deck:
                self._write(f"(deck not found: {self._cwd_deck!r})")
                return
            total = deck.get("total_main", 0)
            side = deck.get("total_side", 0)
            extras = f" +{side} sb" if side else ""
            fmt = f" [{deck['format']}]" if deck.get("format") else ""
            self._write(
                f"{deck['name']}: {total} cards{extras}{fmt} "
                f"-- full contents in left panel (use `show` to dump here)"
            )
            return
        if self._cwd_folder:
            decks_list = d.list_decks(folder=self._cwd_folder)
            self._write(r.render_deck_list(
                decks_list,
                header=f"Decks in /{self._cwd_folder}:",
                flat=True,
            ))
            return
        # Root: show folders + unsorted decks.
        folders = d.list_folders()
        self._write(r.render_folder_list(folders))
        unsorted = d.list_decks(folder=None)
        unsorted = [x for x in unsorted if x.get("folder") is None]
        if unsorted:
            self._write(r.render_deck_list(unsorted, header="Unsorted decks:"))

    def _show_current_deck(self) -> None:
        deck = d.get_deck(self._cwd_deck, folder=self._cwd_folder)
        if not deck:
            self._write(f"(deck disappeared: {self._cwd_deck!r})")
            self._cwd_deck = None
            self._refresh_status()
            return
        links: list[r.LinkSpan] = []
        self._write_linked(r.render_deck(deck, links), links)

    def _on_entered_deck(self) -> None:
        """Run after every successful `cd` into a deck.

        Just writes the path on the right pane. The deck contents,
        analytics, and combos are all rendered by `_refresh_nav` in the
        side pane, so the right pane stays free for the user's next
        command (search / card / combo-info / etc.).
        """
        self._write(self._path_str())

    def _cmd_mkdir(self, arg: str) -> None:
        name = arg.strip()
        if not name:
            self._write("usage: mkdir <folder>")
            return
        try:
            d.create_folder(name)
            self._write(f"OK created folder {name!r}")
            self._refresh_nav()
        except d.DeckError as e:
            self._write(f"mkdir: {e}")

    def _cmd_rmdir(self, arg: str) -> None:
        name = arg.strip()
        if not name:
            self._write("usage: rmdir <folder>")
            return
        try:
            d.delete_folder(name)
            self._write(f"OK removed folder {name!r}")
            self._refresh_nav()
        except d.DeckError as e:
            self._write(f"rmdir: {e}")

    def _cmd_rename(self, arg: str) -> None:
        if ";" not in arg:
            self._write("usage: rename <old>; <new>")
            return
        old, new = [s.strip() for s in arg.split(";", 1)]
        if not old or not new:
            self._write("usage: rename <old>; <new>")
            return
        try:
            d.rename_deck(old, new, folder=self._cwd_folder)
            self._write(f"OK renamed {old!r} -> {new!r}")
            if self._cwd_deck == old:
                self._cwd_deck = new
                self._refresh_status()
            self._refresh_nav()
        except d.DeckError as e:
            self._write(f"rename: {e}")

    def _cmd_move(self, arg: str) -> None:
        if ";" not in arg:
            self._write("usage: move <deck>; <folder>  (empty folder = unsorted)")
            return
        name, folder = [s.strip() for s in arg.split(";", 1)]
        if not name:
            self._write("usage: move <deck>; <folder>")
            return
        try:
            d.move_deck(name, folder or None, folder=self._cwd_folder)
            self._write(f"OK moved {name!r} -> {folder or '(unsorted)'}")
            self._refresh_nav()
        except d.DeckError as e:
            self._write(f"move: {e}")

    def _cmd_add(self, arg: str) -> None:
        """Context-aware create/add. Behavior depends on cwd:

        - In a deck:  add a card (with optional [--force] and [<qty>])
        - In a folder: create a new deck in this folder
        - At root:    error — folders are created with `mkdir` for clarity
        """
        if self._cwd_deck:
            return self._add_card_to_current_deck(arg)
        if self._cwd_folder:
            return self._add_deck_in_current_folder(arg)
        self._write(
            "(at root: use `mkdir <folder>` to create a folder, "
            "or `cd <folder>` first then `add <deck>` to create a deck)"
        )

    def _add_card_to_current_deck(self, arg: str) -> None:
        arg = arg.strip()
        if not arg:
            self._write("usage: add [--force] <card> [<qty>]")
            return
        # Strip --force anywhere in the args so it works as both a prefix
        # and a suffix (`add --force Foo` and `add Foo --force`).
        force = False
        toks = arg.split()
        if "--force" in toks:
            force = True
            toks = [t for t in toks if t != "--force"]
            arg = " ".join(toks)
        if not arg:
            self._write("usage: add [--force] <card> [<qty>]")
            return
        # Trailing integer quantity, e.g. `add Sol Ring 2`.
        qty = 1
        toks = arg.rsplit(None, 1)
        if len(toks) == 2 and toks[1].isdigit():
            arg, qty = toks[0], int(toks[1])
        try:
            canonical = d.add_card_to_deck(
                self._cwd_deck, arg, quantity=qty, folder=self._cwd_folder,
                force=force,
            )
            tag = " (forced)" if force else ""
            self._write(f"OK {qty}x {canonical}{tag}")
            self._refresh_nav()
        except d.DeckError as e:
            self._write(f"add: {e}")

    def _add_deck_in_current_folder(self, arg: str) -> None:
        name = arg.strip()
        if not name:
            self._write("usage: add <deck>   (in folder context: creates a deck)")
            return
        try:
            d.create_deck(name, folder=self._cwd_folder)
            scope = f"/{self._cwd_folder}" if self._cwd_folder else "(unsorted)"
            self._write(f"OK created deck {name!r} in {scope}")
            self._refresh_nav()
        except d.DeckError as e:
            self._write(f"add: {e}")

    def _cmd_remove(self, arg: str) -> None:
        """Context-aware delete/remove. Behavior depends on cwd:

        - In a deck:  remove copies of a card (with optional [<qty>])
        - In a folder: delete a deck in this folder
        - At root:    error — folders are deleted with `rmdir` for clarity
        """
        if self._cwd_deck:
            return self._remove_card_from_current_deck(arg)
        if self._cwd_folder:
            return self._remove_deck_in_current_folder(arg)
        self._write(
            "(at root: use `rmdir <folder>` to delete a folder, "
            "or `cd <folder>` first then `remove <deck>` to delete a deck)"
        )

    def _remove_card_from_current_deck(self, arg: str) -> None:
        arg = arg.strip()
        if not arg:
            self._write("usage: remove <card> [<qty>]   (omit qty to remove all copies)")
            return
        # Trailing integer = quantity, mirroring `add <card> [<qty>]`.
        qty: Optional[int] = None
        toks = arg.rsplit(None, 1)
        if len(toks) == 2 and toks[1].isdigit():
            arg, qty = toks[0], int(toks[1])
        try:
            canonical, removed, remaining = d.remove_card_from_deck(
                self._cwd_deck, arg, quantity=qty, folder=self._cwd_folder,
            )
            if remaining:
                self._write(
                    f"OK removed {removed}x {canonical} ({remaining} remaining)"
                )
            else:
                self._write(f"OK removed all {removed}x {canonical}")
            self._refresh_nav()
        except d.DeckError as e:
            self._write(f"remove: {e}")

    def _remove_deck_in_current_folder(self, arg: str) -> None:
        name = arg.strip()
        if not name:
            self._write("usage: remove <deck>  (in folder context: deletes a deck)")
            return
        try:
            d.delete_deck(name, folder=self._cwd_folder)
            self._write(f"OK deleted deck {name!r}")
            self._refresh_nav()
        except d.DeckError as e:
            self._write(f"remove: {e}")

    def _cmd_commander(self, arg: str) -> None:
        if not self._cwd_deck:
            self._write("(use `cd <deck>` to enter a deck before `commander`)")
            return
        arg = arg.strip()
        if not arg:
            self._write(
                "usage: commander <card>            promote a card to commander\n"
                "       commander --unset <card>    demote a commander back to main\n"
                "       commander --force <card>    bypass the legality check"
            )
            return
        unset = False
        force = False
        toks = arg.split()
        if "--unset" in toks:
            unset = True
        if "--force" in toks:
            force = True
        toks = [t for t in toks if t not in ("--unset", "--force")]
        arg = " ".join(toks)
        if not arg:
            self._write("usage: commander [--unset] [--force] <card>")
            return
        try:
            canonical, action, format_set = d.set_commander(
                self._cwd_deck, arg, folder=self._cwd_folder, unset=unset,
                force=force,
            )
        except d.DeckError as e:
            self._write(f"commander: {e}")
            return
        msg = {
            "promoted":  f"OK {canonical} promoted to commander",
            "added":     f"OK {canonical} added as commander",
            "unchanged": f"OK {canonical} is already a commander (no change)",
            "demoted":   f"OK {canonical} demoted from commander to main",
        }[action]
        self._write(msg)
        if format_set:
            self._write(
                f"   deck format auto-set to {format_set!r} — "
                f"singleton checks and CI filter on `search` are now active"
            )
        self._refresh_nav()

    def _cmd_points(self, _: str) -> None:
        """Points spent under a points-list format (Canadian Highlander)."""
        if not self._cwd_deck:
            self._write("(use `cd <deck>` to enter a deck before `points`)")
            return
        try:
            points = d.deck_points(self._cwd_deck, folder=self._cwd_folder)
        except d.DeckError as e:
            self._write(f"points: {e}")
            return
        if not points:
            info = d.get_deck_format_info(self._cwd_deck, folder=self._cwd_folder)
            label = info["label"] if info else "this deck's format"
            self._write(
                f"({label} has no points list — points apply to formats like "
                f"Canadian Highlander. Set the deck's format to one of them "
                f"to enable it.)"
            )
            return
        self._write(r.render_points(points))

    def _cmd_show(self, arg: str) -> None:
        target = arg.strip()
        if target:
            # Explicit name overrides cwd. Look up in current folder scope.
            deck = d.get_deck(target, folder=self._cwd_folder)
            if not deck:
                self._write(f"(deck not found: {target})")
                return
            links: list[r.LinkSpan] = []
            self._write_linked(r.render_deck(deck, links), links)
            return
        if self._cwd_deck:
            self._show_current_deck()
            return
        self._write("usage: show <deck>  (or `cd <deck>` then `show`)")

    def _cmd_import(self, arg: str) -> None:
        from pathlib import Path as _P
        arg = arg.strip()
        if not arg:
            self._write("usage: import <filepath>  (current deck)")
            return
        if not self._cwd_deck:
            self._write("(use `cd <deck>` to enter a deck before `import`)")
            return
        try:
            text = _P(arg).read_text(encoding="utf-8")
        except OSError as e:
            self._write(f"import: could not read {arg!r}: {e}")
            return
        self._import_text_into_current_deck(text)

    def _cmd_paste(self, _: str) -> None:
        """Read deckstring from system clipboard and import into current deck."""
        if not self._cwd_deck:
            self._write("(use `cd <deck>` to enter a deck before `paste`)")
            return
        try:
            text = _read_clipboard()
        except Exception as e:
            self._write(f"paste: could not read clipboard: {e}")
            return
        if not text or not text.strip():
            self._write("(clipboard is empty)")
            return
        self._import_text_into_current_deck(text)

    def _import_text_into_current_deck(self, text: str) -> None:
        parsed = parse_deckstring(text)
        if not parsed:
            self._write("(no card lines recognized in input)")
            return
        # Same code path the CLI's `deck import` uses, so both honour the
        # documented "a pasted list loads verbatim" rule (force=True) and
        # both report every row that didn't make it in.
        result = d.load_parsed_into_deck(
            self._cwd_deck, parsed, folder=self._cwd_folder,
        )
        self._write(r.render_import_result(self._cwd_deck, result))
        self._refresh_nav()



    # --- rendering helpers specific to the app -----------------------

    @staticmethod
    def _render_numbered_combo_list(
        combos: list[dict],
        header: str,
        links: Optional[list[r.LinkSpan]] = None,
    ) -> str:
        if not combos:
            return "(no matching combos)"
        lines = [header]
        for i, c in enumerate(combos, 1):
            cards_str = c.get("cards") or c.get("combo_name") or ""
            ci = c.get("color_identity") or "-"
            plus = "+" if c.get("has_template_vars") else ""
            index_label = f"[{i:>3}]"
            row_header = f"  {index_label} {ci:<5} ({c['card_count']}{plus} cards) "
            first_line_no = len(lines)
            lines.extend(r.wrap_combo_row(row_header, cards_str).split("\n"))
            if links is not None:
                links.append(r.LinkSpan(first_line_no, 2, 2 + len(index_label),
                                        "combo", (i,)))
        lines.append("  (click a row number, or type `combo-info <N>`)")
        return "\n".join(lines)

    def _write_combo_list(self, combos: list[dict], header: str) -> None:
        links: list[r.LinkSpan] = []
        body = self._render_numbered_combo_list(combos, header, links)
        self._write_linked(body, links)


def run() -> None:
    MtgOracleApp().run()
