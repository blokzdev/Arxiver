package dev.blokz.arxiver.core.network

import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.biorxiv.BIORXIV_CATEGORIES
import dev.blokz.arxiver.core.network.biorxiv.MEDRXIV_CATEGORIES
import dev.blokz.arxiver.core.network.openalex.OPENALEX_FIELDS

/**
 * One category a follow can subscribe to. [value] is stored verbatim in `follows.value` and handed to the
 * backend as its category arg (a bio/med server string like `"neuroscience"`, or an OpenAlex Field token like
 * `"fields/16"`). [label] is the human display. The **whole-source** follow (no category filter) is NOT in this
 * list — it is a `value = ""` follow the picker offers separately when [PreprintSourceInfo.allowsWholeSource].
 */
data class FollowCategoryOption(
    val value: String,
    val label: String,
)

/** A source the follow-picker can offer (P-Feeds PF.3): its category vocabulary + whether whole-source is allowed. */
data class PreprintSourceInfo(
    val source: Source,
    val categories: List<FollowCategoryOption>,
    val allowsWholeSource: Boolean,
)

/**
 * The single source of truth for which non-arXiv sources the follow-picker offers and with what category
 * vocabulary (P-Feeds PF.3) — so the source list isn't scattered across `Source`, `backendFor`, `sidFor`, and
 * the picker. All vocab is COMPILE-TIME (the OpenAlex metering red line: a picker never fetches `/fields` or a
 * categories endpoint to populate itself). arXiv keeps its own native taxonomy grid; S2 is not a followable feed.
 */
object PreprintSourceRegistry {
    /** The whole-source (no category filter) follow value — flows through the backends' non-blank category guards. */
    const val WHOLE_SOURCE_VALUE = ""

    val pickable: List<PreprintSourceInfo> =
        listOf(
            PreprintSourceInfo(Source.BIORXIV, bioMed(BIORXIV_CATEGORIES), allowsWholeSource = true),
            PreprintSourceInfo(Source.MEDRXIV, bioMed(MEDRXIV_CATEGORIES), allowsWholeSource = true),
            PreprintSourceInfo(Source.CHEMRXIV, openAlexFields(), allowsWholeSource = true),
            PreprintSourceInfo(Source.RESEARCH_SQUARE, openAlexFields(), allowsWholeSource = true),
            PreprintSourceInfo(Source.SSRN, openAlexFields(), allowsWholeSource = true),
            PreprintSourceInfo(Source.PREPRINTS_ORG, openAlexFields(), allowsWholeSource = true),
            PreprintSourceInfo(Source.PSYARXIV, openAlexFields(), allowsWholeSource = true),
        )

    fun infoFor(source: Source): PreprintSourceInfo? = pickable.firstOrNull { it.source == source }

    /** bio/med native categories: value = the server string, label = title-cased for display. */
    private fun bioMed(categories: List<String>): List<FollowCategoryOption> =
        categories.map { FollowCategoryOption(value = it, label = titleCase(it)) }

    private fun openAlexFields(): List<FollowCategoryOption> =
        OPENALEX_FIELDS.sortedBy { it.displayName }
            .map { FollowCategoryOption(value = it.token, label = it.displayName) }

    private fun titleCase(s: String): String =
        s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
}
