"""MTG Oracle — Textual TUI app.

Runs in any terminal on Windows / Linux / Mac. Imports
`mtg_oracle.queries` directly; no HTTP, no network.

Launch:
    python scripts/mtg_app.py

Keys:
    :                focus the command input
    Enter            run the command in the input
    Esc              unfocus input
    Ctrl+Q           quit
    Tab / Shift+Tab  cycle focus

Commands (type in the input box):
    card <name>
    ruling <name>
    combo <card>
    combos <card1>; <card2>[; ...]        intersection
    combo-info <combo_id>
    rule <number>
    search-rules <text>
    search [--name X] [--tag Y] [--type Z] [--mana-ability] [--limit N]
    correction [<card-or-topic>]
    help
    clear
    quit / q
"""
from __future__ import annotations

import shlex
from typing import Callable

from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Vertical
from textual.widgets import Footer, Header, Input, RichLog, Static

from mtg_oracle import queries as q
from mtg_oracle import renderer as r


HELP_TEXT = """\
MTG Oracle - local knowledge base

Commands:
  card <name>                         full card profile + tags + rulings + combos
  ruling <name>                       rulings for a card
  combo <card>                        combos featuring a card
  combos <card1>; <card2>[; ...]      combos containing ALL named cards (use ';' between)
  combo-info <combo_id>               full combo detail
  rule <number>                       rule text + children (e.g. '605.1a')
  search-rules <text>                 search rule bodies
  search [--name X] [--tag Y] [--type Z] [--mana-ability] [--limit N]
  correction [<card-or-topic>]        list relevant feedback-loop corrections
  help                                this screen
  clear                               clear the output pane
  quit                                exit

Keys:
  :            focus the command input
  Enter        run the command
  Esc          unfocus
  Ctrl+Q       quit
"""


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

    def compose(self) -> ComposeResult:
        yield Header(show_clock=False)
        yield RichLog(id="output", wrap=False, markup=False, highlight=False, auto_scroll=True)
        yield Static("Press : to enter a command, Ctrl+Q to quit, Ctrl+L to clear.", id="cmd-label")
        yield Input(placeholder="type a command (try 'help' or 'card Deathrite Shaman')", id="cmd")
        yield Footer()

    def on_mount(self) -> None:
        log = self.query_one("#output", RichLog)
        log.write(HELP_TEXT)

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
        # shlex handles quoting but doesn't preserve apostrophes inside words well
        # for cards like Thassa's Oracle. Fall back to simple split for free-form
        # commands that take a full card name.
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
        self._write(r.render_combo_list(
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
        joined = " + ".join(cards)
        self._write(r.render_combo_list(
            combos, f"{len(combos)} combo(s) containing ALL of: {joined}"
        ))

    def _cmd_combo_info(self, arg: str) -> None:
        if not arg:
            self._write("usage: combo-info <combo_id>")
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
        # Parse optional flags: --name X --tag Y --type Z --mana-ability --limit N
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
        # arg may be a card name or a topic keyword; we try both filters
        rows = q.get_corrections(card=arg or None, topic=arg or None, limit=50)
        self._write(r.render_corrections(rows))


def run() -> None:
    MtgOracleApp().run()
