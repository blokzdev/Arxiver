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

    /**
     * **Curation rule (P-Explorer PE.1, empirical):** an OpenAlex Field is offered for a source iff it carries
     * **≥1% of that source's works published since 2024-01-01**, measured live against
     * `api.openalex.org/works?filter=primary_location.source.id:<SID>,from_publication_date:2024-01-01&group_by=primary_topic.field.id`
     * (captured 2026-07-10; the percentages below are that measurement). Curated lists are declared in **descending
     * real mass**, so the source's dominant discipline leads the picker — *not* alphabetically.
     *
     * The two **megajournals** deliberately keep all 26: their distributions are genuinely flat (top-10 covers only
     * ~80–84%, every Field carries >0.15% of works), so a subset would *hide real content* rather than remove noise.
     */
    val pickable: List<PreprintSourceInfo> =
        listOf(
            PreprintSourceInfo(Source.BIORXIV, bioMed(BIORXIV_CATEGORIES), allowsWholeSource = true),
            PreprintSourceInfo(Source.MEDRXIV, bioMed(MEDRXIV_CATEGORIES), allowsWholeSource = true),
            // chemRxiv — 95.9% cumulative. Chemistry 24.3 · Materials 19.4 · Engineering 15.5 · Biochem 11.2 ·
            // Physics 5.0 · CompSci 4.7 · Medicine 3.9 · Energy 3.8 · EnvSci 3.4 · ChemEng 3.2 · Pharmacology 1.6.
            // Cut as noise: Dentistry 0.04%, Arts & Humanities 0.10%, Veterinary 0.02% — the incoherence reported
            // from the device. CompSci + Medicine are NOT noise (comp-chem, med-chem) and were nearly missed.
            PreprintSourceInfo(
                Source.CHEMRXIV,
                fields(16, 25, 22, 13, 31, 17, 27, 21, 23, 15, 30),
                allowsWholeSource = true,
            ),
            // Research Square (Springer Nature) — genuine all-discipline megajournal. NOT curated.
            PreprintSourceInfo(Source.RESEARCH_SQUARE, allFields(), allowsWholeSource = true),
            // SSRN — 96.2% cumulative. **Engineering (21.9%) is the #1 recent field**, not law/finance: the
            // post-Elsevier "Preprints with SSRN" expansion made it a broad STEM firehose. A {Econ, Business,
            // Social, Decision} subset — the intuitive one — covers a mere 27.9% and would hide most of the source.
            // (OpenAlex has no "Law" Field; legal scholarship rolls into Social Sciences. Honest, not native.)
            PreprintSourceInfo(
                Source.SSRN,
                fields(22, 33, 20, 23, 17, 14, 25, 27, 11, 13, 31, 21, 16, 32, 18, 19, 12, 36),
                allowsWholeSource = true,
            ),
            // Preprints.org (MDPI) — broad portfolio; same megajournal reasoning as Research Square. NOT curated.
            PreprintSourceInfo(Source.PREPRINTS_ORG, allFields(), allowsWholeSource = true),
            // PsyArXiv — 97.5% cumulative. Psychology 40.3 · Neuroscience 20.9 · Social Sciences 14.1 ·
            // **Medicine 9.0** (clinical psych/psychiatry — the one real omission in the intuitive 3-field set) ·
            // CompSci 3.7 · Decision Sciences 3.6 · Maths 1.8 · Health Professions 1.5 · Arts 1.5 · EnvSci 1.2.
            PreprintSourceInfo(
                Source.PSYARXIV,
                fields(32, 28, 33, 27, 17, 18, 26, 36, 12, 23),
                allowsWholeSource = true,
            ),
        )

    fun infoFor(source: Source): PreprintSourceInfo? = pickable.firstOrNull { it.source == source }

    /** bio/med native categories: value = the server string, label = title-cased for display. */
    private fun bioMed(categories: List<String>): List<FollowCategoryOption> =
        categories.map { FollowCategoryOption(value = it, label = titleCase(it)) }

    /**
     * A curated subset, **declared in descending real mass** and rendered in that order. Fails LOUD on an unknown
     * id — a transposed digit must break the build, never degrade to a silently-empty follow (a bad Field id
     * returns HTTP 200 / count 0).
     */
    private fun fields(vararg ids: Int): List<FollowCategoryOption> =
        ids.map { id ->
            val token = "fields/$id"
            val field = OPENALEX_FIELDS.firstOrNull { it.token == token } ?: error("unknown OpenAlex Field id: $id")
            FollowCategoryOption(value = field.token, label = field.displayName)
        }

    /** All 26 Fields, alphabetical — for the genuinely multidisciplinary megajournals (no meaningful mass order). */
    private fun allFields(): List<FollowCategoryOption> =
        OPENALEX_FIELDS.sortedBy { it.displayName }
            .map { FollowCategoryOption(value = it.token, label = it.displayName) }

    private fun titleCase(s: String): String =
        s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
}
