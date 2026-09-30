package mtgoracle.core.analysis

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/*
 * Python's number semantics where the analysis depends on them, so reports
 * match the originals digit for digit. Java's `%.1f` rounds half up and
 * Python rounds half to even on the exact binary value; `sum()` over floats
 * is compensated since Python 3.12; `int / int` is correctly rounded.
 */
object Py {
    /** `round(x, digits)`, which keeps the sign of a negative that rounds to zero (`-0.0`, printed `-0p`). */
    fun round(x: Double, digits: Int): Double {
        val r = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
        return if (r == 0.0 && x < 0) -0.0 else r
    }

    /** `round(x)` to an integer. */
    fun roundInt(x: Double): Int = Math.rint(x).toInt()

    /** `sum(values)` over floats: Neumaier summation, as CPython does. */
    fun sum(values: Iterable<Double>): Double {
        var total = 0.0
        var compensation = 0.0
        for (x in values) {
            val t = total + x
            compensation += if (abs(total) >= abs(x)) (total - t) + x else (x - t) + total
            total = t
        }
        return if (compensation != 0.0 && compensation.isFinite()) total + compensation else total
    }

    /** `a / b` for integers that may exceed a double's exact range. */
    fun ratio(a: BigInteger, b: BigInteger): Double = BigDecimal(a).divide(BigDecimal(b), MathContext(60)).toDouble()

    /** `f"{x:.{digits}f}"`, with `+` when [plus]: the sign survives rounding to zero, as in Python. */
    fun fixed(x: Double, digits: Int, plus: Boolean = false): String {
        val negative = x < 0 || (x == 0.0 && 1.0 / x < 0)
        val body = BigDecimal(abs(x)).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
        return when {
            negative -> "-$body"
            plus -> "+$body"
            else -> body
        }
    }

    /** `repr()` of a string: single quotes unless it holds one and no double quote. */
    fun repr(s: String): String =
        if ('\'' in s && '"' !in s) "\"$s\"" else "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /** `f"{x:g}"` for the small, few-decimal values the reports print. */
    fun general(x: Double): String {
        if (x == 0.0) return "0"
        val text = BigDecimal(x).round(MathContext(6, RoundingMode.HALF_EVEN)).stripTrailingZeros().toPlainString()
        return text
    }
}

/** `f"{s:>w}"` and `f"{s:<w}"`. */
fun String.right(width: Int): String = padStart(width)
fun String.left(width: Int): String = padEnd(width)
