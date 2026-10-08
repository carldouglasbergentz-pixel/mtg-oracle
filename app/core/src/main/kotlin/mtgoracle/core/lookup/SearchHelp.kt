package mtgoracle.core.lookup

/**
 * The search language for its users: scryfall_search.SYNTAX_HELP, word for
 * word. Kept beside the parser, so adding an operator and documenting it are
 * one edit (on both sides, while both exist).
 */
val SEARCH_SYNTAX_HELP: String = """
Scryfall-style search. AND is implicit (space-separated). OR, NOT, and
parentheses are supported. `-` is a shortcut for NOT.

Operators:
  o:TEXT      oracle text contains TEXT  (quote for spaces: o:"draw a card")
  t:TEXT      type line contains TEXT    (t:creature, t:planeswalker)
  n:TEXT      name contains TEXT         (substring)
  n=TEXT      name is exactly TEXT       (n:"Lightning Bolt" also matches
                                          'Emeritus of Conflict // Lightning Bolt')
  kw:KW       card has keyword ability   (flying, trample, prowess, ward, ...)
  c:COLORS    colors subset-contains     (c:u any-blue; c:wu contains W and U)
  c=COLORS    colors equal exactly       (c=wu exactly W+U, not tri-colored)
  ci<=COLORS  color identity fits        (commander legality; ci:, ci=, ci>= too)
  mv:N        mana value comparisons     (also mv=, mv<, mv>, mv<=, mv>=, mv!=)
  pow:S       power                      (string match on :/=, numeric for <, >, etc.)
  tou:S       toughness                  (same shape as pow)
  r:RARITY    rarity                     (common | uncommon | rare | mythic | bonus | special)
  layout:X    card layout                (normal | transform | modal_dfc | split | flip | ...)

Format legality:
  f:FORMAT    legal (or restricted) in FORMAT   (aliases: format:, legal:)
  banned:F    on that format's ban list
  restricted:F  restricted in that format. Means "one copy only" in
              Vintage / Old School, but "may not be your commander" in
              Duel Commander (`duel`) and Tiny Leaders (`tlr`).
  game:X      paper | arena | mtgo — `game:paper` drops Arena-only
              Alchemy rebalances (the `A-` cards)
  is:reserved on the Reserved List

  Formats: ${Formats.LEGALITY.joinToString(", ")}
  Spaces and hyphens are ignored, so f:"competitive brawl" works.
  Aliases: edh, pdh, duelcommander, pennydreadful, cbrawl.

Sorting:
  order:asc_FIELD / order:desc_FIELD  (alias sort:) — direction is required.
  Fields: mv, name, power, toughness, rarity, color, ci, edhrec.
  `order:asc_edhrec` is most-played-first. NULLs always sort last.

Boolean:
  A B         both (implicit AND)
  A or B      either
  -A / not A  negation
  (A or B) C  grouping

Mana, function and card kind:
  m:{U}{U}    mana cost has at least these symbols (also m:2uu; m= is exact)
  c:m         multicolored
  otag:TAG    Scryfall Tagger function (otag:removal, otag:ramp, otag:mana-rock;
              a tag also finds its children, removal -> removal-creature, ...)
  is:X        commander, permanent, spell, historic, dfc, mdfc, split, reserved
  like:NAME   the cards most like a card, likest first: what it does (Tagger's
              tags), then its kind, mana value, colours and words (like:"llanowar elves")

Colors can be letters (`u`, `uw`), words (`blue`, `white`, `blue white`), or
braced (`{W}{U}`).

Free text: a bare word or a quoted phrase matches the name, the type line
or the oracle text, so `bolt` finds Lightning Bolt and `goblin` every
Goblin. Without `order:`, name matches come first. Use o:, t: or n: for
one field only.

Examples:
    counterspell
    goblin mv<=2 c:r
    o:"enters the battlefield" t:creature c:u mv<=3
    otag:removal c:w mv<=2 order:asc_edhrec
    like:"llanowar elves" c:r
    kw:flying (c:w or c:u) -t:artifact
    f:competitivebrawl ci<=UR t:instant order:asc_edhrec
    f:commander game:paper t:artifact mv<=2 order:asc_edhrec
    banned:commander
    c=wu t:instant
    pow>=4 t:creature r:mythic
    (kw:flying or kw:trample) c:g mv<=3
""".trimIndent()
