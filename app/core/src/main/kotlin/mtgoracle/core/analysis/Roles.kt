package mtgoracle.core.analysis

/*
 * What a card *does* (roles.py): the functional roles it can fill, one
 * primary among them, and whether its card draw repeats. Scryfall Tagger's
 * community labels decide where a card carries a recognised one; the text
 * rules below are the fallback for the ~0.6% that carry none. OVERRIDES holds
 * the judgement calls text cannot settle, each with its reason.
 */

/** One card's roles, primary role and cost, plus how it was decided. */
data class Classification(
    val name: String,
    val primary: String,
    /** Sorted. */
    val roles: List<String>,
    val cost: Cost,
    /** `derived`, `tagged` or `override`. */
    val source: String = "derived",
    val reason: String = "",
    /** No label applied, no text matched, and the card fell to `utility`: worth a human look. */
    val lowConfidence: Boolean = false,
    /** A permanent whose draw repeats turn after turn: an engine count, not a resource count. */
    val engine: Boolean = false,
)

object Roles {
    val ROLES: List<String> = listOf(
        "land", "mana", "ritual", "counter", "sweeper", "discard", "spot", "burn",
        "tutor", "recursion", "threat", "draw", "cantrip", "utility",
    )

    /** Every role but `land`, which reports with the mana base. */
    val REPORT_ROLES: List<String> = ROLES - "land"

    val LABELS: Map<String, String> = mapOf(
        "land" to "Lands", "mana" to "Mana sources", "ritual" to "Rituals",
        "counter" to "Counterspells", "sweeper" to "Sweepers / wraths",
        "discard" to "Hand disruption", "spot" to "Single-target removal",
        "burn" to "Burn / reach", "tutor" to "Tutors", "recursion" to "Recursion",
        "threat" to "Threats / win conditions", "draw" to "Card advantage",
        "cantrip" to "Cantrips / selection", "utility" to "Utility / lock / hate",
    )

    /**
     * Precedence for the ONE primary role, which is a modelling decision:
     * `ritual` beats `mana` because a `mana` primary becomes a permanent
     * source in the on-curve model, and a Dark Ritual counted as a rock hands
     * the deck an extra mana forever. `mana` sits under the answers because
     * Tagger tags removal's drawback (Path to Exile is `land ramp`). A
     * planeswalker is a threat before it is the removal it happens to carry.
     */
    val PRIMARY_ORDER: List<String> = listOf(
        "land", "ritual", "planeswalker", "sweeper", "counter", "discard", "spot", "mana",
        "tutor", "recursion", "threat", "burn", "draw", "cantrip", "utility",
    )

    /** The most a cheap draw may cost and stay selection: Impulse is, Stock Up at three is buying cards. */
    const val CANTRIP_MAX_MANA = 2

    // --- Scryfall Tagger labels ---------------------------------------

    /** Exact labels, checked before the prefixes. An empty list is a deliberate "no role". */
    private val TAG_EXACT_ROLES: Map<String, List<String>> = mapOf(
        "sweeper-graveyard" to listOf("utility"),
        "deanimate" to listOf("spot"),
        "deanimate self" to listOf(), // Detective's Phoenix stops being a creature; not removal
        "pacifism" to listOf("spot"),
        "artifactify" to listOf("spot"),
        "graveyard seal" to listOf("utility"),
        "mass land denial" to listOf("utility"),
        "alternate win condition" to listOf("threat"),
        "cantrip" to listOf("cantrip"),
        "discard" to listOf("discard"),
        "reanimate-self" to listOf(), // a resilient threat, see TAG_SUPPRESS
        "regrowth-self" to listOf(),
        "mana egg" to listOf("ritual"), // Lotus Petal: one shot, like Dark Ritual
        "ramp" to listOf("mana"),
        "land ramp" to listOf("mana"),
        "multi land ramp" to listOf("mana"),
        "mana increaser" to listOf("mana"),
        "adds multiple mana" to listOf("mana"),
        "cost reducer" to listOf("mana"),
        "spot removal" to listOf("spot"),
        "multi removal" to listOf("spot"),
        "repeatable removal" to listOf("spot"),
        "pure draw" to listOf("draw"),
        "burst draw" to listOf("draw"),
        "draw engine" to listOf("draw"),
        "repeatable pure draw" to listOf("draw"),
        "extra turn" to listOf("threat"),
    )

