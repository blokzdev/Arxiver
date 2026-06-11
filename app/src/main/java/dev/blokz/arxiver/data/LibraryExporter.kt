package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.database.dao.LibraryDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ExportedPaper(
    val arxivId: String,
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
    val absUrl: String,
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
                    arxivId = row.paper.id,
                    version = row.paper.latestVersion,
                    title = row.paper.title,
                    abstract = row.paper.abstract,
                    authors = full?.authors ?: emptyList(),
                    primaryCategory = row.paper.primaryCategory,
                    categories = full?.categories ?: listOf(row.paper.primaryCategory),
                    published = Instant.ofEpochMilli(row.paper.publishedAt).toString(),
                    updated = Instant.ofEpochMilli(row.paper.updatedAt).toString(),
                    doi = row.paper.doi,
                    absUrl = "https://arxiv.org/abs/${row.paper.id}",
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
                val key = "$firstAuthorKey$year${paper.arxivId.takeLast(4).filter { it.isDigit() }}"
                buildString {
                    appendLine("@misc{$key,")
                    appendLine("  title = {${paper.title.escapeBibtex()}},")
                    appendLine("  author = {${paper.authors.joinToString(" and ").escapeBibtex()}},")
                    appendLine("  year = {$year},")
                    appendLine("  eprint = {${paper.arxivId}},")
                    appendLine("  archivePrefix = {arXiv},")
                    appendLine("  primaryClass = {${paper.primaryCategory}},")
                    paper.doi?.let { appendLine("  doi = {$it},") }
                    appendLine("  url = {${paper.absUrl}}")
                    append("}")
                }
            }

        private fun String.escapeBibtex(): String = replace("{", "\\{").replace("}", "\\}")
    }
