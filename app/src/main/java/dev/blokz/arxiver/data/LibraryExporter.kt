package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.database.dao.LibraryDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.model.Source
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The export/backup DTO for one library paper — shared by [LibraryExport] and `ArxiverBackup`.
 *
 * [paperId] is the opaque `papers.id` PK (a bare arXiv id, or `"<origin>:<nativeId>"`). It accepts the
 * legacy `"arxivId"` JSON key too ([JsonNames]), so a pre-P-Sources v1 file still deserializes losslessly.
 * [origin] (default `"arxiv"`) + [pdfUrl] (nullable) are additive: a v1 file omits both, so its papers
 * default to arXiv with a re-synthesized PDF URL on restore. The old `absUrl` field is dropped — the PDF
 * URL is now carried verbatim and the abs/DOI link is derived, so a non-arXiv paper is never mangled into
 * an arxiv.org URL.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExportedPaper(
    @JsonNames("arxivId") val paperId: String,
    val version: Int,
    val title: String,
    val abstract: String,
    val authors: List<String>,
    val primaryCategory: String,
    val categories: List<String>,
    val published: String,
    val updated: String,
    val doi: String?,
    val comment: String? = null,
    val journalRef: String? = null,
    val origin: String = "arxiv",
    val pdfUrl: String? = null,
    val status: String,
    val rating: Int?,
    val addedAt: String,
    val tags: List<String>,
    val notes: List<String>,
)

@Serializable
data class LibraryExport(
    val schema: String = "arxiver-export/v1",
    val exportedAt: String,
    val papers: List<ExportedPaper>,
)

/** SPEC-DATA §6: library export, token-free by construction. */
@Singleton
class LibraryExporter
    @Inject
    constructor(
        private val libraryDao: LibraryDao,
        private val paperDao: PaperDao,
    ) {
        private val json = Json { prettyPrint = true }

        suspend fun collectExportedPapers(): List<ExportedPaper> =
            libraryDao.observeLibrary().first().map { row ->
                val full = paperDao.paperWithRelations(row.paper.id)
                val tags = libraryDao.observeTagsFor(row.paper.id).first().map { it.name }
                val notes = libraryDao.notesFor(row.paper.id).map { it.content }
                ExportedPaper(
                    paperId = row.paper.id,
                    version = row.paper.latestVersion,
                    title = row.paper.title,
                    abstract = row.paper.abstract,
                    authors = full?.authors ?: emptyList(),
                    primaryCategory = row.paper.primaryCategory,
                    categories = full?.categories ?: listOf(row.paper.primaryCategory),
                    published = Instant.ofEpochMilli(row.paper.publishedAt).toString(),
                    updated = Instant.ofEpochMilli(row.paper.updatedAt).toString(),
                    doi = row.paper.doi,
                    origin = row.paper.origin,
                    pdfUrl = row.paper.pdfUrl,
                    status = row.status,
                    rating = row.rating,
                    addedAt = Instant.ofEpochMilli(row.added_at).toString(),
                    tags = tags,
                    notes = notes,
                )
            }

        suspend fun toJson(): String =
            json.encodeToString(LibraryExport(exportedAt = Instant.now().toString(), papers = collectExportedPapers()))

        suspend fun toBibtex(): String =
            collectExportedPapers().joinToString("\n\n") { paper ->
                val year = Instant.parse(paper.published).atZone(ZoneOffset.UTC).year
                val firstAuthorKey =
                    paper.authors.firstOrNull()
                        ?.substringAfterLast(' ')
                        ?.lowercase()
                        ?.filter { it.isLetter() }
                        .orEmpty()
                val key = "$firstAuthorKey$year${paper.paperId.takeLast(4).filter { it.isDigit() }}"
                buildString {
                    appendLine("@misc{$key,")
                    appendLine("  title = {${paper.title.escapeBibtex()}},")
                    appendLine("  author = {${paper.authors.joinToString(" and ").escapeBibtex()}},")
                    appendLine("  year = {$year},")
                    if (paper.origin == Source.ARXIV.wire) {
                        appendLine("  eprint = {${paper.paperId}},")
                        appendLine("  archivePrefix = {arXiv},")
                        appendLine("  primaryClass = {${paper.primaryCategory}},")
                        paper.doi?.let { appendLine("  doi = {$it},") }
                        appendLine("  url = {https://arxiv.org/abs/${paper.paperId}}")
                    } else {
                        val label = Source.entries.firstOrNull { it.wire == paper.origin }?.displayName ?: paper.origin
                        appendLine("  howpublished = {$label},")
                        paper.doi?.let { appendLine("  doi = {$it},") }
                        appendLine("  url = {${paper.doi?.let { "https://doi.org/$it" } ?: paper.pdfUrl.orEmpty()}}")
                    }
                    append("}")
                }
            }

        private fun String.escapeBibtex(): String = replace("{", "\\{").replace("}", "\\}")
    }
