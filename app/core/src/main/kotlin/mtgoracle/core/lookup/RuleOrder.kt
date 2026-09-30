package mtgoracle.core.lookup

/**
 * Comprehensive Rules numbers in natural order: 702.2 < 702.9 < 702.10 <
 * 702.10a. Text order puts 702.10 before 702.2. Digit runs compare as
 * numbers and sort before letter runs, as queries.rule_sort_key does.
 */
object RuleOrder : Comparator<String> {
    private val PART = Regex("[0-9]+|[A-Za-z]+")

    override fun compare(a: String, b: String): Int {
        val left = PART.findAll(a).map { it.value }.toList()
        val right = PART.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val c = comparePart(left[i], right[i])
            if (c != 0) return c
        }
        return left.size.compareTo(right.size)
    }

    private fun comparePart(x: String, y: String): Int {
        val xNum = x[0].isDigit()
        val yNum = y[0].isDigit()
        return when {
            xNum && yNum -> x.toBigInteger().compareTo(y.toBigInteger())
            xNum -> -1
            yNum -> 1
            else -> x.lowercase().compareTo(y.lowercase())
        }
    }
}
