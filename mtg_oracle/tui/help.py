"""Every help text and command name the TUI knows.

Long enough on its own to bury the App class it used to sit above — the deck
filesystem needs a full screen to explain, and it is the kind of prose that
gets edited far more often than the widget code.

The search syntax is *not* written out here. It lives with the parser that
implements it (`scryfall_search.SYNTAX_HELP`), so adding an operator and
documenting it are one edit; this module appends only the part that is true
of the TUI and not of the CLI.
"""
from __future__ import annotations

from mtg_oracle.scryfall_search import SYNTAX_HELP


COMMANDS = [
    # Card / rules / combo lookup
    "card", "ruling", "combo", "combos", "combo-info",
    "rule", "search-rules", "search",
    "next", "prev", "page",
    "correction",
    # Terminal-style navigation
    "cd", "pwd", "ls", "mkdir", "rmdir",
    "add", "remove", "show", "rename", "move",
    "commander", "format", "points", "import", "paste",
    # Maintenance
    "sync",
    # Misc
    "copy", "help", "clear", "quit",
]


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

FOLDER DEFAULT FORMAT (`/<folder>/`)
  format                    show this folder's default format
  format <name>             set it — decks created here inherit it, so a
                            "Canadian Highlander" folder can hand every new
                            deck the Canlander rules
  format <name> --all       also stamp it on decks here that have no format
                            (decks that already have one are left alone)
  format --unset            clear the default

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
  format                    show the deck's format and which rules it turns
                            on (legality, singleton, points)
  format <name>             set it — `format canlander`, `format commander`,
                            `format competitive brawl`, ...
  format --unset            clear it; no format rules apply
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


SEARCH_HELP = SYNTAX_HELP + """
Inside a deck, `search` is automatically restricted to what that deck can
play — the commander's color identity and the deck's format. The active
filters are shown above the results; `cd ..` searches the full pool.
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
    the path at the top of the left pane -> `/` goes to root, the folder
                                           name goes up one level
    a folder or deck in the left tree    -> cd into it
    a card name anywhere                 -> its full profile, right pane
    a combo's [ N ] row number           -> expands that combo
  Drag the `|` divider between the panes to resize them (Ctrl+Left /
  Ctrl+Right does the same). The split is remembered next launch.
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


# Sub-topics for `help <topic>`. DECK_HELP and SEARCH_HELP are long enough
# that inlining them in the overview buried everything else.
HELP_TOPICS = {
    "decks": DECK_HELP,
    "deck": DECK_HELP,
    "search": SEARCH_HELP,
}