    /**
     * Prefixes, in order. `hate-` first: `hate-counterspell` fights
     * counterspells. Burn is listed exactly: only damage that can point at a
     * creature or permanent is an answer, player burn is a clock.
     */
    private val TAG_PREFIX_ROLES: List<Pair<String, List<String>>> = listOf(
        "hate-" to listOf("utility"),
        "counterspell" to listOf("counter"),
        "sweeper" to listOf("sweeper"),
        "burn creature" to listOf("spot", "burn"),
        "burn any" to listOf("spot", "burn"),
        "burn permanent" to listOf("spot", "burn"),
        "burn battle" to listOf("spot", "burn"),
        "burn player" to listOf("burn"),
        "burn planeswalker" to listOf("burn"),
        "removal-" to listOf("spot"),
        "protects-" to listOf("utility"),
        "tutor-" to listOf("tutor"),
        "reanimate" to listOf("recursion"),
        "regrowth" to listOf("recursion"),
        "temporary reanimation" to listOf("recursion"),
        "ritual" to listOf("ritual"),
        "mana dork" to listOf("mana"),
        "mana rock" to listOf("mana"),
        "utility mana rock" to listOf("mana"),
        "impulsive draw" to listOf("draw"),
        "long term impulsive draw" to listOf("draw"),
        "repeatable impulsive draw" to listOf("draw"),
    )

    /**
     * Labels that silence others on the same card. With a `-self` label,
     * `reanimate-cast` describes the card coming back (Gravecrawler), not an
     * effect on others; one that names another card stays (The Scarab God).
     */
    private val SELF_AMBIGUOUS = listOf("reanimate-cast", "reanimate-face-down")
    private val TAG_SUPPRESS: Map<String, List<String>> = mapOf(
        "reanimate-self" to SELF_AMBIGUOUS,
        "regrowth-self" to SELF_AMBIGUOUS,
    )

    // On removal, Tagger also tags what the victim gets: Path to Exile is `land ramp`.
    private const val REMOVAL_DRAWBACK_EVIDENCE = "donate rampant growth"
    private val REMOVAL_DRAWBACK_EXACT = setOf("land ramp", "multi land ramp")
    private const val REMOVAL_DRAWBACK_PREFIX = "tutor-land-"
    private val REMOVAL_DRAWBACK_ROLES = listOf("mana", "tutor")

    // `spot removal` on damage that only reaches players (Lava Spike). Text can only veto here, never add.
    private val FACE_ONLY_BURN = setOf("burn player", "burn planeswalker", "burn player-each")
    private val HITS_A_CREATURE = rx(
        "\\bdamage\\b[^.]*?\\bto (?:any target|each creature|" +
            "(?:up to \\w+ )?(?:other |another )?target (?:\\w+ )?creature)" +
            "|\\btarget (?:\\w+ )?creature or (?:planeswalker|player)\\b" +
            "|\\bfights?\\b" +
            "|\\b(?:destroy|exile)\\b[^.]*\\bcreature" +
            "|target creature deals damage",
        ignoreCase = true,
    )

    /** The roles one label implies, or null when the label is not recognised. */
    private fun labelRoles(label: String): List<String>? {
        TAG_EXACT_ROLES[label]?.let { return it }
        return TAG_PREFIX_ROLES.firstOrNull { label.startsWith(it.first) }?.second
    }

    private fun isRemovalDrawback(label: String) = label in REMOVAL_DRAWBACK_EXACT || label.startsWith(REMOVAL_DRAWBACK_PREFIX)

    private fun spotIsFaceBurn(tags: Collection<String>, text: String): Boolean {
        val labels = tags.map { it.lowercase() }.toSet()
        val spot = labels.filter { "spot" in (labelRoles(it) ?: emptyList()) }.toSet()
        val burn = labels.filter { "burn" in (labelRoles(it) ?: emptyList()) }.toSet()
        return spot == setOf("spot removal") && burn.isNotEmpty() && FACE_ONLY_BURN.containsAll(burn) &&
            !HITS_A_CREATURE.containsMatchIn(text)
    }

