package mtgoracle.core.lookup

/*
 * The Scryfall-style search language, up to the syntax tree: tokenizer,
 * parser and the `order:` tokens. What each field means in SQL is the data
 * layer's (data/SearchSql.kt). A port of mtg_oracle/scryfall_search.py; its
 * tests (tests/test_search_language.py) moved with it.
 */

/** A query the user can fix: bad syntax, an unknown field, a misused operator. */
class SearchError(message: String) : IllegalArgumentException(message)

sealed interface SearchNode {
    data class Term(val field: String, val op: String, val value: String) : SearchNode
    /** A bare word or quoted phrase: the name, the type line or the oracle text. */
    data class Free(val value: String) : SearchNode
    data class Not(val expr: SearchNode) : SearchNode
    data class And(val parts: List<SearchNode>) : SearchNode
    data class Or(val parts: List<SearchNode>) : SearchNode
}

data class SortKey(val field: String, val descending: Boolean)

/**
 * A parsed query: the filter (null matches every card, as a query of only
 * `order:` tokens does) and the sort keys, in the order typed.
 */
data class SearchQuery(val where: SearchNode?, val order: List<SortKey> = emptyList()) {
    /**
     * This query restricted by [extra] as well. Structural, so a deck's
     * filters can't hide a sort token the way wrapping the text in
     * parentheses did in Python (`(t:x order:asc_mv)`).
     */
    fun and(extra: SearchNode): SearchQuery = copy(where = where?.let { SearchNode.And(listOf(it, extra)) } ?: extra)
}

object SearchLanguage {

    /**
     * The free text [node] asks FOR, in order — not what is under a NOT:
     * `-bolt` must not rank Lightning Bolt first. (scryfall_search.free_words)
     */
    fun freeWords(node: SearchNode?): List<String> = when (node) {
        is SearchNode.Free -> listOf(node.value)
        is SearchNode.And -> node.parts.flatMap(::freeWords)
        is SearchNode.Or -> node.parts.flatMap(::freeWords)
        else -> emptyList()
    }

    /** The whole query: sort tokens out first, then the filter. An empty filter matches everything. */
    fun parse(query: String): SearchQuery {
        val (cleaned, order) = extractOrder(query)
        return SearchQuery(if (cleaned.isBlank()) null else parseExpression(cleaned), order)
    }

