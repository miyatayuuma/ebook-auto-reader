package io.github.ebookautoreader.reader

// Candidates only: truncated titles, missing numbers and ambiguous layouts are not identity proof.
internal data class HeaderObservation(val number: Int?, val titleCandidate: String?)

internal object HeaderParser {
    private val prefix = Regex("^\\s*[0-9０-９]{1,4}(?![0-9０-９])")
    fun parse(lines: List<String>): HeaderObservation {
        val first = lines.firstOrNull() ?: return HeaderObservation(null, null)
        val number = PageNumberParser.parse(first) ?: return HeaderObservation(null, null)
        val title = (listOf(first.replaceFirst(prefix, "")) + lines.drop(1))
            .joinToString(" ").trim().takeIf { it.isNotBlank() }
        return HeaderObservation(number, title)
    }

    fun normalizedTitle(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
            .filterNot { it.isWhitespace() }
}
