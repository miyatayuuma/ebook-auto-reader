package io.github.ebookautoreader.reader

internal enum class NavigationDecision { WAIT, CHANGED, UNCHANGED, CONFLICT }
internal data class NavigationResult(val decision: NavigationDecision, val number: Int?, val stable: Int)

/** Pure evidence accumulator. Number is sampled once per distinct stable candidate.
 * Body signatures alone never establish a transition after interruption.
 */
internal class NavigationEvidence(
    private val origin: PageSignature, private val originHeader: PageSignature,
    private val originNumber: Int?, forward: Boolean,
) {
    private val expected = originNumber?.plus(if (forward) 1 else -1)
    private var candidate: PageSignature? = null
    private var header: PageSignature? = null
    private var number: Int? = null
    private var stable = 0
    private var leftOrigin = false
    private var wasInterrupted = false

    private fun sameCandidate(body: PageSignature, band: PageSignature) =
        candidate?.matches(body) == true && header?.identical(band) == true

    private fun atOrigin(body: PageSignature, band: PageSignature) =
        origin.matches(body) && originHeader.identical(band)

    fun needsNumber(body: PageSignature, band: PageSignature) =
        !sameCandidate(body, band) && !atOrigin(body, band)

    fun interrupted() {
        wasInterrupted = true
        candidate = null
        header = null
        number = null
        stable = 0
    }

    fun observe(body: PageSignature, band: PageSignature, sampled: Int?): NavigationResult {
        val original = atOrigin(body, band)
        if (!original) leftOrigin = true
        if (!sameCandidate(body, band)) {
            candidate = body
            header = band
            number = if (original) originNumber else sampled
            stable = 1
        } else stable++
        val relation = origin.compare(body)
        fun result(decision: NavigationDecision) = NavigationResult(decision, number, stable)
        if (stable >= 2 && expected != null && number != null && number != originNumber && number != expected)
            return result(NavigationDecision.CONFLICT)
        if (stable >= 2 && expected != null && number == expected && relation == PageComparison.DIFFERENT)
            return result(NavigationDecision.CHANGED)
        if (stable >= 3 && relation == PageComparison.DIFFERENT && !wasInterrupted)
            return result(NavigationDecision.CHANGED)
        if (stable >= 4 && original && !leftOrigin && !wasInterrupted)
            return result(NavigationDecision.UNCHANGED)
        return result(NavigationDecision.WAIT)
    }
}