    /**
     * `(roles, recognised)` for a card's Tagger labels. Recognised means at
     * least one label is known, even one that implies no role: that is
     * "Tagger says it does nothing we count", not "Tagger has only flavour".
     */
    fun tagVerdict(tags: Collection<String>): Pair<Set<String>, Boolean> {
        val labels = tags.map { it.lowercase() }.toSet()
        val silenced = labels.flatMap { TAG_SUPPRESS[it] ?: emptyList() }.toSet()
        var recognised = false
        val sources = linkedMapOf<String, MutableSet<String>>()
        for (label in labels) {
            val implied = labelRoles(label) ?: continue
            recognised = true
            if (label in silenced) continue
            implied.forEach { sources.getOrPut(it) { mutableSetOf() }.add(label) }
        }
        if ("spot" in sources && REMOVAL_DRAWBACK_EVIDENCE in labels) {
            for (role in REMOVAL_DRAWBACK_ROLES) {
                if (sources[role]?.all(::isRemovalDrawback) == true) sources.remove(role)
            }
        }
        return sources.keys.toSet() to recognised
    }

    // --- text patterns -------------------------------------------------

    private val COUNTER = rx("\\bcounter target\\b|\\bcounter it\\b|\\bcounter that spell\\b")
    private val SOFT_COUNTER = rx("return target spell to its owner's hand") // Reprieve
    private val SWEEPER = rx(
        // "Exile all cards from target player's library" is mill: Jace's -12 is no wrath.
        "\\b(?:destroy|exile)\\s+all\\b(?!\\s+(?:cards\\s+from|graveyards))" +
            "|\\bput all creatures\\b" +
            "|\\breturn all\\b" +
            "|\\bdestroy each\\b" +
            "|\\bdeals \\d+ damage to each creature\\b" +
            "|\\beach creature (?:gets|gains) -\\d+/-\\d+\\b" +
            "|\\bsacrifices? all\\b",
    )
    private val SPOT = rx(
        "\\b(?:destroy|exile) (?:up to one )?target\\b" +
            "|\\bput target (?:attacking )?(?:creature|nonland permanent|artifact)\\b" +
            "|\\breturn target [^.]{0,50}?to its owner's hand\\b" +
            "|\\btarget creature .{0,40}(?:bottom of its owner's library|owner's hand)\\b" +
            "|\\bgain control of target\\b" +
            "|\\bexile each permanent with the most votes\\b" +
            // The target has to be on the battlefield: "3 damage to target player" is Lava Spike.
            "|\\bdeals? \\d+ damage to (?:any target|up to|target (?:creature|permanent|planeswalker|artifact))\\b" +
            "|\\bdeals? damage equal to [^.]{0,40}to (?:any target|target (?:creature|permanent))\\b" +
            "|\\btarget creature gets -\\d+/-\\d+\\b" +
            "|\\btarget creature gets -x/-x\\b" +
            "|\\bdamage divided as you choose among\\b" +
            // `.` on purpose: Galvanic Discharge puts three sentences between target and damage.
            "|\\bchoose target (?:creature|permanent).{0,180}?\\bdeals that much damage\\b",
    )
    private val TUTOR = rx("\\bsearch your library\\b")
    // Advantage needs the count: two or more is advantage, exactly one replaces the card spent.
    private val DRAW_MANY = rx(
        "\\bdraws? (?:two|three|four|five|six|seven|eight|nine|ten|x|half)\\b" +
            "|\\bdraw cards equal\\b" +
            "|\\bput (?:two|three) of (?:them|those cards) into your hand\\b",
    )
    private val DRAW_ONE = rx("\\bdraws? a card\\b")
    private val TO_HAND = rx("\\bput [^.]{0,24}?into your hand\\b")
    private val TO_HAND_MANY = rx("\\bput (?:two|three|four) (?:of (?:them|those cards) )?into your hand\\b")
    // The Brainstorm template: the draw is partly given back, so it is selection.
    private val PUT_BACK = rx("\\bput (?:one|two|three) cards? from your hand on top\\b|\\bput the rest on the bottom\\b")
    private val SELECT = rx("\\bscry \\d\\b|\\bsurveil \\d\\b|\\blook at the top\\b|\\breveal the top\\b|\\bmill (?:a|one|two)\\b")
    private val INVESTIGATE = rx("\\binvestigate\\b")
    private val WINCON = rx("\\byou win the game\\b")
    private val MAKES_CREATURES = rx("\\bcreate\\b[^.]{0,70}\\bcreature tokens?\\b")
    private val PRODUCES_MANA = rx(":\\s*add\\b")
    // Hand attack needs an opponent discarding: a looter (Smuggler's Copter) is selection.
    private val DISCARD = rx(
        "\\b(?:target (?:player|opponent)|each (?:player|opponent)|that player|" +
            "defending player|opponents?) discards?\\b" +
            "|\\breveals their hand\\b",
    )
    private val BURN_PLAYER = rx("\\bdeals \\d+ damage to (?:each|target|that) (?:player|opponent)\\b|\\bdeals \\d+ damage to each of\\b")
    private val RITUAL = rx("\\badd (?:\\{[^}]+\\}){2,}")
    private val UNTAP_FOR_MANA = rx("\\buntap (?:all|up to \\w+|target) [^.]{0,30}\\blands?\\b")
    private val RECURSION = rx(
        "\\breturn target [^.]{0,60}?from (?:your|a) graveyard to (?:the battlefield|your hand)\\b" +
            "|\\breturn (?:up to \\w+ )?target [^.]{0,40}?cards? from your graveyard\\b",
    )

