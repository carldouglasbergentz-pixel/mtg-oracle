"""MTG Oracle — shared query library over the local SQLite knowledge base.

Import entry point:
    from mtg_oracle.queries import get_card, find_combos, get_rule, ...

All query functions are pure: they take arguments, open a read-only
connection, run parameterized SQL, and return plain Python dicts / lists.
Callers own presentation (CLI, HTTP API, app, LLM tool-use, etc.).
"""
from mtg_oracle.queries import (
    get_card,
    search_cards,
    get_rulings,
    find_combos_with_card,
    find_combos_with_all,
    get_combo,
    get_rule,
    search_rules,
    get_corrections,
)

__all__ = [
    "get_card",
    "search_cards",
    "get_rulings",
    "find_combos_with_card",
    "find_combos_with_all",
    "get_combo",
    "get_rule",
    "search_rules",
    "get_corrections",
]
