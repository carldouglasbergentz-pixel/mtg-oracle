"""MTG Oracle — Textual TUI app.

Runs in any terminal on Windows / Linux / Mac. Imports
`mtg_oracle.queries` directly; no HTTP, no network.

Launch:
    python scripts/mtg_app.py
"""
from __future__ import annotations

import shlex
import sqlite3
from typing import Callable, Optional

from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.suggester import Suggester
from textual.widgets import Footer, Header, Input, RichLog, Static

from mtg_oracle import queries as q
from mtg_oracle import renderer as r


COMMANDS = [
    "card", "ruling", "combo", "combos", "combo-info",
    "rule", "search-rules", "search",
    "correction", "help", "clear", "quit",
]


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
  search [--name X] [--tag Y] [--type Z] [--mana-ability] [--limit N]
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

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        # The most recent list-of-combos response. Used by `combo-info <N>`
        # so the user can refer to a result by list index instead of the
        # opaque Spellbook id.
        self._last_combos: list[dict] = []

    def compose(self) -> ComposeResult:
        yield Header(show_clock=False)
        yield RichLog(id="output", wrap=False, markup=False, highlight=False, auto_scroll=True)
        yield Static("Press : to enter a command, Ctrl+Q to quit, Ctrl+L to clear.", id="cmd-label")
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
            self._write("usage: card <name>")
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
        try:
            tokens = shlex.split(arg)
        except ValueError as e:
            self._write(f"parse error: {e}")
            return
        name = tag = typ = None
        mana_ability = False
        limit = 50
        i = 0
        while i < len(tokens):
            t = tokens[i]
            if t == "--name" and i + 1 < len(tokens):
                name = tokens[i + 1]; i += 2
            elif t == "--tag" and i + 1 < len(tokens):
                tag = tokens[i + 1]; i += 2
            elif t == "--type" and i + 1 < len(tokens):
                typ = tokens[i + 1]; i += 2
            elif t == "--mana-ability":
                mana_ability = True; i += 1
            elif t == "--limit" and i + 1 < len(tokens):
                try:
                    limit = int(tokens[i + 1]); i += 2
                except ValueError:
                    self._write("--limit needs an integer"); return
            else:
                self._write(f"unknown search token: {t!r}")
                return
        cards = q.search_cards(
            name_like=name, tag=tag, card_type=typ,
            is_mana_ability=mana_ability or None, limit=limit,
        )
        self._write(r.render_search(cards))

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