    // A draw that happens again next turn: loyalty, activated, or a recurring trigger. An ETB draw does not.
    private val LOYALTY_ABILITY = rx("^[+−-]?\\d+\\s*:")
    private val ACTIVATED = rx("^[^:]{0,40}:\\s")
    private val RECURRING_TRIGGER = rx("\\bwhenever\\b|\\bat the beginning of\\b", ignoreCase = true)
    private val DELAYED_TRIGGER = rx("\\bat the beginning of (?:the|your) next\\b", ignoreCase = true) // Mishra's Bauble fires once
    private val ETB_ONLY = rx("\\bwhen (?:this|[A-Z][^,]{0,40}) enters\\b", ignoreCase = true)

    /** `{1}, {T}, Sacrifice Mind Stone: Draw a card` happens once: the permanent does not survive its cost. */
    private fun sacrificesItself(cost: String, card: CardFacts): Boolean {
        val faceName = card.faces.firstOrNull()?.name?.ifEmpty { null }
        val name = (faceName ?: card.name).frontPart().trim()
        val names = "this\\b" + if (name.isNotEmpty()) "|" + Regex.escape(name) else ""
        return rx("\\bsacrifice (?:$names)", ignoreCase = true).containsMatchIn(cost)
    }

    /** A permanent whose card draw repeats rather than happening once. */
    private fun isEngine(card: CardFacts, hasDraw: Boolean): Boolean {
        if (!hasDraw) return false
        val front = card.frontTypeLine()
        if ("Instant" in front || "Sorcery" in front || isLandWord(front)) return false
        if ("Planeswalker" in front) return true // loyalty abilities are per turn by construction
        for (line in (card.oracleText ?: "").split("\n")) {
            val low = clean(line)
            if (!(DRAW_MANY.containsMatchIn(low) || DRAW_ONE.containsMatchIn(low) || TO_HAND.containsMatchIn(low))) continue
            val recurring = RECURRING_TRIGGER.containsMatchIn(DELAYED_TRIGGER.replace(line, " "))
            if (ETB_ONLY.containsMatchIn(line) && !recurring) continue
            val activated = ACTIVATED.find(line)
            if (activated != null && sacrificesItself(activated.value, card)) continue
            if (LOYALTY_ABILITY.containsMatchIn(line) || activated != null || recurring) return true
        }
        return false
    }

    /** The text you can actually use: the front, plus a second face only when it is castable from hand. */
    internal fun roleText(card: CardFacts): String {
        if (card.faces.isEmpty()) return clean(card.oracleText)
        val parts = mutableListOf(card.faces[0].oracleText ?: "")
        if ((card.layout ?: "") in Costs.CASTABLE_SECOND_FACE) {
            card.faces.drop(1).filter { !isLandWord(it.typeLine) && !it.isAftermath() }.forEach { parts += it.oracleText ?: "" }
        }
        return clean(parts.joinToString(" "))
    }

