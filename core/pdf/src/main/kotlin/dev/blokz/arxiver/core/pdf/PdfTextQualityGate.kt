package dev.blokz.arxiver.core.pdf

/**
 * Rejects garbage PDF extractions **before** they embed or count toward full-text coverage (Phase P-Reader2,
 * PFT.5.4). Pure + unit-tested. PDF text extraction is noisy in ways HTML isn't — CID run-together (no spaces),
 * ligature/glyph mangling, scanned image-only pages (no real text), and symbol/table dumps — and a poisoned
 * body hurts BM25 + bge more than no body at all. This gate keeps the honest coverage promise: a paper only
 * counts as "full-text covered" when its extracted text is genuinely readable prose.
 *
 * **The primary discriminator is the function-word ratio.** Real English prose is ~25–45% function words
 * (`the`, `of`, `and`, `to`, `in`, …) among its alphabetic tokens — a stable, language-level property; garbage
 * (CID codes, scrambled glyphs, scanned-empty) has almost none. Computed over **alphabetic** tokens only, so a
 * math-heavy paper (equations diluting the raw token stream) is judged on its *prose*, not its symbols — the
 * false-reject risk the plan flags. A bibliography tail is trimmed first so author-name / DOI / arXiv-id soup
 * doesn't drag a legitimate paper under threshold.
 *
 * **Honest limitation (adversarial-validated right-sizing of the plan's "coherence signal"):** two-column
 * *reading-order interleave* produces text that is locally valid English (function words intact) but globally
 * scrambled — no simple, language-model-free heuristic reliably catches it, so this gate does **not** claim to.
 * That failure mode is mitigated instead by the shipped `sortByPosition = false` default (content-stream order,
 * which arXiv pdflatex writes column-major) plus the device A/B ratification (VERIFICATION §P-Reader2 PR2-A2b).
 * The gate here is the backstop for the clearly-broken extractions, which it catches robustly.
 *
 * All thresholds are PROVISIONAL — device-ratifiable against real on-device extraction ratios.
 */
object PdfTextQualityGate {
    /**
     * Bumped only when the extractor/gate *itself* genuinely improves. The `.bodyindex` `SKIP:<gateVersion>`
     * marker (PFT.5.5) uses this so a garbage PDF is re-attempted on a gate improvement, NOT on every
     * embedding-model bump (garbage is model-independent — re-running the same gate would just re-reject).
     */
    const val GATE_VERSION: Int = 1

    /** Fewer real words than this ⇒ scanned / figure-only / empty — nothing worth indexing. */
    internal const val MIN_WORDS = 50

    /** Below this fraction of alphabetic tokens being common function words ⇒ not readable English prose. */
    internal const val MIN_FUNCTION_RATIO = 0.10f

    /** A median alphabetic-token length above this signals CID/no-space run-together ("thequickbrownfox…"). */
    internal const val MAX_MEDIAN_TOKEN_LEN = 14

    /** Below this fraction of the sample being letters/spaces ⇒ a symbol/table/formula dump, not prose. */
    internal const val MIN_ALPHA_SPACE_RATIO = 0.55f

    fun isAcceptable(text: String): Boolean {
        val sample = trimBibliography(text)
        val tokens = alphabeticTokens(sample)
        if (tokens.size < MIN_WORDS) return false
        if (alphaSpaceRatio(sample) < MIN_ALPHA_SPACE_RATIO) return false
        if (medianLength(tokens) > MAX_MEDIAN_TOKEN_LEN) return false
        val functionHits = tokens.count { it.lowercase() in FUNCTION_WORDS }
        return functionHits.toFloat() / tokens.size >= MIN_FUNCTION_RATIO
    }

    /**
     * Drop a trailing references/bibliography section (its name-and-number soup has few function words and would
     * skew the ratio) — but only when the heading sits in the last 45% of the text, so a mid-body mention of the
     * word "references" never truncates a real body.
     */
    internal fun trimBibliography(text: String): String {
        val cut =
            BIBLIOGRAPHY_HEADINGS
                .mapNotNull { h -> text.lastIndexOf(h, ignoreCase = true).takeIf { it >= 0 } }
                .filter { it >= text.length * 0.55 }
                .minOrNull()
        return if (cut != null) text.substring(0, cut) else text
    }

    private fun alphabeticTokens(text: String): List<String> =
        text.split(NON_WORD).filter { it.length > 1 && it.all { c -> c.isLetter() || c == '-' || c == '\'' } }

    private fun medianLength(tokens: List<String>): Int {
        if (tokens.isEmpty()) return 0
        val sorted = tokens.map { it.length }.sorted()
        return sorted[sorted.size / 2]
    }

    private fun alphaSpaceRatio(text: String): Float {
        if (text.isEmpty()) return 0f
        val good = text.count { it.isLetter() || it.isWhitespace() }
        return good.toFloat() / text.length
    }

    private val NON_WORD = Regex("""[^\p{L}'-]+""")

    private val BIBLIOGRAPHY_HEADINGS = listOf("References", "Bibliography", "REFERENCES", "BIBLIOGRAPHY")

    /**
     * ~250 of the most common English function + academic words. Not a spell-check dictionary — a *presence*
     * signal: real prose is dense with these; extraction garbage is not. Kept inline (not a resource file) so it
     * survives the module test classpath without the java_res quirks and adds no asset weight.
     */
    private val FUNCTION_WORDS: Set<String> =
        (
            "the of and a to in is was he for it with as his on be at by i this had not are but from or " +
                "have an they which one you were her all she there would their we him been has when who will more " +
                "no if out so said what up its about into than them can only other new some could time these two may " +
                "then do first any my now such like our over man me even most made after also did many before must " +
                "through back years where much your way well down should because each just those people mr how too " +
                "little state good very make world still see own men work long here get both between under never day " +
                "same another know while last might us great old year off come since against go came right used take " +
                "three states himself few house use during without again place around however home small found " +
                "thought went say part once general upon war left every don't does got united number hand course " +
                // academic / paper-prose common words — keep a real math/ML paper's prose above threshold
                "results result method methods model models data using based show shown propose proposed approach " +
                "section figure table experiments experiment performance training problem function different task " +
                "learning network networks input output algorithm evaluation dataset baseline analysis"
        ).split(' ').filter { it.isNotBlank() }.toSet()
}
