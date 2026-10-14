package mtgoracle.forge

// Forge's prompt texts, as the seat passes them on.

/**
 * Forge's priority prompt without its status lines: `Priority: You`,
 * `Turn: 4 (AI)`, `Phase: End step`, `Stack: Empty` (InputBase's
 * getTurnPhasePriorityMessage). The header says all of them from the board,
 * so the prompt said them twice. What Forge adds beyond them stays: day or
 * night (written after the turn), the storm count, anything else.
 * [labels] are Forge's words for priority, turn, phase and stack.
 */
internal fun withoutTurnStatus(message: String, labels: List<String>): String =
    message.lines().mapNotNull { line ->
        val label = labels.firstOrNull { line.startsWith("$it: ") } ?: return@mapNotNull line
        // `Turn: 3 (You)  [Night]`: the bracket is the one thing in the status the header lacks.
        if (label == labels.getOrNull(1)) DAY_NIGHT.find(line)?.groupValues?.get(1)?.let { "It is ${it.lowercase()}." } else null
    }.joinToString("\n").trim()

private val DAY_NIGHT = Regex("""\[([^\]]+)]\s*$""")

/** Forge's own words for the four status lines, in the language it runs in. */
internal fun turnStatusLabels(): List<String> = forge.util.Localizer.getInstance().let { l ->
    listOf("lblPriority", "lblTurn", "lblPhase", "lblStack").map { l.getMessage(it) }
}

/** A card's label without the id Forge writes after its name: `Sylvan Library (900)`, `Face-down card (H3)`. */
internal fun withoutCardId(label: String): String = label.replace(CARD_ID, "")

private val CARD_ID = Regex(""" \(H?\d+\)$""")
