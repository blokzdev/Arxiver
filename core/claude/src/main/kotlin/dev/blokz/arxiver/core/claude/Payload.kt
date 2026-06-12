package dev.blokz.arxiver.core.claude

import dev.blokz.arxiver.core.model.Paper
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** SPEC-CLAUDE-BRIDGE §5 action catalog. */
enum class RoutineAction(val wire: String) {
    DIGEST("digest"),
    DEEP_DIVE("deep_dive"),
    COMPARE("compare"),
    WEEKLY_REVIEW("weekly_review"),
    LITERATURE_SCAN("literature_scan"),
    CUSTOM("custom"),
    PING("ping"),
}

@Serializable
data class PayloadUser(
    val tags: List<String> = emptyList(),
    val status: String? = null,
    val rating: Int? = null,
    val notes: List<String> = emptyList(),
)

@Serializable
data class PayloadPaper(
    @SerialName("arxiv_id") val arxivId: String,
    val version: Int,
    val title: String,
    val authors: List<String>,
    val abstract: String,
    @SerialName("primary_category") val primaryCategory: String,
    val categories: List<String>,
    val published: String,
    val updated: String,
    val doi: String?,
    @SerialName("abs_url") val absUrl: String,
    @SerialName("pdf_url") val pdfUrl: String,
    @SerialName("citation_count") val citationCount: Int?,
    val user: PayloadUser? = null,
)

@Serializable
data class PayloadContext(
    @SerialName("include_notes") val includeNotes: Boolean,
    @SerialName("library_size") val librarySize: Int,
    @SerialName("user_tags_in_selection") val userTagsInSelection: List<String> = emptyList(),
)

/** Pairwise embedding cosine between two selected papers. */
@Serializable
data class PayloadSimilarityEdge(
    val a: String,
    val b: String,
    val cosine: Double,
)

/** Citation edge where both endpoints are in the selection. */
@Serializable
data class PayloadCitationEdge(
    val citing: String,
    val cited: String,
)

/** A semantically near paper from the local corpus, anchored to a selected paper. */
@Serializable
data class PayloadNeighbor(
    @SerialName("arxiv_id") val arxivId: String,
    val near: String,
    val title: String,
    val cosine: Double,
    @SerialName("in_library") val inLibrary: Boolean,
    @SerialName("abs_url") val absUrl: String,
)

/**
 * SPEC-CLAUDE-BRIDGE §4 `relations` block: the device's precomputed analysis
 * primitives (embedding similarity, citation edges, corpus neighbors) shipped
 * alongside the papers so the routine can compose them instead of re-deriving
 * relationships from raw text. Additive and optional — absent when empty.
 */
@Serializable
data class PayloadRelations(
    val similarity: List<PayloadSimilarityEdge> = emptyList(),
    val citations: List<PayloadCitationEdge> = emptyList(),
    // Nullable so redaction leaves the key structurally absent, never [].
    @SerialName("library_neighbors") val libraryNeighbors: List<PayloadNeighbor>? = null,
) {
    fun isEmpty(): Boolean = similarity.isEmpty() && citations.isEmpty() && libraryNeighbors.isNullOrEmpty()
}

/** SPEC-CLAUDE-BRIDGE §4 envelope — `schema` bumps on breaking change. */
@Serializable
data class ArxiverPayload(
    val schema: String = SCHEMA,
    @SerialName("app_version") val appVersion: String,
    val action: String,
    @SerialName("sent_at") val sentAt: String,
    val instruction: String,
    val context: PayloadContext,
    val relations: PayloadRelations? = null,
    val papers: List<PayloadPaper>,
) {
    companion object {
        const val SCHEMA = "arxiver/v1"
    }
}

/** A paper plus its user-side annotations, assembled by the repository layer. */
data class PaperWithAnnotations(
    val paper: Paper,
    val tags: List<String> = emptyList(),
    val status: String? = null,
    val rating: Int? = null,
    val notes: List<String> = emptyList(),
)

sealed interface PayloadResult {
    data class Ready(val json: String, val paperCount: Int) : PayloadResult

