"""MTG Oracle — the Textual TUI.

Runs in any terminal on Windows / Linux / Mac. No HTTP, no network: every
answer comes from `data/mtg.db` through the service layer.

This module is the App itself — widgets, key bindings, command dispatch and
the two panes. Everything the App merely *uses* lives beside it:
`help` (the help texts), `suggester` (autofill), `divider` (the drag handle),
`config` (persisted preferences), `clipboard` (reading the system clipboard).
Business logic lives in `mtg_oracle.services`; text formatting in
`mtg_oracle.renderer`. A command handler here should read as: parse the
argument, call one service, render the result.

Launch:
    python scripts/mtg_app.py
"""
from __future__ import annotations

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
from textual.widgets import Footer, Header, Input, RichLog, Static

from mtg_oracle import analytics as a
from mtg_oracle import queries as q
from mtg_oracle import renderer as r
from mtg_oracle import roles
from mtg_oracle import services as svc
from mtg_oracle import decks as d
from mtg_oracle.tui.clipboard import read_clipboard
from mtg_oracle.tui.config import REPO_ROOT, load_config, save_config
from mtg_oracle.tui.divider import PaneDivider
from mtg_oracle.tui.help import HELP_TEXT, HELP_TOPICS, SEARCH_HELP
from mtg_oracle.tui.suggester import MtgSuggester


# Width of the left navigation pane (matches the CSS rule). Wide enough that
# the live deck view doesn't truncate names aggressively — and card names got
# longer once points markers were appended to them.
NAV_WIDTH = 52

