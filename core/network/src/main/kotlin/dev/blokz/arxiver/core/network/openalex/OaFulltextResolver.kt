package dev.blokz.arxiver.core.network.openalex

import dev.blokz.arxiver.core.model.ExternalRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.normalizeDoi
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.Normalizer

/**
 * Pure, deterministic picker for the **open-access published-version resolver** (P-OA). Given one host-free
 * OpenAlex title-search response ([OpenAlexClient.search] with `sourceId = null`), it returns the best FREE
 * fulltext URL for a preprint — preferring the *published* version-of-record — or `null` for a clean miss.
 *
 * The design bias is **a miss beats a wrong hit**: opening an unrelated paper's PDF is the worst outcome for a
 * research tool, so the crosswalk tier is guarded on type + version + open-access + title + first-author, and
 * anything retracted or correction-shaped is hard-rejected. All identity comparisons use a resolver-local
 * [baseDoiKey] (strips a `.vN` *and* a `/vN` preprint version tail) so a Research-Square `/v1` DOI matches its
 * unversioned form — WITHOUT touching the shared [normalizeDoi] that feeds the stored `doi_norm` de-dup column.
 *
 * No IO, no Android, no coroutines — trivially unit-testable ([OaFulltextResolverTest]).
 */
object OaFulltextResolver {
    /** A resolved free fulltext. [versionOfRecord] false ⇒ the paper's own OA PDF (label "free", not "published"). */
    data class OaMatch(
        val pdfUrl: String,
        val journalName: String?,
        val versionOfRecord: Boolean,
    )

    /** The identity of the paper being resolved, normalized once so [pick] stays a pure function over primitives. */
    data class Query(
        val baseDoi: String?,
        val openAlexId: String?,
        val normTitle: String,
        val titleWordCount: Int,
        val firstSurname: String?,
        val canonicalHost: String?,
    )

    /** Build a [Query] from a domain [Paper] (the seam the repository calls before [pick]). */
    fun queryFor(paper: Paper): Query {
        val norm = normalizeTitle(paper.title)
        return Query(
            baseDoi = baseDoiKey(paper.doi),
            // A DOI-less source (PsyArXiv) keys under its OpenAlex `W…` id; a DOI'd source's nativeId is the DOI,
            // so this stays null there and self-identity falls to baseDoi — exactly one anchor is always present.
            openAlexId = (paper.ref as? ExternalRef)?.nativeId?.takeIf { it.startsWith("W") },
            normTitle = norm,
            titleWordCount = if (norm.isBlank()) 0 else norm.split(' ').count { it.isNotEmpty() },
            firstSurname = surnameOf(paper.authors.firstOrNull()),
            canonicalHost = paper.canonicalUrl().toHttpUrlOrNull()?.host,
        )
    }

    /**
     * The three-tier pick over (at most [cap]) results.
     *  - **Tier A** — merged version-of-record: a result that IS our work (DOI identity) but carries a
     *    `publishedVersion` OA PDF. Zero collision risk — no title/author matching needed.
     *  - **Tier B** — unmerged crosswalk: a *different* `article` whose title AND first-author match ours and
     *    which exposes a `publishedVersion` OA PDF. The only risky tier; guarded hard.
     *  - **Tier C** — the paper's OWN genuinely-open PDF, when no published version is found and the PDF lives at
     *    a host the existing "Open in browser" affordance doesn't already reach (echo-suppression).
     */
    fun pick(
        query: Query,
        works: List<OpenAlexWork>,
        cap: Int = DEFAULT_CAP,
    ): OaMatch? {
        // Retracted / correction-shaped works are never a fulltext, in any tier.
        val candidates = works.take(cap).filterNot { it.isDisqualified() }

        // Tier A — merged version-of-record by DOI identity.
        query.baseDoi?.let { ours ->
            candidates.firstOrNull { baseDoiKey(it.bareDoi()) == ours && it.publishedVersionOaPdf() != null }
                ?.let { return OaMatch(it.publishedVersionOaPdf()!!, it.journalName(), versionOfRecord = true) }
        }

        // Tier B — unmerged crosswalk to the published sibling.
        val siblings =
            candidates.filter { c ->
                !c.isSelf(query) &&
                    c.type == TYPE_ARTICLE &&
                    c.openAccess?.isOa == true &&
                    c.publishedVersionOaPdf() != null &&
                    c.titleMatches(query) &&
                    c.authorMatches(query)
            }
        // Highest citation count wins; ties broken by lowest id — both deterministic, so the pick never flickers.
        siblings.minWithOrNull(
            compareByDescending<OpenAlexWork> { it.citedByCount ?: 0 }.thenBy { it.openAlexId() ?: it.id ?: "" },
        )?.let { return OaMatch(it.publishedVersionOaPdf()!!, it.journalName(), versionOfRecord = true) }

        // Tier C — the paper's own OA PDF (labeled "free", never "published"), echo-suppressed.
        candidates.firstOrNull { it.isSelf(query) }?.let { self ->
            val pdf = self.oaPdfUrl()
            if (self.openAccess?.isOa == true && !pdf.isNullOrBlank() && hostOf(pdf) != query.canonicalHost) {
                return OaMatch(pdf, journalName = null, versionOfRecord = false)
            }
        }
        return null
    }

