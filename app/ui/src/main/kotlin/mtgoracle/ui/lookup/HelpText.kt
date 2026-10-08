package mtgoracle.ui.lookup

import mtgoracle.core.lookup.SEARCH_SYNTAX_HELP

/** `help`: what the command line does. */
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
  combos                              in or on a deck: the combos it holds whole
  combo add <card1>; <card2>[; ...]   a combo of your own (it asks what it does)
  combo remove <user-NNN>             one of your own, after a yes

SEARCH
  search <query>                      Scryfall-style card search — `help search` for the syntax
  next / prev / page <N>              page through the results

DECKS
  cd <deck>                           open it to edit: search and card profiles follow its
                                      commander's colours and its format (shown in the prompt)
  cd ..                               back to the whole card pool
  add [--sb] [--force] <card> [N]     into the open deck (or its sideboard)
  remove [--sb|--considering] <card> [N]   out of it; no N takes every copy
  consider <card> [N]                 onto its considering list
  commander [--unset] <card>          make it (or no longer) the commander
  undo / history                      revert the newest change / the History tab

ANALYSIS
  profile [<deck>|<folder>]           what the deck's cards do, what they really cost, and when
                                      each role is castable; a folder profiles every deck in it
                                      side by side, with the cards they play most
  compare <deck>                      the open (or selected) deck head to head with another
  compare <folder>                    ...or against every deck in a folder: where it steps
                                      outside their ranges, and the cards they play that it lacks
  Import reference lists into a folder and they become a reference set.

DATA
  sync [--force] [<source> ...]       fetch what moved upstream: cards, rules, combos, tags,
                                      oracletags, formats, printings (decks are never touched); --force
                                      fetches it all again
  autosync [on|off]                   the daily sync in the background, on by default:
                                      once a day, Spellbook's 675 MB once a week
  update                              install the newest release, if it is newer: the
                                      app restarts, and data\ is kept
  prune [--yes]                       card rows no export writes any more; a dry run
                                      without --yes, and a deck's cards are always kept

GAMES
  results [<deck>]                    wins–losses–draws against each opponent: the games you
                                      played, and the AI's (simulated on the setup screen, S)

MORE
  copy [last|all]                     the last command's output, or all of it, to the clipboard
  clear                               clear the output (Ctrl+L)
  help search                         the search syntax
  guide                               getting started: the checklist, and a tour of the library
  sealed <set>                        open a sealed pool (six packs of BLB, dom ...) for you and the AI
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