# Drag limits for the pane divider: neither pane may be squeezed to nothing.
MIN_NAV_WIDTH = 24
MIN_OUTPUT_WIDTH = 30


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
        # Keyboard equivalent of dragging the divider.
        Binding("ctrl+right", "widen_nav", "Widen pane", show=False),
        Binding("ctrl+left", "narrow_nav", "Narrow pane", show=False),
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

        # Most recent search — powers pagination (next/prev/page) and the
        # `card <N>` expand shortcut. None until the first search.
        self._search: Optional[svc.SearchPage] = None

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
            yield PaneDivider(id="divider")
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
        config = load_config()
        # Restore the pane split before anything renders, so the compact deck
        # view is built at the width it will actually be shown at.
        saved_width = config.get("nav_width")
        if isinstance(saved_width, int):
            try:
                self.query_one("#nav", RichLog).styles.width = max(
                    MIN_NAV_WIDTH, saved_width
                )
            except NoMatches:
                pass
        # Restore last-used theme (Ctrl+P palette → "Change theme") if any.
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
        config = load_config()
        config["theme"] = theme
        save_config(config)

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
            format_names=self._format_names(),
        )
        self.query_one("#cmd", Input).suggester = suggester

    @staticmethod
    def _format_names() -> list[str]:
        """Every string `format` accepts, for autofill — plus its one flag."""
        return svc.format_catalog().names() + ["--unset"]

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

    # --- resizable panes ------------------------------------------

    def on_pane_divider_dragged(self, event: PaneDivider.Dragged) -> None:
        # Mid-drag: move the boundary and nothing else. Re-rendering the pane
        # or writing the config here meant a 24-column drag did 24 full deck
        # re-renders and 24 file writes — 24 seconds of apparent hang.
        nav = self.query_one("#nav", RichLog)
        self.set_nav_width(event.screen_x - nav.region.x,
                           refresh=False, persist=False)

    def on_pane_divider_drag_ended(self, event: PaneDivider.DragEnded) -> None:
        # Let go: now do the expensive part, once.
        self._persist_nav_width()
        self._refresh_nav()

    def _nav_width(self) -> int:
        """Current pane width in columns, whatever set it."""
        width = self.query_one("#nav", RichLog).styles.width
        value = getattr(width, "value", None)
        return int(value) if value else NAV_WIDTH

    def _persist_nav_width(self) -> None:
        config = load_config()
        config["nav_width"] = self._nav_width()
        save_config(config)

    def set_nav_width(
        self, width: int, *, refresh: bool = True, persist: bool = True,
    ) -> None:
        """Resize the left pane, clamped so neither pane can be squeezed out.

        `refresh` re-renders the pane contents, which the compact deck view
        needs because it truncates card names to the pane width — but it is
        the expensive half, so a drag turns it off until the mouse is
        released.
        """
        nav = self.query_one("#nav", RichLog)
        largest = max(MIN_NAV_WIDTH, self.size.width - MIN_OUTPUT_WIDTH)
        width = max(MIN_NAV_WIDTH, min(width, largest))
        if width == self._nav_width():
            return
        nav.styles.width = width
        if persist:
            self._persist_nav_width()
        if refresh:
            self._refresh_nav()

    def action_widen_nav(self) -> None:
        self.set_nav_width(self._nav_width() + 2)

    def action_narrow_nav(self) -> None:
        self.set_nav_width(self._nav_width() - 2)

    def _nav_content_width(self) -> int:
        """Columns the nav renderer may use, measured rather than assumed.

        The pane's border and padding each take a column per side; reading the
        widget's own content region keeps this correct after a drag instead of
        depending on a constant that a resize would invalidate.
        """
        try:
            measured = self.query_one("#nav", RichLog).content_region.width
        except NoMatches:
            measured = 0
        # Fallback before layout: the CSS width less the 1-char border and
        # 1-char padding on each side. `NAV_WIDTH - 2` subtracted the padding
        # but not the border, and the widest rows overflowed by two columns.
        return measured if measured > 8 else NAV_WIDTH - 4

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
            self._write(f"> cd /{args[0]}")
            self._cmd_cd(f"/{args[0]}")
        elif kind == "root":
            self._write("> cd /")
            self._cmd_cd("/")

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
            "format": self._cmd_format,
            "points": self._cmd_points,
            "profile": self._cmd_profile,
            "compare": self._cmd_compare,
            "show": self._cmd_show,
            "import": self._cmd_import,
            "paste": self._cmd_paste,
            "export": self._cmd_export,
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

    def _cmd_help(self, arg: str) -> None:
        topic = arg.strip().lower()
        if not topic:
            self._write(HELP_TEXT)
            return
        body = HELP_TOPICS.get(topic)
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
        rows = self._search.rows if self._search else []
        if arg.isdigit() and rows:
            idx = int(arg) - 1
            if 0 <= idx < len(rows):
                arg = rows[idx]["name"]
            else:
                self._write(
                    f"(no row #{arg} in last search; valid range is 1..{len(rows)})"
                )
                return
        card = svc.card_profile(arg, self._ref())
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
        # Inside a deck the query is hard-filtered to what that deck can
        # actually play; `cd ..` searches the whole card pool again.
        try:
            page = svc.deck_search(
                arg, self._ref(), page_size=self.SEARCH_PAGE_SIZE,
            )
        except svc.ServiceError as e:
            self._write(f"search error: {e}\n(type `search help` for syntax)")
            return
        self._search = page
        notice = r.render_deck_filter_notice(page.filters)
        if notice:
            self._write(notice)
        self._render_current_page()

    def _cmd_search_next(self, _: str) -> None:
        if self._search is None:
            self._write("(no prior search — run `search <query>` first)")
            return
        if not self._search.has_next:
            self._write(f"(already on last page {self._search.last_page})")
            return
        self._load_search_page(self._search.page + 1)

    def _cmd_search_prev(self, _: str) -> None:
        if self._search is None:
            self._write("(no prior search — run `search <query>` first)")
            return
        if not self._search.has_prev:
            self._write("(already on first page)")
            return
        self._load_search_page(self._search.page - 1)

    def _cmd_search_page(self, arg: str) -> None:
        if self._search is None:
            self._write("(no prior search — run `search <query>` first)")
            return
        arg = arg.strip()
        if not arg.isdigit():
            self._write("usage: page <N>")
            return
        n = int(arg)
        if not (1 <= n <= self._search.last_page):
            self._write(f"(valid pages are 1..{self._search.last_page})")
            return
        self._load_search_page(n)

    def _load_search_page(self, page: int) -> None:
        # Re-run the *effective* query, filters included — re-scoping per page
        # would silently change the result set when the user walks out of the
        # deck mid-pagination.
        try:
            self._search = svc.search(
                self._search.effective, page=page,
                page_size=self.SEARCH_PAGE_SIZE, filters=self._search.filters,
            )
        except svc.ServiceError as e:
            self._write(f"search error: {e}")
            return
        self._render_current_page()

    def _render_current_page(self) -> None:
        page = self._search
        links: list[r.LinkSpan] = []
        body = r.render_search(
            page.rows,
            page=page.page,
            total=page.total,
            page_size=page.page_size,
            nav_hint=r.render_search_nav_hint(page.has_next, page.has_prev),
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
        sync_path = REPO_ROOT / "scripts" / "sync.py"
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

    def _ref(self) -> svc.DeckRef:
        """The cwd as the services layer wants it: one value, not two."""
        return svc.DeckRef(deck=self._cwd_deck, folder=self._cwd_folder)

    def _path_str(self) -> str:
        """Render the current cwd as a unix-style path."""
        return self._ref().path

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

    def _write_breadcrumb(self, nav: RichLog) -> None:
        """Clickable path at the top of the nav pane.

        Inside a deck the pane switches to the deck's contents, so the tree
        that got you there is gone — without this there is no way back to a
        folder or to root except by typing. Every segment is a click target,
        including the leading `/`.
        """
        row = Text(no_wrap=True)

        def segment(label: str, kind: str, args: tuple) -> None:
            ticket = self._click_ticket(kind, args, nav=True)
            row.append(
                label, Style.from_meta({"@click": f"app.click_target({ticket})"})
            )

        segment("/", "root", ())
        if self._cwd_folder:
            row.append(" ")
            segment(self._cwd_folder, "folder", (self._cwd_folder,))
        if self._cwd_deck:
            row.append(" / ")
            # The deck segment re-enters the deck: harmless, and it keeps the
            # whole path uniformly clickable rather than one dead tail.
            segment(self._cwd_deck, "deck", (self._cwd_folder, self._cwd_deck))
        nav.write(row)
        nav.write("-" * 16)

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

        self._write_breadcrumb(nav)

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
                deck, width=self._nav_content_width(),
                analytics=analytics, combos=combos, links=links,
            )
            nav.write(self._linked_text(body, links, nav=True))
            return

        # Otherwise → folder/deck tree with cwd marker. No header row: the
        # breadcrumb above already says where we are, and two separator
        # lines in a row read as a rendering glitch.
        self._deck_card_names = []
        try:
            folders = d.list_folders()
        except Exception as e:
            nav.write(f"(nav error: {e})")
            return

        real_folders = [f for f in folders if f["id"] is not None]
        unsorted_count = next(
            (f["deck_count"] for f in folders if f["id"] is None), 0
        )

        def nav_link(
            prefix: str, label: str, kind: str, args: tuple, suffix: str = "",
        ) -> None:
            """Write one tree row with the label as a click target."""
            ticket = self._click_ticket(kind, args, nav=True)
            row = Text(prefix, no_wrap=True)
            row.append(label, Style.from_meta({"@click": f"app.click_target({ticket})"}))
            if suffix:
                row.append(suffix)
            nav.write(row)

        for f in real_folders:
            name = f["name"]
            here = name == self._cwd_folder
            marker = ">" if here else " "
            # Show the folder's default format: it's what decks created here
            # will inherit, so it belongs where you create them.
            suffix = f"  [{f['format']}]" if f.get("format") else ""
            nav_link(f"{marker} ", name, "folder", (name,), suffix=suffix)
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

    def _cmd_format(self, arg: str) -> None:
        """Show or set a format. Which one depends on where you are:

        - in a deck:   that deck's format
        - in a folder: the folder's default, inherited by decks created there
        - at root:     nothing to set
        """
        if self._cwd_deck:
            return self._deck_format(arg.strip())
        if self._cwd_folder:
            return self._folder_format(arg.strip())
        self._write(
            "(at root there's nothing to set — `cd <folder>` for a folder "
            "default, or `cd <deck>` for one deck)"
        )

    def _folder_format(self, arg: str) -> None:
        """The folder's default format, inherited by decks created in it."""
        folder = self._cwd_folder
        try:
            if not arg:
                current = d.get_folder_format(folder)
                if not current:
                    self._write(
                        f"folder {folder!r} has no default format.\n"
                        f"  `format <name>` sets one — new decks created here "
                        f"inherit it.\n"
                        f"  add `--all` to also stamp it on decks here that "
                        f"have no format yet.\n"
                        + self._known_formats()
                    )
                else:
                    self._write(
                        f"folder {folder!r} default format: {current!r}\n"
                        f"{self._format_effect(current)}"
                    )
                return
            toks = arg.split()
            apply_all = "--all" in toks
            name = " ".join(t for t in toks if t != "--all")
            if name in ("--unset", "--clear"):
                d.set_folder_format(folder, None)
                self._write(f"OK cleared the default format for {folder!r}")
            elif not name:
                self._write("usage: format <name> [--all]  |  format --unset")
                return
            else:
                stored, _info, updated = d.set_folder_format(
                    folder, name, apply_to_decks=apply_all,
                )
                lines = [
                    f"OK folder {folder!r} default format set to {stored!r} — "
                    f"new decks here inherit it",
                    self._format_effect(stored),
                ]
                if apply_all:
                    lines.append(
                        f"   applied to {updated} existing deck(s) that had no "
                        f"format (decks with one were left alone)"
                    )
                else:
                    lines.append(
                        "   existing decks are unchanged — re-run with `--all` "
                        "to stamp the ones with no format"
                    )
                self._write("\n".join(lines))
        except d.DeckError as e:
            self._write(f"format: {e}")
            return
        self._refresh_nav()

    def _deck_format(self, arg: str) -> None:
        """Show or set the current deck's format — the switch for every rule."""
        try:
            if not arg:
                deck = d.get_deck(self._cwd_deck, folder=self._cwd_folder)
                current = (deck or {}).get("format")
                if not current:
                    self._write(
                        "no format set — no legality, singleton or points rules "
                        "apply.\n"
                        "  set one with `format <name>`, e.g. "
                        "`format canlander` or `format commander`.\n"
                        + self._known_formats()
                    )
                else:
                    self._write(f"format: {current!r}\n{self._format_effect(current)}")
                return
            if arg in ("--unset", "--clear"):
                d.set_deck_format(self._cwd_deck, None, folder=self._cwd_folder)
                self._write("OK format cleared — no format rules apply now")
            else:
                stored, _info = d.set_deck_format(
                    self._cwd_deck, arg, folder=self._cwd_folder,
                )
                self._write(f"OK format set to {stored!r}\n{self._format_effect(stored)}")
        except d.DeckError as e:
            self._write(f"format: {e}")
            return
        self._refresh_nav()

    @staticmethod
    def _known_formats() -> str:
        catalog = svc.format_catalog()
        return r.render_known_formats(catalog.scryfall, catalog.community)

    @staticmethod
    def _format_effect(raw: Optional[str]) -> str:
        """Which rules the named format switches on, in full.

        Takes the raw stored string rather than a resolved dict because
        singleton has a source `resolve_format` doesn't cover — see
        `services.format_rules`.
        """
        return r.render_format_effect(**svc.format_rules(raw))

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

    # Eight turns is what the analysis tables were built around, and the
    # output pane is the wide one, so the curve fits without a width guess.
    _ANALYSIS_TURNS = list(range(1, 9))

    def _analysis_kwargs(self) -> dict:
        return {"role_labels": roles.LABELS,
                "role_order": [x for x in roles.ROLES if x != "land"],
                "turns": self._ANALYSIS_TURNS}

    def _cmd_profile(self, arg: str) -> None:
        """What the deck is made of, and when each role is actually castable.

        No argument profiles the deck you are in, like `export` and `points`.
        Naming one profiles that deck instead, without having to `cd` first.
        """
        name = arg.strip() or self._cwd_deck
        if not name:
            self._write("usage: profile [<deck>]   "
                        "(or `cd <deck>` first to profile the deck you are in)")
            return
        folder = self._cwd_folder if name == self._cwd_deck else None
        try:
            deck = svc.deck_cards_for_analysis(
                svc.DeckRef(deck=name, folder=folder))
        except svc.ServiceError as e:
            self._write(f"profile: {e}")
            return
        profile = svc.profile_deck(deck["name"], deck["cards"])
        # The fall-through list is the useful half on your own deck — these
        # are the cards whose role nothing could establish, so every number
        # above rests on a guess for them.
        _, low = svc.rank_cards([deck], self._analysis_kwargs()["role_order"])
        self._write(r.render_profile([profile], low_confidence=low,
                                     **self._analysis_kwargs()))

    def _cmd_compare(self, arg: str) -> None:
        """This deck against another one in the collection, head to head.

        Deliberately deck-to-deck rather than deck-to-folder-of-files: the
        command language knows decks and folders in the database, and letting
        filesystem paths in here would make it a second, weaker CLI. Import
        the reference lists as decks and they become comparable.
        """
        other = arg.strip()
        if not self._cwd_deck:
            self._write("(use `cd <deck>` to enter a deck before `compare`)")
            return
        if not other:
            self._write("usage: compare <deck>   "
                        f"(measures {self._cwd_deck!r} against that deck)")
            return
        if other == self._cwd_deck:
            self._write("(a deck compared to itself deviates nowhere — "
                        "name a different one)")
            return
        try:
            subject = svc.deck_cards_for_analysis(self._ref())
            reference = svc.deck_cards_for_analysis(svc.DeckRef(deck=other))
        except svc.ServiceError as e:
            self._write(f"compare: {e}")
            return
        cmp = svc.compare_decks(subject, [reference],
                                turns=self._ANALYSIS_TURNS)
        self._write(r.render_comparison(cmp, **self._analysis_kwargs()))

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
        arg = arg.strip()
        if not arg:
            self._write("usage: import <filepath>  (current deck)")
            return
        if not self._cwd_deck:
            self._write("(use `cd <deck>` to enter a deck before `import`)")
            return
        try:
            text = Path(arg).read_text(encoding="utf-8")
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
            text = read_clipboard()
        except Exception as e:
            self._write(f"paste: could not read clipboard: {e}")
            return
        if not text or not text.strip():
            self._write("(clipboard is empty)")
            return
        self._import_text_into_current_deck(text)

    def _import_text_into_current_deck(self, text: str) -> None:
        try:
            result = svc.import_text_into_deck(self._ref(), text)
        except svc.ServiceError as e:
            self._write(f"({e})")
            return
        self._write(r.render_import_result(self._cwd_deck, result))
        self._refresh_nav()

    def _cmd_export(self, arg: str) -> None:
        """The inverse of `paste`: the deck as a list you can paste elsewhere.

        Clipboard by default, because the point is getting the deck into
        Moxfield without a file in between.
        """
        if not self._cwd_deck:
            self._write("(use `cd <deck>` to enter a deck before `export`)")
            return
        toks = arg.split()
        front = "--front-face" in toks
        grouped = "--grouped" in toks
        path = " ".join(t for t in toks if not t.startswith("--")).strip()
        try:
            exported = svc.export_deck_text(
                self._ref(), front_face=front, group_by_role=grouped,
            )
        except svc.ServiceError as e:
            self._write(f"export: {e}")
            return
        if path:
            try:
                Path(path).write_text(exported.text, encoding="utf-8")
            except OSError as e:
                self._write(f"export: could not write {path!r}: {e}")
                return
            self._write(f"OK wrote {exported.cards} cards to {path}")
            return
        try:
            self.copy_to_clipboard(exported.text)
        except Exception as e:
            self._write(f"export: clipboard write failed: {e}")
            return
        parts = ", ".join(f"{n} {k}" for k, n in exported.sections.items())
        self._write(
            f"OK copied {exported.cards} cards ({parts}) to the clipboard — "
            f"paste into Moxfield, Archidekt, or a text file."
        )
        if front:
            self._write("   two-faced names shortened to the front face")
        if grouped:
            self._write("   grouped by role with `//` headers (importers skip them)")

    def _write_combo_list(self, combos: list[dict], header: str) -> None:
        """A clickable, numbered combo list — `combo-info <N>` matches it."""
        links: list[r.LinkSpan] = []
        body = r.render_combo_list(combos, header, numbered=True, links=links)
        self._write_linked(body, links)


def run() -> None:
    MtgOracleApp().run()