    /** Every role the card's text and types say it can fill. */
    fun derive(card: CardFacts): Set<String> {
        if (card.isLand()) return setOf("land")
        val front = card.frontTypeLine()
        val text = roleText(card)
        val found = linkedSetOf<String>()
        if (COUNTER.containsMatchIn(text) || SOFT_COUNTER.containsMatchIn(text)) found += "counter"
        if (SWEEPER.containsMatchIn(text)) found += "sweeper"
        if (SPOT.containsMatchIn(text)) found += "spot" // no `target` guard: Council's Judgment
        if (TUTOR.containsMatchIn(text)) found += "tutor"
        if (DISCARD.containsMatchIn(text)) found += "discard"
        if (BURN_PLAYER.containsMatchIn(text)) found += "burn"
        if (RECURSION.containsMatchIn(text)) found += "recursion"
        if (("Instant" in front || "Sorcery" in front) && (RITUAL.containsMatchIn(text) || UNTAP_FOR_MANA.containsMatchIn(text))) {
            found += "ritual"
        }
        // NET cards: Brainstorm says "draw three" and gives two back.
        val netPositive = (DRAW_MANY.containsMatchIn(text) && !PUT_BACK.containsMatchIn(text)) ||
            TO_HAND_MANY.containsMatchIn(text) ||
            (DRAW_ONE.containsMatchIn(text) && INVESTIGATE.containsMatchIn(text))
        if (netPositive) found += "draw"
        // A Vehicle is played to attack, like a two-drop creature.
        if ("Creature" in front || "Planeswalker" in front || "Vehicle" in front) found += "threat"
        if (WINCON.containsMatchIn(text) || MAKES_CREATURES.containsMatchIn(text)) found += "threat"
        // Only a card whose whole job is mana, or every MDFC with a land back reads as a Mox.
        if (PRODUCES_MANA.containsMatchIn(text) && found.isEmpty() && "Creature" !in front) found += "mana"
        if (!netPositive && (SELECT.containsMatchIn(text) || DRAW_ONE.containsMatchIn(text) ||
                TO_HAND.containsMatchIn(text) || PUT_BACK.containsMatchIn(text))
        ) {
            found += "cantrip"
        }
        return found.ifEmpty { setOf("utility") }
    }

    private data class Override(val primary: String, val extra: List<String>, val mv: Int?, val reason: String)

    /** Curated judgement calls: name -> (primary, extra roles, mv override, reason). */
    private val OVERRIDES: Map<String, Override> = mapOf(
        "Teferi, Time Raveler" to Override("utility", listOf("spot", "cantrip"), null, "flash-lock is the reason it is played"),
        "Narset, Parter of Veils" to Override("utility", listOf("cantrip"), null, "draw-hate"),
        "Simian Spirit Guide" to Override("ritual", listOf(), 0, "exiled from hand for {R}"),
        "Elvish Spirit Guide" to Override("ritual", listOf(), 0, "exiled from hand for {G}"),
        "Harbinger of the Seas" to Override("utility", listOf("threat"), null, "nonbasic lock"),
        "Hullbreacher" to Override("utility", listOf("threat"), null, "draw-hate"),
        "Solitude" to Override("spot", listOf("threat"), null, ""),
        "Subtlety" to Override("counter", listOf("threat"), null, "soft-counters a creature or planeswalker spell"),
        "Fractured Identity" to Override("spot", listOf("threat"), null, "exile, and in 1v1 you keep the copy"),
        "Brazen Borrower" to Override("spot", listOf("threat"), null, "Petty Theft is the mode you want"),
        "Murktide Regent" to Override("threat", listOf(), 4, "delve floor is 2 but a small Murktide is not a threat"),
        "Shark Typhoon" to Override("threat", listOf("draw"), 4, "cycling {X}{1}{U} at X=2 makes a 2/2 and draws"),
        "Three Steps Ahead" to Override("counter", listOf("draw", "threat"), 3, "spree: {U} plus {1}{U} for the counter mode"),
        "Unexpectedly Absent" to Override("spot", listOf(), 2, "{X}{W}{W} at X=0 tucks on top"),
        "Prismatic Ending" to Override("spot", listOf(), 3, "{X}{W} at X=2"),
        "Emeritus of Ideation" to Override(
            "threat", listOf("draw"), null,
            "CR 722.3 — the inset frame is not castable from hand; the Recall copy costs a further {U} after the body resolves",
        ),
        "Mana Drain" to Override("counter", listOf("mana"), null, ""),
        "Search for Azcanta" to Override("utility", listOf("draw"), null, "engine, transforms into a land"),
        "Shadow of the Second Sun" to Override("utility", listOf(), null, "extra upkeep, draw and untap each turn"),
        "Elixir of Immortality" to Override("utility", listOf(), null, "recursion / anti-mill"),
        "Ghost Vacuum" to Override("utility", listOf(), null, "graveyard hate"),
        "Dress Down" to Override("utility", listOf("cantrip"), null, ""),
        "Settle the Wreckage" to Override("sweeper", listOf(), null, "conditional — attackers only"),
        "Marang River Regent" to Override("draw", listOf("threat", "spot"), null, "the Omen half is the mode that gets cast"),
        "Logic Knot" to Override("counter", listOf(), 3, "{X}{U}{U} at X=2, with delve paying part of X"),
        "Consult the Star Charts" to Override("cantrip", listOf("draw"), null, "two cards only when kicked for {1}{U} more"),
        "Flow State" to Override("cantrip", listOf("draw"), null, "two cards only with an instant and a sorcery in the yard"),
    )

