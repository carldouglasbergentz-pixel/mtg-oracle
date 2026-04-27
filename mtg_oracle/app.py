"""MTG Oracle — Textual TUI app.

Runs in any terminal on Windows / Linux / Mac. Imports
`mtg_oracle.queries` directly; no HTTP, no network.

Launch:
    python scripts/mtg_app.py
"""
from __future__ import annotations

import subprocess
import sqlite3
import sys
from typing import Callable, Optional

from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal
from textual.suggester import Suggester
from textual.widgets import Footer, Header, Input, RichLog, Static

from mtg_oracle import queries as q
from mtg_oracle import renderer as r
from mtg_oracle import scryfall_search as ss
from mtg_oracle import decks as d
from mtg_oracle.deck_parser import parse_deckstring


# Width of the left navigation pane (matches the CSS rule). Slightly wider
# than strictly needed so deck names don't get aggressively truncated when
# the live deck view is shown.
NAV_WIDTH = 38


COMMANDS = [
    # Card / rules / combo lookup
    "card", "ruling", "combo", "combos", "combo-info",
    "rule", "search-rules", "search",
    "next", "prev", "page",
    "correction",
    # Terminal-style navigation
    "cd", "pwd", "ls", "mkdir", "rmdir", "new",
    "add", "remove", "show", "rename", "move", "delete",
    "import", "paste",
    # Maintenance
    "sync",
    # Misc
    "help", "clear", "quit",
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

AT ROOT (`/`)
  mkdir <name>              create folder
  rmdir <name>              delete an empty folder
  new <deck>                create an unsorted deck
  show <deck>               render a deck without entering it

INSIDE A FOLDER (`/<folder>/`)
  new <deck>                create deck in this folder
  delete <deck>             delete deck in this folder
  rename <old>; <new>       rename deck
  move <deck>; <folder>     move deck (empty folder = unsorted)
  show <deck>               render a deck without entering

INSIDE A DECK (`/<folder>/<deck>/`)
  ls                        one-line summary (full contents are in the
                            left panel, live-updated as you edit)
  show                      render the full deck to the right pane
                            (useful if you want to scroll / copy it out)
  add <card> [<qty>]        add card (qty defaults to 1)
  remove <card>             remove a card
  combos                    list Spellbook combos fully contained here
  paste                     read deckstring from system clipboard and
                            append to current deck (Windows / macOS / Linux)
  import <filepath>         load a deckstring from a text file
                            (appended to the current deck)

Card-name resolution is tolerant of `/` vs ` // ` and front-face-only DFC
names: `add fire/ice` resolves to the canonical `Fire // Ice`.
"""


SEARCH_HELP = """\
Scryfall-style search. AND is implicit (space-separated). OR, NOT, and
parentheses are supported. `-` is a shortcut for NOT.

Operators:
  o:TEXT      oracle text contains TEXT  (quote for spaces: o:"draw a card")
  t:TEXT      type line contains TEXT    (t:creature, t:planeswalker)
  n:TEXT      name contains TEXT
  kw:KW       card has keyword ability   (flying, trample, prowess, ward, ...)
  c:COLORS    colors subset-contains     (c:u any-blue; c:wu contains W and U)
  c=COLORS    colors equal exactly       (c=wu exactly W+U, not tri-colored)
  mv:N        mana value comparisons     (also mv=, mv<, mv>, mv<=, mv>=, mv!=)
  pow:S       power                      (string match on :/=, numeric for <, >, etc.)
  tou:S       toughness                  (same shape as pow)
  r:RARITY    rarity                     (common | uncommon | rare | mythic | bonus | special)
  layout:X    card layout                (normal | transform | modal_dfc | split | flip | ...)

Colors can be letters (`u`, `uw`), words (`blue`, `white`), or braced (`{W}{U}`).
Bare words and quoted strings default to oracle-text search, so:
    "enters the battlefield"     <=>  o:"enters the battlefield"

Examples:
    o:"enters the battlefield" t:creature c:u mv<=3
    kw:flying (c:w or c:u) -t:artifact
    "counter target spell" c:u mv:2
    c=wu t:instant
    pow>=4 t:creature r:mythic
    not kw:flying t:creature
    (kw:flying or kw:trample) c:g mv<=3
"""


HELP_TEXT = """\
MTG Oracle - local knowledge base

Commands:
  card <name>                         full card profile + tags + rulings + combos
  ruling <name>                       rulings for a card
  combo <card>                        combos featuring a card (auto-expands if 1 match)
  combos <card1>; <card2>[; ...]      combos containing ALL named cards (auto-expand on 1)
  combo-info <id-or-number>           full combo detail; accepts list index from last combo search
  rule <number>                       rule text + children (e.g. '605.1a')
  search-rules <text>                 search rule bodies
  search <query>                      Scryfall-style card search (type `search help` for syntax)
  next / prev / page <N>              navigate search results
  card <N>                            expand the N-th row of the last search
  correction [<card-or-topic>]        list relevant feedback-loop corrections
  cd / ls / pwd / mkdir / new /       deck and folder operations — type `deck help`
    add / remove / show / import /    for the full menu (terminal-style)
    paste / combos / move / rename /
    delete / rmdir
  sync [force]                        refresh data from Scryfall / Wizards / Spellbook
  help                                this screen
  clear                               clear the output pane
  quit                                exit

Typing:
  Autofill suggestions appear as gray text after your command (prefix match).
  Tab or Right Arrow accepts the suggestion.
  Up / Down  cycle through previously submitted commands (shell-style).

Keys:
  :            focus the command input
  Enter        run the command
  Up / Down    previous / next command in history
  Esc          unfocus
  Ctrl+L       clear
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
    """

    def __init__(
        self,
        card_names: list[str],
        rule_numbers: list[str],
        case_sensitive: bool = False,
    ) -> None:
        super().__init__(case_sensitive=case_sensitive, use_cache=False)
        self._card_names = card_names
        self._rule_numbers = rule_numbers
        # Lowercased prefix index for O(n) prefix match per keypress.
        # n is small (34k names), microseconds per call — no bisect needed yet.
        self._card_names_lc = [n.lower() for n in card_names]

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
        if cmd_lc == "combos":
            return self._suggest_combos_intersection(cmd, rest)
        if cmd_lc == "rule":
            return self._suggest_rule(cmd, rest)
        return None

    # --- per-command suggestion helpers ---

    def _suggest_card(self, cmd: str, rest: str) -> Optional[str]:
        if not rest:
            return None
        rest_lc = rest.lower()
        for name, name_lc in zip(self._card_names, self._card_names_lc):
            if name_lc.startswith(rest_lc):
                suggestion = f"{cmd} {name}"
                # Don't re-suggest what the user already has exactly.
                return suggestion if suggestion.lower() != f"{cmd} {rest}".lower() else None
        return None

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
        width: 38;
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

        # Shell-style history. Most-recent at the end. `_history_idx` is
        # -1 when the user is editing fresh input (not browsing history);
        # 0 = most recent, 1 = second-most-recent, etc. `_history_pending`
        # preserves whatever was typed before history browsing started so
        # `Down` past the newest entry restores it.
        self._history: list[str] = []
        self._history_idx: int = -1
        self._history_pending: str = ""

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

    def on_mount(self) -> None:
        log = self.query_one("#output", RichLog)
        log.write(HELP_TEXT)
        self._install_suggester()
        self._refresh_status()
        self._refresh_nav()

    def _install_suggester(self) -> None:
        """Preload autofill sources and wire them into the input widget."""
        try:
            card_names, rule_numbers = self._load_suggestion_data()
        except Exception as e:
            self._write(f"(autofill disabled: {type(e).__name__}: {e})")
            return
        suggester = MtgSuggester(card_names, rule_numbers)
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
            "new": self._cmd_new,
            "delete": self._cmd_delete,
            "rename": self._cmd_rename,
            "move": self._cmd_move,
            "add": self._cmd_add,
            "remove": self._cmd_remove,
            "show": self._cmd_show,
            "import": self._cmd_import,
            "paste": self._cmd_paste,
            "sync": self._cmd_sync,
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

    def _cmd_help(self, _: str) -> None:
        self._write(HELP_TEXT)

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
        card = q.get_card(arg)
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
        self._write(self._render_numbered_combo_list(
            combos, f"{len(combos)} combo(s) featuring {arg}:"
        ))

    def _cmd_combo_intersection(self, arg: str) -> None:
        # Inside a deck with no args, interpret as "combos in this deck".
        if not arg and self._cwd_deck:
            try:
                combos = d.combos_in_deck(self._cwd_deck, folder=self._cwd_folder)
            except d.DeckError as e:
                self._write(f"combos: {e}")
                return
            self._write(r.render_combo_list(
                combos,
                f"{len(combos)} combo(s) fully contained in {self._cwd_deck!r}:",
            ))
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
        self._write(self._render_numbered_combo_list(
            combos, f"{len(combos)} combo(s) containing ALL of: {joined}"
        ))

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
        try:
            total = ss.count_query(arg)
            rows = ss.run_query(arg, limit=self.SEARCH_PAGE_SIZE, offset=0)
        except ss.SearchError as e:
            self._write(f"search error: {e}\n(type `search help` for syntax)")
            return
        self._search_query = arg
        self._search_page = 1
        self._search_total = total
        self._search_rows = rows
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
        self._write(r.render_search(
            self._search_rows,
            page=self._search_page,
            total=self._search_total,
            page_size=self.SEARCH_PAGE_SIZE,
            nav_hint=nav_hint,
        ))

    def _cmd_correction(self, arg: str) -> None:
        rows = q.get_corrections(card=arg or None, topic=arg or None, limit=50)
        self._write(r.render_corrections(rows))

    # --- maintenance: sync ----------------------------------------

    def _cmd_sync(self, arg: str) -> None:
        """Run scripts/sync.py inside the app. Blocks the UI for the
        duration; pass `force` to re-ingest unchanged sources."""
        from pathlib import Path as _P
        sync_path = _P(__file__).resolve().parent.parent / "scripts" / "sync.py"
        cmd = [sys.executable, str(sync_path)]
        if arg.strip().lower() == "force":
            cmd.append("--force")
        elif arg.strip():
            self._write("usage: sync   (or `sync force` to re-ingest unchanged sources)")
            return
        self._write("Running sync — this freezes the UI for up to ~30 s when upstream changed.")
        try:
            result = subprocess.run(
                cmd, capture_output=True, text=True, timeout=600,
            )
        except subprocess.TimeoutExpired:
            self._write("sync: timed out after 10 minutes")
            return
        except Exception as e:
            self._write(f"sync failed to start: {type(e).__name__}: {e}")
            return
        if result.stdout:
            self._write(result.stdout.rstrip())
        if result.stderr:
            self._write(f"stderr:\n{result.stderr.rstrip()}")
        self._write(f"sync exit: {result.returncode}")


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
        except Exception:
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
        except Exception:
            return
        nav.clear()

        # Inside a deck → live deck contents. Width matches CSS #nav width
        # minus the 1-char padding on either side.
        if self._cwd_deck:
            try:
                deck = d.get_deck(self._cwd_deck, folder=self._cwd_folder)
            except Exception as e:
                nav.write(f"(deck error: {e})")
                return
            if not deck:
                nav.write(f"(deck disappeared: {self._cwd_deck!r})")
                return
            nav.write(r.render_deck_compact(deck, width=NAV_WIDTH - 2))
            return

        # Otherwise → folder/deck tree with cwd marker.
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

        for f in real_folders:
            name = f["name"]
            here = name == self._cwd_folder
            marker = ">" if here else " "
            nav.write(f"{marker} {name}")
            try:
                decks_in_folder = d.list_decks(folder=name)
            except Exception:
                decks_in_folder = []
            for x in decks_in_folder:
                nav.write(f"    {x['name']}")

        if unsorted_count:
            nav.write("")
            nav.write("  (unsorted)")
            unsorted = [
                x for x in d.list_decks(folder=None)
                if x.get("folder") is None
            ]
            for x in unsorted:
                nav.write(f"    {x['name']}")

    def _cmd_pwd(self, _: str) -> None:
        self._write(self._path_str())

    def _cmd_cd(self, arg: str) -> None:
        target = arg.strip()
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
                self._write(self._path_str())
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
            self._write(self._path_str())
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
                self._write(self._path_str())
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
        self._write(r.render_deck(deck))

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

    def _cmd_new(self, arg: str) -> None:
        name = arg.strip()
        if not name:
            self._write("usage: new <deck>  (creates in current folder)")
            return
        if self._cwd_deck:
            self._write("(can't create a deck inside a deck — `cd ..` first)")
            return
        try:
            d.create_deck(name, folder=self._cwd_folder)
            scope = f"/{self._cwd_folder}" if self._cwd_folder else "(unsorted)"
            self._write(f"OK created deck {name!r} in {scope}")
            self._refresh_nav()
        except d.DeckError as e:
            self._write(f"new: {e}")

    def _cmd_delete(self, arg: str) -> None:
        name = arg.strip()
        if not name:
            self._write("usage: delete <deck>")
            return
        if self._cwd_deck and name == self._cwd_deck:
            self._write("(can't delete the deck you're inside — `cd ..` first)")
            return
        try:
            d.delete_deck(name, folder=self._cwd_folder)
            self._write(f"OK deleted deck {name!r}")
            self._refresh_nav()
        except d.DeckError as e:
            self._write(f"delete: {e}")

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
        if not self._cwd_deck:
            self._write("(use `cd <deck>` to enter a deck before `add`)")
            return
        arg = arg.strip()
        if not arg:
            self._write("usage: add <card> [<qty>]")
            return
        # Trailing integer quantity, e.g. `add Sol Ring 2`.
        qty = 1
        toks = arg.rsplit(None, 1)
        if len(toks) == 2 and toks[1].isdigit():
            arg, qty = toks[0], int(toks[1])
        try:
            canonical = d.add_card_to_deck(
                self._cwd_deck, arg, quantity=qty, folder=self._cwd_folder,
            )
            self._write(f"OK {qty}x {canonical}")
            self._refresh_nav()
        except d.DeckError as e:
            self._write(f"add: {e}")

    def _cmd_remove(self, arg: str) -> None:
        if not self._cwd_deck:
            self._write("(use `cd <deck>` to enter a deck before `remove`)")
            return
        arg = arg.strip()
        if not arg:
            self._write("usage: remove <card>")
            return
        try:
            d.remove_card_from_deck(
                self._cwd_deck, arg, folder=self._cwd_folder,
            )
            self._write(f"OK removed {arg!r}")
            self._refresh_nav()
        except d.DeckError as e:
            self._write(f"remove: {e}")

    def _cmd_show(self, arg: str) -> None:
        target = arg.strip()
        if target:
            # Explicit name overrides cwd. Look up in current folder scope.
            deck = d.get_deck(target, folder=self._cwd_folder)
            if not deck:
                self._write(f"(deck not found: {target})")
                return
            self._write(r.render_deck(deck))
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
        added = 0
        unresolved: list[str] = []
        for row in parsed:
            if row["section"] == "maybeboard":
                continue
            try:
                d.add_card_to_deck(
                    self._cwd_deck, row["name"], quantity=row["quantity"],
                    is_commander=(row["section"] == "commander"),
                    is_sideboard=(row["section"] == "sideboard"),
                    folder=self._cwd_folder,
                )
                added += 1
            except d.DeckError as e:
                if "card not found" in str(e):
                    unresolved.append(row["name"])
        result = {"added": added, "unresolved": unresolved, "total_input": len(parsed)}
        self._write(r.render_import_result(self._cwd_deck, result))
        self._refresh_nav()



    # --- rendering helpers specific to the app -----------------------

    @staticmethod
    def _render_numbered_combo_list(combos: list[dict], header: str) -> str:
        if not combos:
            return "(no matching combos)"
        lines = [header]
        for i, c in enumerate(combos, 1):
            cards_str = c.get("cards") or c.get("combo_name") or ""
            ci = c.get("color_identity") or "-"
            row_header = f"  [{i:>3}] {ci:<5} ({c['card_count']} cards) "
            lines.append(r.wrap_combo_row(row_header, cards_str))
        lines.append("  (type `combo-info <N>` to expand any row)")
        return "\n".join(lines)


def run() -> None:
    MtgOracleApp().run()
