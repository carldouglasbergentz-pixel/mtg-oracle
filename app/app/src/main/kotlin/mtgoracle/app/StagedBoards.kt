package mtgoracle.app

/**
 * Exact table situations, as Forge GameStates (its dev-mode/puzzle format),
 * applied as turn one begins. Shared by the tests, which assert on them, and
 * the `snapshots` mode, which renders them with real art.
 *
 * "human" is player one (your seat; seat A when watching), "ai" player two.
 * Lives are always set: the pinned Forge turns an unlisted life into -1.
 */
object StagedBoards {
    private fun many(card: String, n: Int = 20) = List(n) { card }.joinToString(";")

    private fun base(active: String = "human", phase: String = "MAIN1", humanLibrary: String = many("Island"), aiLibrary: String = many("Swamp")) = listOf(
        "turn=3", "activeplayer=$active", "activephase=$phase", "removesummoningsickness=true", "humanlife=20", "ailife=20",
        "humanlibrary=$humanLibrary", "ailibrary=$aiLibrary",
    )

    /** The opponent's secrets: a hand never shown, a face-down creature, a library of Moxen. Plus one face-down of ours. */
    val hiddenInfo = base(aiLibrary = many("Mox Pearl")) + listOf(
        "humanhand=Lightning Bolt", "humanbattlefield=Mountain;Akroma, Angel of Fury|FaceDown",
        "aihand=Black Lotus;Time Walk;Timetwister", "aibattlefield=Swamp;Exalted Angel|FaceDown",
    )
    val hiddenNames = listOf("Black Lotus", "Time Walk", "Timetwister", "Exalted Angel", "Mox Pearl")

    /** Thoughtseize reveals the AI's hand; after the discard it is hidden again. */
    val reveal = base() + listOf(
        "humanhand=Thoughtseize", "humanbattlefield=Swamp",
        "aihand=Time Walk;Black Lotus", "aibattlefield=Island",
    )

    /** A board as a table has one: auras and equipment on their hosts, counters, tapped cards, face-down, every row. */
    val table = base() + listOf(
        "humanhand=Lightning Bolt;Counterspell;Brainstorm",
        "humanbattlefield=Grizzly Bears|Id:1|Counters:P1P1=2;Rancor|Attaching:1;Serra Angel|Tapped|Id:2;Swiftfoot Boots|Attaching:2;" +
            "Sol Ring;Jace, the Mind Sculptor|Counters:LOYALTY=3;Forest|Tapped;Forest;Plains|Tapped;Island",
        "humangraveyard=Opt;Ponder;Force of Will", "humanexile=Ancestral Recall",
        "aihand=Time Walk;Black Lotus;Timetwister;Mox Sapphire",
        "aibattlefield=Hill Giant|Tapped|Id:3;Pacifism|Attaching:3;Goblin Guide;Exalted Angel|FaceDown;Chrome Mox;Swamp;Swamp|Tapped;Mountain",
        "aigraveyard=Thoughtseize;Lightning Bolt",
    )

    /**
     * Lands as MTGO stacks them: untapped Islands in one frame, tapped ones in
     * another, and a manland (Celestial Colonnade) that the seat animates, so
     * it stands in the creature row until end of turn.
     */
    val lands = base() + listOf(
        "humanhand=Opt",
        "humanbattlefield=Island;Island;Island;Island;Plains;Plains;Island|Tapped;Island|Tapped;Celestial Colonnade",
        "aihand=Duress", "aibattlefield=Swamp;Swamp;Swamp|Tapped;Mountain",
    )

    /**
     * The AI's own turn, a fetchland in hand and a creature to cast with the
     * land it finds: it plays Polluted Delta, cracks it (1 life, sacrifice),
     * fetches a Swamp and casts Hypnotic Specter — the sequence the table must show.
     */
    val fetch = base(active = "ai") + listOf(
        "humanhand=Opt", "humanbattlefield=Island;Island",
        "aihand=Polluted Delta;Hypnotic Specter", "aibattlefield=Swamp;Swamp",
    )

    /** The AI's turn with Lightning Bolt and something of ours worth bolting: a spell with a target, on the stack. */
    val bolt = base(active = "ai") + listOf(
        "humanhand=Opt", "humanbattlefield=Island;Birds of Paradise;Dark Confidant",
        "aihand=Lightning Bolt", "aibattlefield=Mountain",
    )

    /** Your turn with F4 about to be pressed; the AI's Soul Warden triggers when your Grizzly Bears enter. */
    val trigger = base() + listOf(
        "humanhand=Grizzly Bears", "humanbattlefield=Forest;Forest",
        "aihand=", "aibattlefield=Soul Warden;Plains",
    )

    /** The AI's turn from upkeep, drawing from a library of cards it can't cast: the trail may count them, never name them. */
    val hiddenDraws = base(active = "ai", phase = "UPKEEP", aiLibrary = many("Time Walk")) + listOf(
        "humanhand=Opt", "humanbattlefield=Island",
        "aihand=Timetwister", "aibattlefield=Swamp",
    )
    val hiddenDrawNames = listOf("Time Walk", "Timetwister")

    /** A turn of ours with everything the layout must hold still through: a land to play, a Bolt to aim, an attacker. */
    val turn = base() + listOf(
        "humanhand=Mountain;Lightning Bolt;Opt", "humanbattlefield=Mountain;Island;Goblin Guide",
        "aihand=", "aibattlefield=Swamp;Hill Giant",
    )

    /**
     * A crowded table and three burn spells: with one on the stack, the next
     * one's targets include creatures under the floating stack box, which
     * must step aside; then three items stacked.
     */
    val crowded = base() + listOf(
        "humanhand=Lightning Bolt;Lightning Bolt;Shock", "humanbattlefield=Mountain;Mountain;Mountain",
        "aihand=", "aibattlefield=" + listOf("Grizzly Bears", "Hill Giant", "Goblin Guide", "Savannah Lions", "Grizzly Bears", "Hill Giant", "Goblin Guide", "Savannah Lions").joinToString(";"),
    )

    /** The user's crowded side: 9 lands, 3 artifacts, 2 planeswalkers, 6 creatures, and a long graveyard. */
    val crowdedSide = base() + listOf(
        "humanhand=Opt;Remand",
        "humanbattlefield=" + (listOf("Island", "Island", "Island", "Plains", "Plains", "Tundra", "Flooded Strand", "Mystic Sanctuary", "Meticulous Archive",
            "Sol Ring", "Mind Stone", "Chromatic Star", "Teferi, Hero of Dominaria|Counters:LOYALTY=4", "Jace, the Mind Sculptor|Counters:LOYALTY=3",
            "Grizzly Bears", "Snapcaster Mage", "Serra Angel", "Baneslayer Angel", "Restoration Angel", "Spellstutter Sprite")).joinToString(";"),
        "humangraveyard=" + List(16) { "Opt" }.plus("Remand").joinToString(";"), "humanexile=Ancestral Recall;Time Walk;Brainstorm;Ponder;Preordain",
        "aihand=Duress", "aibattlefield=Swamp;Swamp;Hill Giant",
    )

    /** Watching AI vs AI: both hands full of spells neither can cast (no blue mana), so the hands stay put. */
    val watch = base(humanLibrary = many("Plains"), aiLibrary = many("Plains")) + listOf(
        "humanhand=Time Walk;Ancestral Recall", "humanbattlefield=Plains",
        "aihand=Timetwister;Braingeyser", "aibattlefield=Plains",
    )
    val watchHands = listOf("Time Walk", "Ancestral Recall", "Timetwister", "Braingeyser")
}
