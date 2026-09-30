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
    "commander", "format", "points", "import", "paste", "export",
    "history", "undo", "consider", "forge",
    "profile", "compare",
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
  cd (unsorted)/<deck>      a deck outside any folder, by path
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
                            (`show`, `profile` and `compare` also take
                            <folder>/<deck>; a bare name is looked up in
                            the current folder first, then in any folder)

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
  add <card> [<qty>]        add card (qty defaults to 1). A name that ends
                            in a number is read as that card first:
                            `add Pain 101` adds Pain 101; `add Pain 101 2`
                            adds two of it
  add --force <card>        bypass the deck's rules: commander CI,
                            format legality, singleton and points
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
                            cards show their points after the name, as
                            `Card Name (3)`, in `show` and the left pane.
  consider <card> [<qty>]   put a card on the deck's considering list (its
                            maybeboard): shown last, not counted, exported or
                            played, and not held to the deck's rules
  combos                    list Spellbook combos fully contained here
  paste                     read deckstring from system clipboard and
                            append to current deck (Windows / macOS / Linux)
  import <filepath>         load a deckstring from a text file
                            (appended to the current deck)
  paste --replace           make the deck exactly the clipboard list, as one
                            change, and show what was added / removed /
                            changed. A card name it doesn't recognise stops
                            it with nothing changed — add `--force` to
                            replace without those cards
  import <filepath> --replace [--force]
                            the same, from a file
  history [N]               the deck's last N recorded changes (default 20)
  history <deck> [N]        another deck's, without `cd` (or
                            <folder>/<deck>)
  undo                      revert the latest change — add, remove,
                            commander, paste, import or a replace. Running
                            it again redoes it
  export                    copy the deck to the clipboard as a `N Card Name`
                            list — paste straight into Moxfield or Archidekt
  export <filepath>         write that list to a file instead
  export --front-face       shorten two-faced names to the front face
                            (split cards keep `//`; `Fire` is not a card)
  export --grouped          add `// role` headers; importers skip them
  profile                   what the deck is made of: role densities,
                            reach, and the exact odds each role is
                            castable on each turn. Ends with the cards
                            whose role could not be established.
  profile <deck>            profile another deck without `cd`-ing to it
  compare <deck>            this deck against that one, head to head:
                            role counts, curve deltas, and which cards
                            each plays that the other does not

Folder and deck names can't contain `/` — it separates them in a path.

Card-name resolution is tolerant of `/` vs ` // ` and front-face-only DFC
names: `add fire/ice` resolves to the canonical `Fire // Ice`.

`compare` measures against one deck in this collection. To measure against
a whole set of reference lists, run `scripts/analyse_archetype.py --dir
<folder> --compare <deck>`: with several lists it reports ranges rather
than a single opponent, and a range nobody left is a rule worth knowing.
"""


SEARCH_HELP = SYNTAX_HELP + """
Inside a deck, `search` is automatically restricted to what that deck can
play — the commander's color identity and the deck's format. The active
filters are shown above the results; `cd ..` searches the full pool.
"""


FORGE_HELP = """\
Forge is the playtest engine (tools/forge/). mtg-oracle writes your decks as
Forge .dck files, has Forge's AI play them against each other, and keeps the
results. Decks are named as everywhere else: <deck>, or <folder>/<deck> when
the name is in more than one folder.

  forge export [<deck>]     write <deck>.dck into Forge's decks folder (the
                            deck you are in, if you name none). Inside a
                            deck, clicking `[-> forge]` in the left pane's
                            header does the same
    --force                 export even though Forge lacks some cards; they
                            are left out, and listed
    --overwrite             replace a .dck mtg-oracle didn't write, or one
                            edited in Forge since. A hand-made file with the
                            deck's name is refused until you pass this
  forge sub add <card> -> <substitute>
                            (inside a deck) the AI copy plays <substitute>
                            wherever the deck has <card>
  forge sub remove <card>   (inside a deck) drop that substitution
  forge sub list [<deck>]   a deck's substitutions
  forge play                open Forge's own window; exported decks are
                            under Constructed / Commander in its lists
  forge sim <opponent> [N]  (inside a deck) Forge's AI plays this deck
                            against <opponent>, N games (default 3). Runs in
                            the background — about 6 s, plus 2-8 s a game —
                            and the result lands in the output pane
  forge sim <deck>; <opponent> [N]
                            the same, from anywhere
    --no-ai-variant         play the decks as built, ignoring substitutions
  forge results [<deck>]    stored records for a deck (the one you are in,
                            or the one named); at root, the whole win matrix

THE AI COPY
  Forge's AI can't play some cards at all; `forge export` lists them. A
  substitution never changes your deck: export writes a second file,
  `<deck> (AI).dck`, with the substitutes swapped in, and that copy is what
  the AI pilots in `forge sim` whenever the deck has substitutions. A
  substitute has to pass the rules `add` would apply — colour identity,
  legality, singleton, points.

COMMANDER
  A deck with a commander is exported and simmed as Commander, and Forge
  plays Commander at 40 life with 21 commander damage lethal. That is not
  Duel Commander (20 life), so read those games as a Commander proxy.
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
  profile [<deck>]                    role densities, reach, and the odds each
                                      role is castable on each turn
  compare <deck>                      this deck against that one, head to head
  history / undo                      recorded changes; undo reverts the latest
                                      (`help decks` for replacing a whole list)

MAINTENANCE
  sync [force]                        refresh data from Scryfall / Wizards / Spellbook
  copy [last|all|nav]                 copy pane content to clipboard
                                      (last = output since last command; all = full
                                      right pane; nav = left pane)
  clear                               clear the output pane
  quit                                exit

FORGE                                 (playtest decks in the Forge engine)
  forge export | sub | play | sim | results     see `help forge`

MORE HELP
  help decks                          the deck / folder filesystem model, in full
  help search                         Scryfall-style search syntax and examples
  help forge                          exporting to Forge, AI copies, sims, results

Mouse:
  Clickable, in both panes — they underline when you hover:
    the path at the top of the left pane -> `/` goes to root, the folder
                                           name goes up one level
    a folder or deck in the left tree    -> cd into it
    a card name anywhere                 -> its full profile, right pane
    a combo's [ N ] row number           -> expands that combo
  Drag the `|` divider between the panes to resize them (Ctrl+Left /
  Ctrl+Right does the same, even while typing). The split is remembered
  next launch.
  Everything is still reachable by typing; the mouse is a shortcut, not a
  second interface. Shift+drag still selects text.

Typing:
  Autofill suggestions appear as gray text after your command (prefix match).
  Right Arrow (with the cursor at the end) accepts the suggestion. Inside a
  deck, `remove` completes from the cards actually in that deck.
  Up / Down  cycle through previously submitted commands (shell-style).

Keys:
  :            focus the command input
  Enter        run the command
  Up / Down    previous / next command in history
  Esc          unfocus
  Ctrl+L       clear
  Ctrl+Left / Ctrl+Right   narrow / widen the left pane
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
    "forge": FORGE_HELP,
}
