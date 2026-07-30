package com.tv2000.app.scanner

import java.text.Normalizer
import java.util.Locale

/**
 * Locale-independent natural ordering for channel and episode names.
 *
 * Numeric runs are compared without converting them to a fixed-width integer,
 * so very large episode numbers remain safe.
 */
object NaturalOrderComparator : Comparator<String> {
    override fun compare(left: String, right: String): Int {
        val normalizedLeft = Normalizer.normalize(left, Normalizer.Form.NFKC)
        val normalizedRight = Normalizer.normalize(right, Normalizer.Form.NFKC)
        val leftTokens = tokenize(normalizedLeft)
        val rightTokens = tokenize(normalizedRight)

        for (index in 0 until minOf(leftTokens.size, rightTokens.size)) {
            val result = compareToken(leftTokens[index], rightTokens[index])
            if (result != 0) return result
        }

        val tokenCountResult = leftTokens.size.compareTo(rightTokens.size)
        if (tokenCountResult != 0) return tokenCountResult

        val normalizedResult = normalizedLeft.compareTo(
            normalizedRight,
            ignoreCase = true,
        )
        if (normalizedResult != 0) return normalizedResult

        return normalizedLeft.compareTo(normalizedRight)
    }

    private fun compareToken(left: Token, right: Token): Int {
        return when {
            left is Token.Number && right is Token.Number -> compareNumber(left.value, right.value)
            left is Token.Text && right is Token.Text -> {
                val foldedResult = left.value.lowercase(Locale.ROOT)
                    .compareTo(right.value.lowercase(Locale.ROOT))
                if (foldedResult != 0) foldedResult else left.value.compareTo(right.value)
            }
            else -> left.raw.compareTo(right.raw, ignoreCase = true)
        }
    }

    private fun compareNumber(left: String, right: String): Int {
        val significantLeft = left.trimStart('0').ifEmpty { "0" }
        val significantRight = right.trimStart('0').ifEmpty { "0" }

        val digitCountResult = significantLeft.length.compareTo(significantRight.length)
        if (digitCountResult != 0) return digitCountResult

        val valueResult = significantLeft.compareTo(significantRight)
        if (valueResult != 0) return valueResult

        return left.length.compareTo(right.length)
    }

    private fun tokenize(value: String): List<Token> {
        if (value.isEmpty()) return emptyList()

        val result = mutableListOf<Token>()
        var start = 0
        var digits = value[0].isDigit()

        for (index in 1 until value.length) {
            val nextDigits = value[index].isDigit()
            if (nextDigits != digits) {
                result += token(value.substring(start, index), digits)
                start = index
                digits = nextDigits
            }
        }

        result += token(value.substring(start), digits)
        return result
    }

    private fun token(value: String, digits: Boolean): Token =
        if (digits) Token.Number(value) else Token.Text(value)

    private sealed interface Token {
        val raw: String

        data class Number(val value: String) : Token {
            override val raw: String = value
        }

        data class Text(val value: String) : Token {
            override val raw: String = value
        }
    }
}