    /** By any name the card goes by: two-faced cards are stored whole and written by their front. */
    private fun overrideFor(name: String, card: CardFacts): Override? =
        OVERRIDES[name] ?: OVERRIDES[name.frontPart().trim()] ?: card.faces.firstOrNull()?.name?.trim()?.let { OVERRIDES[it] }

    /**
     * Roles, primary role and effective cost for one card. Tags REPLACE the
     * text rules rather than union with them, because a loose rule can add a
     * role the tags correctly withheld (Lava Spike). The type line still
     * gives `threat` and `land` either way.
     */
    fun classify(card: CardFacts, xValue: Int = Costs.X_VALUE, tags: Collection<String> = emptyList()): Classification {
        val name = card.name.ifEmpty { "?" }
        var cost = Costs.effective(card, xValue)
        val (tagRoles, recognised) = tagVerdict(tags)
        val tagged = tagRoles.toMutableSet()
        if ("spot" in tagged && spotIsFaceBurn(tags, roleText(card))) tagged -= "spot"
        val derived: MutableSet<String>
        val source: String
        if (recognised) {
            val structural = derive(card).filter { it == "threat" || it == "land" }
            derived = (tagged + structural).toMutableSet().ifEmpty { mutableSetOf("utility") }
            source = "tagged"
        } else {
            derived = derive(card).toMutableSet()
            source = "derived"
        }
        // Flywheel Racer taps for mana only as a creature, after a crew: you play it to attack.
        if ("Vehicle" in card.frontTypeLine()) derived -= "mana"

        // Cheap selection is a cantrip, the same shape at more mana is card
        // advantage; a permanent whose draw repeats is advantage however cheap.
        val repeats = isEngine(card, "draw" in derived || "cantrip" in derived)
        if ("cantrip" in derived && !repeats) {
            if (cost.effective > CANTRIP_MAX_MANA) {
                derived -= "cantrip"
                derived += "draw"
            } else {
                derived -= "draw"
            }
        } else if (repeats) {
            derived += "draw"
            derived -= "cantrip"
        }

        overrideFor(name, card)?.let { o ->
            val roles = (derived + o.extra + o.primary).toMutableSet()
            roles -= "planeswalker"
            if (o.mv != null) cost = cost.copy(effective = o.mv, reason = o.reason.ifEmpty { "see OVERRIDES['$name']" })
            return Classification(name, o.primary, roles.sorted(), cost, "override", o.reason, engine = isEngine(card, "draw" in roles))
        }

        val front = card.frontTypeLine()
        val primary = PRIMARY_ORDER.firstNotNullOfOrNull { role ->
            when {
                role == "planeswalker" -> if ("Planeswalker" in front) "threat" else null
                role in derived -> role
                else -> null
            }
        } ?: "utility"
        // Only a derived fall-through is low confidence: Tagger's `hate-graveyard` alone is a human's answer.
        val low = source == "derived" && derived == setOf("utility") && !card.isLand()
        return Classification(name, primary, derived.sorted(), cost, source, cost.reason, low, isEngine(card, "draw" in derived))
    }
}