    /** A filter expression alone; empty is an error here (`empty query`). */
    fun parseExpression(text: String): SearchNode {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) throw SearchError("empty query")
        return Parser(tokens).parse()
    }

    /*
     * `order:asc_mv` / `sort=desc_edhrec`, word-bounded: at the start or after
     * whitespace, and followed by whitespace or the end. Quoted strings match
     * first and pass through, so `o:"x order:asc_mv"` searches for that text.
     */
    private val ORDER_TOKEN = Regex("\"[^\"]*\"|(?:^|(?<=\\s))(?:order|sort)[:=]([a-zA-Z_]+)(?=\\s|$)", RegexOption.IGNORE_CASE)

    /** The query without its sort tokens, and the keys they asked for. The direction is required. */
    fun extractOrder(query: String): Pair<String, List<SortKey>> {
        val keys = mutableListOf<SortKey>()
        val cleaned = ORDER_TOKEN.replace(query) { m ->
            val spec = m.groups[1]?.value?.lowercase() ?: return@replace m.value
            val token = m.value.trim()
            if ('_' !in spec) throw SearchError("sort token must be 'asc_FIELD' or 'desc_FIELD', got '$token'")
            val direction = spec.substringBefore('_')
            val field = spec.substringAfter('_')
            if (direction != "asc" && direction != "desc") {
                throw SearchError("sort direction must be 'asc' or 'desc', got '$direction' in '$token'")
            }
            if (field.isEmpty()) throw SearchError("sort token missing field name: '$token'")
            keys += SortKey(field, descending = direction == "desc")
            " " // keeps the neighbours apart so the rest still tokenizes
        }.trim()
        return cleaned to keys
    }

    internal sealed interface Token {
        data object LParen : Token
        data object RParen : Token
        data object Or : Token
        /** `not` or a `-` directly before a term. */
        data object Not : Token
        data class Term(val field: String, val op: String, val value: String) : Token
        /** A loose word or a quoted string: oracle text. */
        data class Bare(val word: String) : Token
    }

    private const val OP_CHARS = ":=<>!"
    private val LONG_OPS = setOf(">=", "<=", "!=")

    internal fun tokenize(s: String): List<Token> = buildList {
        var i = 0
        val n = s.length
        fun quotedFrom(start: Int): Pair<String, Int> {
            val end = s.indexOf('"', start + 1)
            if (end == -1) throw SearchError("unterminated quoted string at col $start")
            return s.substring(start + 1, end) to end + 1
        }
        while (i < n) {
            val ch = s[i]
            when {
                ch.isWhitespace() -> i++
                ch == '(' -> { add(Token.LParen); i++ }
                ch == ')' -> { add(Token.RParen); i++ }
                ch == '-' -> {
                    // A bare '-' used to search oracle text for a hyphen, silently narrowing `t:goblin -`.
                    if (i + 1 < n && !s[i + 1].isWhitespace() && s[i + 1] != ')') { add(Token.Not); i++ }
                    else throw SearchError("dangling '-' at col $i")
                }
                ch == '"' -> { val (text, next) = quotedFrom(i); add(Token.Bare(text)); i = next }
                else -> {
                    var j = i
                    while (j < n && !s[j].isWhitespace() && s[j] !in "()\"" && s[j] !in OP_CHARS) j++
                    val ident = s.substring(i, j)
                    val op = when {
                        j >= n || s[j] !in OP_CHARS -> null
                        else -> LONG_OPS.firstOrNull { s.startsWith(it, j) } ?: s[j].toString()
                    }
                    if (op == null) {
                        i = j
                        when (ident.lowercase()) {
                            "or" -> add(Token.Or)
                            "not" -> add(Token.Not)
                            "and" -> {} // juxtaposition already is AND; as a bareword it became `o:and`
                            else -> add(Token.Bare(ident))
                        }
                    } else {
                        j += op.length
                        val value: String
                        if (j < n && s[j] == '"') {
                            val (text, next) = quotedFrom(j)
                            value = text; i = next
                        } else {
                            var k = j
                            while (k < n && !s[k].isWhitespace() && s[k] != ')') k++
                            value = s.substring(j, k); i = k
                        }
                        add(Token.Term(ident, op, value))
                    }
                }
            }
        }
    }

    /** OR binds loosest, then AND (juxtaposition), then NOT / `-`. */
    private class Parser(private val tokens: List<Token>) {
        private var pos = 0
        private fun peek(): Token? = tokens.getOrNull(pos)

        // Each `(` and `-` is a level of recursion here and of nesting in the SQL: a pasted wall of them
        // overflowed the stack, and SQLite refuses an expression past 1000 deep anyway.
        private var depth = 0
        private inline fun <T> nested(parse: () -> T): T {
            if (++depth > MAX_DEPTH) throw SearchError("the query nests deeper than $MAX_DEPTH levels of '(' and '-'")
            try { return parse() } finally { depth-- }
        }

        fun parse(): SearchNode {
            val ast = or()
            peek()?.let { throw SearchError("unexpected token after query: ${describe(it)}") }
            return ast
        }

        private fun or(): SearchNode {
            val parts = mutableListOf(and())
            while (peek() == Token.Or) { pos++; parts += and() }
            return parts.singleOrNull() ?: SearchNode.Or(parts)
        }

        private fun and(): SearchNode {
            val parts = mutableListOf<SearchNode>()
            while (true) {
                val t = peek()
                if (t == null || t == Token.Or || t == Token.RParen) break
                parts += not()
            }
            if (parts.isEmpty()) throw SearchError("empty expression")
            return parts.singleOrNull() ?: SearchNode.And(parts)
        }

        private fun not(): SearchNode {
            if (peek() == Token.Not) { pos++; return nested { SearchNode.Not(not()) } }
            return primary()
        }

        private fun primary(): SearchNode = when (val t = peek()) {
            Token.LParen -> nested {
                pos++
                val inner = or()
                if (peek() != Token.RParen) throw SearchError("expected ')'")
                pos++
                inner
            }
            is Token.Term -> { pos++; SearchNode.Term(t.field, t.op, t.value) }
            // Free text, as on Scryfall and Moxfield, but wider: `goblin` finds every Goblin.
            is Token.Bare -> { pos++; SearchNode.Free(t.word) }
            else -> throw SearchError("unexpected token: ${t?.let(::describe) ?: "end of query"}")
        }

        private companion object { const val MAX_DEPTH = 100 }

        private fun describe(t: Token): String = when (t) {
            Token.LParen -> "'('"
            Token.RParen -> "')'"
            Token.Or -> "'or'"
            Token.Not -> "'not'"
            is Token.Term -> "'${t.field}${t.op}${t.value}'"
            is Token.Bare -> "'${t.word}'"
        }
    }
}
