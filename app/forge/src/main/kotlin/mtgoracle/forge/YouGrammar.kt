package mtgoracle.forge

/**
 * Forge writes its sentences about a player in the third person (`%s chooses`,
 * `%s has`), and the person at our seat plays under the name "You"
 * (ForgeMatch), so `You chooses` is said `You choose`. Only the word You
 * followed by a lowercase verb in -s changes: a card named "You Come to a
 * River" has a capital after it, and every other player's name is left as
 * Forge wrote it. A plain replace that cannot throw: it runs inside Forge's
 * handlers.
 */
internal object YouGrammar {
    private val YOU_VERB = Regex("""(?<![\w'’])You ([a-z]+)(?=[\s.,;:!?)]|$)""")
    private val IRREGULAR = mapOf("has" to "have", "is" to "are", "was" to "were", "does" to "do")
    private val ES_ENDINGS = listOf("ches", "shes", "sses", "xes", "zes", "oes")

    /** [text] with every `You <verb>s` said as a person says it. */
    fun said(text: String): String =
        if ("You " !in text) text else YOU_VERB.replace(text) { m -> "You " + (base(m.groupValues[1]) ?: m.groupValues[1]) }

    /** The plain form of a third-person verb (`chooses` → `choose`, `searches` → `search`), or null when [word] isn't one (`pass`, `may`). */
    internal fun base(word: String): String? = IRREGULAR[word] ?: when {
        word.length < 3 || !word.endsWith("s") || word.endsWith("ss") || word.endsWith("us") -> null
        word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
        ES_ENDINGS.any { word.endsWith(it) } -> word.dropLast(2)
        else -> word.dropLast(1)
    }
}
