"""MTG Oracle — Textual TUI app.

Runs in any terminal on Windows / Linux / Mac. Imports
`mtg_oracle.queries` directly; no HTTP, no network.

Launch:
    python scripts/mtg_app.py
"""
from __future__ import annotations

import sqlite3
from typing import Callable, Optional

from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.suggester import Suggester
from textual.widgets import Footer, Header, Input, RichLog, Static

from mtg_oracle import queries as q
from mtg_oracle import renderer as r
from mtg_oracle import scryfall_search as ss


COMMANDS = [
    "card", "ruling", "combo", "combos", "combo-info",
    "rule", "search-rules", "search",
    "next", "prev", "page",
    "correction", "help", "clear", "quit",
]


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
  help                                this screen
  clear                               clear the output pane
  quit                                exit

Typing:
  Autofill suggestions appear as gray text after your command (prefix match).
  Tab or Right Arrow accepts the suggestion.

Keys:
  :            focus the command input
  Enter        run the command
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
    #output {
        border: solid $accent;
        padding: 0 1;
        height: 1fr;
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

    def compose(self) -> ComposeResult:
        yield Header(show_clock=False)
        yield RichLog(id="output", wrap=False, markup=False, highlight=False, auto_scroll=True)
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
        self._write(f"> {raw}")
        try:
            self._dispatch(raw)
        except Exception as e:
            self._write(f"ERR {type(e).__name__}: {e}")

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
        if not arg:
            self._write("usage: combos <card1>; <card2>[; ...]")
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
