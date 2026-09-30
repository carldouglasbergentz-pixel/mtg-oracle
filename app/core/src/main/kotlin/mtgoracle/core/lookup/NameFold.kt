package mtgoracle.core.lookup

import java.text.Normalizer

/**
 * A card name as loosely typed input should match it: ligatures spelled out,
 * quotes and apostrophes dropped, diacritics stripped, lower case. So
 * "lim-duls vault" is Lim-Dûl's Vault and "kongming, sleeping dragon" is
 * Kongming, "Sleeping Dragon". Hyphens and commas stay. (queries._ascii_fold)
 */
object NameFold {
    // What NFKD does not decompose.
    private val LIGATURES = mapOf(
        'Æ' to "AE", 'æ' to "ae", 'Œ' to "OE", 'œ' to "oe", 'ß' to "ss",
        'Þ' to "Th", 'þ' to "th", 'Ð' to "D", 'ð' to "d", 'Ø' to "O", 'ø' to "o",
    )
    private const val QUOTES = "'’‘`\"“”"
    private val COMBINING = Regex("\\p{M}+")

    fun fold(s: String): String {
        if (s.isEmpty()) return ""
        val spelled = buildString(s.length) {
            for (ch in s) {
                if (ch in QUOTES) continue
                append(LIGATURES[ch] ?: ch)
            }
        }
        return COMBINING.replace(Normalizer.normalize(spelled, Normalizer.Form.NFKD), "").lowercase()
    }
}
