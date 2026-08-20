"""Command-aware autofill for the command input.

Split out because it is the one TUI piece with real logic to get wrong — which
list to complete from depends on the verb, on where the user is standing, and
on how much they have typed — and that logic is worth testing on its own.
"""
from __future__ import annotations

from typing import Callable, Optional

from textual.suggester import Suggester

from mtg_oracle.tui.help import COMMANDS


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
        format_names: Optional[list[str]] = None,
        case_sensitive: bool = False,
    ) -> None:
        super().__init__(case_sensitive=case_sensitive, use_cache=False)
        self._card_names = card_names
        self._rule_numbers = rule_numbers
        self._format_names = format_names or []
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
        if cmd_lc == "format":
            # Format names are easy to half-remember ('canlander' or
            # 'canadian highlander'? 'competitivebrawl' or two words?), so
            # complete them the same way card names are completed.
            return self._suggest_from(cmd, rest, self._format_names)
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
