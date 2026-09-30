package mtgoracle.ui.lookup

import mtgoracle.core.lookup.SEARCH_SYNTAX_HELP

/** `help`: what the command line does. Only what this app has; deck editing is still the TUI's. */
val HELP_TEXT = """
MTG Oracle — command line   (: or Ctrl+K to type, Esc to leave it)

LOOKUP
  card <name>                         full card profile: text, tags, legality, rulings, combos
  card <N>                            the N-th row of the last search
  ruling <name>                       rulings for a card
  rule <number>                       rule text and its child rules (e.g. 605.1a)
  search-rules <text>                 search the rule texts
  correction [<card-or-topic>]        corrections from the feedback loop

COMBOS
  combo <card>                        combos with a card (one match opens it)
  combos <card1>; <card2>[; ...]      combos with ALL of the cards
  combo-info <id-or-number>           a combo in full; <N> is a row of the last list

SEARCH
  search <query>                      Scryfall-style card search — `help search` for the syntax
  next / prev / page <N>              page through the results

DECKS
  cd <deck>                           search and card profiles follow that deck: its
                                      commander's colours and its format (shown in the prompt)
  cd ..                               back to the whole card pool
  Editing decks (add, remove, import, ...) is still the TUI's: `python scripts/mtg_app.py`.

MORE
  copy [last|all]                     the last command's output, or all of it, to the clipboard
  clear                               clear the output (Ctrl+L)
  help search                         the search syntax
  quit                                close the app

Mouse: a card name opens its profile and shows the card in the zoom pane on hover;
a combo's [ N ] opens it; a rule number opens that rule; `next` / `prev` page.

Keys (in the command line):
  Enter       run            Up / Down   history (Down past the newest restores what you typed)
  Tab or →    take the grey suggestion    PgUp / PgDn   scroll the output
  Esc         leave the command line (then Tab switches between the deck and the output)
""".trimIndent()

val SEARCH_HELP = SEARCH_SYNTAX_HELP + """


After `cd <deck>`, `search` only finds what that deck can play: its commander's
colour identity and its format. The filters are named above the results;
`cd ..` searches the full pool again.
"""

val HELP_TOPICS: Map<String, String> = mapOf("search" to SEARCH_HELP)
