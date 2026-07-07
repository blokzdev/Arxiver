package dev.blokz.arxiver.core.claude

import dev.blokz.arxiver.core.model.ArxivRef
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
    // arXiv identity — present for an arXiv paper, structurally ABSENT for a non-arXiv one (P-Dispatch;
    // explicitNulls=false). Declared before the source-identity keys + user so an arXiv payload serializes
    // byte-identically to the pre-P-Dispatch shape (the new nullable keys simply vanish).
    @SerialName("arxiv_id") val arxivId: String? = null,
    val version: Int? = null,
    val title: String,
    val authors: List<String>,
    val abstract: String,
    @SerialName("primary_category") val primaryCategory: String,
    val categories: List<String>,
    val published: String,
    val updated: String,
    val doi: String?,
    @SerialName("abs_url") val absUrl: String? = null,
    @SerialName("pdf_url") val pdfUrl: String,
    @SerialName("citation_count") val citationCount: Int?,
    // Source identity — present for a non-arXiv paper, ABSENT for arXiv. Discriminator: arxiv_id present ⇒ arXiv;
    // absent ⇒ read source + native_id + url, and check pdf_fetchable before attempting the PDF.
    val source: String? = null,
    @SerialName("native_id") val nativeId: String? = null,
    val url: String? = null,
    @SerialName("pdf_fetchable") val pdfFetchable: Boolean? = null,
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
    @SerialName("arxiv_id") val arxivId: String? = null,
    val near: String,
    val title: String,
    val cosine: Double,
    @SerialName("in_library") val inLibrary: Boolean,
    @SerialName("abs_url") val absUrl: String? = null,
    // Source identity for a non-arXiv neighbor (P-Dispatch) — absent for arXiv, keeping arXiv neighbors byte-identical.
    val source: String? = null,
    val url: String? = null,
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

    /**
     * Source-aware (P-Dispatch): an arXiv paper serializes byte-identically to the pre-P-Dispatch shape
     * (arxiv_id/version/abs_url present); a non-arXiv paper omits those and carries source/native_id/url +
     * an honest pdf_fetchable flag. Reads [Paper.ref] directly — never the deprecated `paper.id` shim, which
     * `error()`s on a non-arXiv paper.
     */
    private fun PaperWithAnnotations.toPayloadPaper(includeNotes: Boolean): PayloadPaper {
        val user = if (includeNotes) PayloadUser(tags = tags, status = status, rating = rating, notes = notes) else null
        val arxivRef = paper.ref as? ArxivRef
        return if (arxivRef != null) {
            PayloadPaper(
                arxivId = arxivRef.id.value,
                version = paper.latestVersion,
                title = paper.title,
                authors = paper.authors,
                abstract = paper.abstract,
                primaryCategory = paper.primaryCategory,
                categories = paper.categories,
                published = paper.publishedAt.toString(),
                updated = paper.updatedAt.toString(),
                doi = paper.doi,
                absUrl = arxivRef.absUrl(paper.latestVersion),
                pdfUrl = paper.pdfUrl,
                citationCount = paper.citationCount,
                user = user,
            )
        } else {
            PayloadPaper(
                title = paper.title,
                authors = paper.authors,
                abstract = paper.abstract,
                primaryCategory = paper.primaryCategory,
                categories = paper.categories,
                published = paper.publishedAt.toString(),
                updated = paper.updatedAt.toString(),
                doi = paper.doi,
                pdfUrl = paper.pdfUrl,
                citationCount = paper.citationCount,
                source = paper.ref.origin.wire,
                nativeId = paper.ref.nativeId,
                url = paper.canonicalUrl(),
                // Only ever emitted as `false` (non-arXiv); arXiv leaves it absent (arxiv_id already implies fetchable).
                pdfFetchable = paper.isPdfFetchable(),
                user = user,
            )
        }
    }

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
