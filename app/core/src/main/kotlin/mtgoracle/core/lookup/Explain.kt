package mtgoracle.core.lookup

import mtgoracle.core.lookup.SearchNode.And
import mtgoracle.core.lookup.SearchNode.Free
import mtgoracle.core.lookup.SearchNode.Not
import mtgoracle.core.lookup.SearchNode.Or
import mtgoracle.core.lookup.SearchNode.Term

/**
 * A query read back in plain words, for the line under the command line:
 * `t:instant c:u mv<=2 counter` is `type has "instant" · colours include U ·
 * mana value ≤ 2 · "counter" in name, type or text`. It explains what was
 * parsed, so a query that means something else than intended shows it
 * before it runs. Field meaning is the compiler's; unknown fields read as
 * written and the compiler says what is wrong.
 */
object Explain {

    fun query(q: SearchQuery): String {
        val parts = listOfNotNull(
            q.where?.let { node(it, top = true) },
            q.order.takeIf { it.isNotEmpty() }?.joinToString(", ", "sorted by ") { "${SORT_NAMES[it.field] ?: it.field} ${if (it.descending) "↓" else "↑"}" },
        )
        return parts.joinToString(" · ").ifEmpty { "every card" }
    }

    private fun node(n: SearchNode, top: Boolean = false): String = when (n) {
        is Free -> "\"${n.value}\" in name, type or text"
        is Term -> term(n)
        is Not -> "not ${node(n.expr).let { if (n.expr is Term || n.expr is Free) it else "($it)" }}"
        is And -> n.parts.joinToString(if (top) " · " else " and ") { p -> node(p).let { if (p is Or) "($it)" else it } }
        is Or -> n.parts.joinToString(" or ") { p -> node(p).let { if (p is And) "($it)" else it } }
    }

    private fun term(t: Term): String {
        val v = t.value
        val op = OPS[t.op] ?: t.op
        return when (t.field.lowercase()) {
            "o", "oracle" -> "text has \"$v\""
            "t", "type" -> "type has \"$v\""
            "n", "name" -> if (t.op == "=") "named \"$v\"" else "name has \"$v\""
            "kw", "keyword" -> "keyword $v"
            "c", "color" -> when {
                v.lowercase() in setOf("m", "multicolor", "multicolored") -> "multicoloured"
                v.lowercase() in setOf("c", "colorless") -> "colourless"
                t.op == "=" -> "colours exactly ${v.uppercase()}"
                else -> "colours include ${v.uppercase()}"
            }
            "ci", "id", "coloridentity", "color_identity" -> when (t.op) {
                "=" -> "identity exactly ${v.uppercase()}"
                ">=" -> "identity includes ${v.uppercase()}"
                else -> "identity within ${v.uppercase()}"
            }
            "mv", "cmc" -> "mana value $op $v"
            "pow", "power" -> "power $op $v"
            "tou", "toughness" -> "toughness $op $v"
            "r", "rarity" -> v.lowercase()
            "layout" -> "layout $v"
            "f", "format", "legal" -> "legal in $v"
            "banned" -> "banned in $v"
            "restricted" -> "restricted in $v"
            "game" -> "on $v"
            "is" -> IS_NAMES[v.lowercase()] ?: "is $v"
            "m", "mana" -> if (t.op == "=") "cost exactly $v" else "cost has $v"
            "otag", "function", "oracletag" -> "function: $v"
            else -> "${t.field}${t.op}$v"
        }
    }

    private val OPS = mapOf(":" to "=", "=" to "=", "!=" to "≠", ">" to ">", "<" to "<", ">=" to "≥", "<=" to "≤")
    private val SORT_NAMES = mapOf("mv" to "mana value", "cmc" to "mana value", "pow" to "power", "tou" to "toughness", "ci" to "identity size", "edhrec" to "popularity")
    private val IS_NAMES = mapOf(
        "commander" to "can be a commander", "permanent" to "a permanent", "spell" to "a spell (not a land)",
        "historic" to "historic", "dfc" to "double-faced", "mdfc" to "modal double-faced", "split" to "a split card",
        "reserved" to "on the Reserved List",
    )
}

/** The candidate closest to [word], if it is close enough to be a typo: one edit in a short word, two otherwise. */
fun closest(word: String, candidates: Collection<String>): String? {
    val w = word.lowercase()
    val limit = if (w.length <= 3) 1 else 2
    return candidates.map { it to editDistance(w, it.lowercase()) }.filter { it.second in 1..limit }.minByOrNull { it.second }?.first
}

private fun editDistance(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        prev = cur
    }
    return prev[b.length]
}