    data class TooLarge(val byteSize: Int, val limit: Int) : PayloadResult
}

/**
 * Builds the versioned dispatch payload (SPEC-CLAUDE-BRIDGE §4). Notes/tags
 * ride along only when [includeNotes] is set — redaction is structural: the
 * `user` key is absent entirely, not emptied.
 */
class PayloadBuilder(
    private val appVersion: String,
    private val json: Json =
        Json {
            encodeDefaults = true
            // Redaction must be structural: absent keys, not "user": null.
            explicitNulls = false
        },
    private val now: () -> Instant = Instant::now,
) {
    fun build(
        action: RoutineAction,
        instruction: String,
        papers: List<PaperWithAnnotations>,
        includeNotes: Boolean,
        librarySize: Int,
        relations: PayloadRelations? = null,
    ): PayloadResult {
        val payload =
            ArxiverPayload(
                appVersion = appVersion,
                action = action.wire,
                sentAt = DateTimeFormatter.ISO_INSTANT.format(now().truncatedTo(ChronoUnit.SECONDS)),
                instruction = instruction,
                context =
                    PayloadContext(
                        includeNotes = includeNotes,
                        librarySize = librarySize,
                        userTagsInSelection = papers.flatMap { it.tags }.distinct().takeIf { includeNotes }.orEmpty(),
                    ),
                // Neighbors reveal what's in the local corpus, so they ride the
                // same privacy gate as notes; redaction stays structural.
                relations =
                    relations
                        ?.let { if (includeNotes) it else it.copy(libraryNeighbors = null) }
                        ?.takeUnless { it.isEmpty() },
                papers = papers.map { it.toPayloadPaper(includeNotes) },
            )
        val encoded = json.encodeToString(ArxiverPayload.serializer(), payload)
        val byteSize = encoded.toByteArray(Charsets.UTF_8).size
        return if (byteSize > MAX_PAYLOAD_BYTES) {
            PayloadResult.TooLarge(byteSize = byteSize, limit = MAX_PAYLOAD_BYTES)
        } else {
            PayloadResult.Ready(json = encoded, paperCount = papers.size)
        }
    }

    private fun PaperWithAnnotations.toPayloadPaper(includeNotes: Boolean): PayloadPaper =
        PayloadPaper(
            arxivId = paper.id.value,
            version = paper.latestVersion,
            title = paper.title,
            authors = paper.authors,
            abstract = paper.abstract,
            primaryCategory = paper.primaryCategory,
            categories = paper.categories,
            published = paper.publishedAt.toString(),
            updated = paper.updatedAt.toString(),
            doi = paper.doi,
            absUrl = paper.id.absUrl(paper.latestVersion),
            pdfUrl = paper.pdfUrl,
            citationCount = paper.citationCount,
            user =
                if (includeNotes) {
                    PayloadUser(tags = tags, status = status, rating = rating, notes = notes)
                } else {
                    null
                },
        )

    companion object {
        /** SPEC-CLAUDE-BRIDGE §4 size guard. */
        const val MAX_PAYLOAD_BYTES = 256 * 1024

        /** §5 instruction templates, user-editable before send. */
        fun defaultInstruction(action: RoutineAction): String =
            when (action) {
                RoutineAction.DIGEST ->
                    "Produce a structured digest of each paper: TL;DR, key contributions, methods, limitations."
                RoutineAction.DEEP_DIVE ->
                    "Read the full PDF and produce a deep technical analysis: approach, evidence quality, " +
                        "reproducibility, open questions."
                RoutineAction.COMPARE ->
                    "Compare these papers: shared problem, differing approaches, trade-offs, " +
                        "which to build on and why."
                RoutineAction.WEEKLY_REVIEW ->
                    "Synthesize my week in research: themes, must-reads, what I should queue next."
                RoutineAction.LITERATURE_SCAN ->
                    "Investigate this question. Local context attached; extend with your own search."
                RoutineAction.CUSTOM -> ""
                RoutineAction.PING -> "Connectivity test from Arxiver."
            }
    }
}
