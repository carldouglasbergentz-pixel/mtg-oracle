package mtgoracle.ui.lookup

import mtgoracle.core.lookup.Rule

private const val INDENT = "  "

/** `rule <number>`: the rule, and its children, each of which opens on a click. (renderer.render_rule) */
fun renderRule(rule: Rule): Rendering = Rendering { width ->
    Lines(width).apply {
        add("[${rule.number}]" + (rule.sectionTitle?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""), Tone.BOLD)
        wrap(rule.text)
        if (rule.children.isNotEmpty()) {
            section("Child rules:")
            rule.children.forEach { ruleEntry(it, null) }
        }
    }.out
}

/** `search-rules <text>`: the hits in natural order, each one opens the rule with its children. */
fun renderRulesSearch(pattern: String, rules: List<Rule>): Rendering = Rendering { width ->
    Lines(width).apply {
        if (rules.isEmpty()) { add("(no rules matching '$pattern')", Tone.DIM); return@apply }
        add("${rules.size} rule(s) matching '$pattern':", Tone.BOLD)
        rules.forEach { ruleEntry(it.copy(text = it.text.take(300)), it.sectionTitle ?: "-") }
    }.out
}

private fun Lines.ruleEntry(rule: Rule, section: String?) {
    val label = "[${rule.number}]"
    add("$INDENT$label" + (section?.let { " ($it)" } ?: ""), Tone.DIM,
        spans = listOf(LinkSpan(INDENT.length, INDENT.length + label.length, OutputLink.Rule(rule.number))))
    wrap(rule.text, INDENT + INDENT)
}