    // ---- candidate predicates (private extensions keep pick() readable) ----

    private fun OpenAlexWork.isDisqualified(): Boolean {
        if (isRetracted == true) return true
        val t = type?.lowercase()
        if (t != null && t in DISQUALIFIED_TYPES) return true
        return CORRECTION_TITLE.containsMatchIn(normalizeTitle(title.orEmpty()))
    }

    private fun OpenAlexWork.isSelf(query: Query): Boolean {
        val doi = baseDoiKey(bareDoi())
        if (query.baseDoi != null && doi == query.baseDoi) return true
        return query.openAlexId != null && openAlexId() == query.openAlexId
    }

    private fun OpenAlexWork.titleMatches(query: Query): Boolean {
        val t = OaFulltextResolver.normalizeTitle(title.orEmpty())
        if (t.isBlank()) return false
        return if (query.firstSurname != null) {
            t == query.normTitle || levenshteinRatio(t, query.normTitle) >= FUZZY_TITLE_THRESHOLD
        } else {
            // No author anchor (SSRN / author-stripped RS): demand an EXACT, distinctive title to avoid a
            // title-collision false positive — a short generic title ("Editorial") could collide otherwise.
            t == query.normTitle && query.titleWordCount >= MIN_AUTHORLESS_TITLE_WORDS
        }
    }

    private fun OpenAlexWork.authorMatches(query: Query): Boolean =
        query.firstSurname == null || surnameOf(firstAuthorName()) == query.firstSurname

    /** The `publishedVersion` OA PDF of this work (best_oa_location first, then any location), else null. */
    internal fun OpenAlexWork.publishedVersionOaPdf(): String? {
        bestOaLocation?.let { if (it.isPublishedOaPdf()) return it.pdfUrl }
        return locations.firstOrNull { it.isPublishedOaPdf() }?.pdfUrl
    }

    private fun OpenAlexLocation.isPublishedOaPdf(): Boolean =
        version == VERSION_PUBLISHED && isOa == true && !pdfUrl.isNullOrBlank()

    private fun OpenAlexWork.journalName(): String? =
        (primaryLocation?.source?.displayName ?: bestOaLocation?.source?.displayName)?.takeIf { it.isNotBlank() }

    // ---- pure string helpers (internal so tests can exercise them directly) ----

    /** NFKD + diacritic-strip + lowercase + collapse every non-alphanumeric run to a single space. */
    internal fun normalizeTitle(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .lowercase()
            .replace(NON_ALNUM, " ")
            .trim()

    /** Last whitespace token of a diacritic-folded, lowercased name — the author-guard anchor; null if blank. */
    internal fun surnameOf(name: String?): String? {
        val folded =
            name
                ?.let { Normalizer.normalize(it, Normalizer.Form.NFKD).replace(COMBINING_MARKS, "").lowercase().trim() }
                ?.takeIf { it.isNotBlank() } ?: return null
        return folded.split(WHITESPACE).lastOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * A resolver-local base DOI key: reuse [normalizeDoi] (prefix strip + lowercase + `.vN` strip) then also drop
     * a `/vN` tail (Research Square versions with a slash, which the shared de-dup key deliberately does not — see
     * the ROADMAP backlog note on migrating `doi_norm`). Kept LOCAL so this never perturbs the stored `doi_norm`.
     */
    internal fun baseDoiKey(doi: String?): String? =
        normalizeDoi(doi)?.let { SLASH_VERSION_TAIL.replace(it, "") }?.takeIf { it.isNotBlank() }

    /** Normalized Levenshtein similarity in [0,1]; inputs length-capped so a pathological long title stays cheap. */
    internal fun levenshteinRatio(
        a: String,
        b: String,
    ): Double {
        val x = if (a.length > MAX_TITLE_LEN) a.substring(0, MAX_TITLE_LEN) else a
        val y = if (b.length > MAX_TITLE_LEN) b.substring(0, MAX_TITLE_LEN) else b
        if (x.isEmpty() && y.isEmpty()) return 1.0
        val maxLen = maxOf(x.length, y.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(x, y).toDouble() / maxLen
    }

    private fun levenshtein(
        a: String,
        b: String,
    ): Int {
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    private fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host

    const val DEFAULT_CAP = 8
    private const val TYPE_ARTICLE = "article"
    private const val VERSION_PUBLISHED = "publishedVersion"
    private const val FUZZY_TITLE_THRESHOLD = 0.94
    private const val MIN_AUTHORLESS_TITLE_WORDS = 5
    private const val MAX_TITLE_LEN = 300

    // Work types that are never a paper's fulltext even when typed loosely by the upstream index.
    private val DISQUALIFIED_TYPES =
        setOf("retraction", "erratum", "correction", "editorial", "letter", "paratext", "peer-review")
    private val CORRECTION_TITLE = Regex("^(author |publisher )?(correction|erratum|retraction)\\b")
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("\\s+")
    private val SLASH_VERSION_TAIL = Regex("/v\\d+$")
}
