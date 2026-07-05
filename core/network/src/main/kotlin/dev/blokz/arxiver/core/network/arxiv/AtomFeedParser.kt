package dev.blokz.arxiver.core.network.arxiv

import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.PaperSource
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.Instant

data class ArxivFeed(
    val totalResults: Int,
    val startIndex: Int,
    val itemsPerPage: Int,
    val papers: List<Paper>,
)

/**
 * Parses arXiv API Atom 1.0 responses (SPEC-DATA §5 mapping). Hand-rolled
 * XmlPullParser per ARCHITECTURE §3.1 — no XML library dependencies.
 */
class AtomFeedParser {
    fun parse(
        input: InputStream,
        source: PaperSource = PaperSource.SEARCH,
    ): ArxivFeed {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(input, null)

        var totalResults = 0
        var startIndex = 0
        var itemsPerPage = 0
        val papers = mutableListOf<Paper>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "totalResults" -> totalResults = parser.nextText().trim().toIntOrNull() ?: 0
                    "startIndex" -> startIndex = parser.nextText().trim().toIntOrNull() ?: 0
                    "itemsPerPage" -> itemsPerPage = parser.nextText().trim().toIntOrNull() ?: 0
                    "entry" -> parseEntry(parser, source)?.let(papers::add)
                }
            }
            event = parser.next()
        }
        return ArxivFeed(totalResults, startIndex, itemsPerPage, papers)
    }

    private fun parseEntry(
        parser: XmlPullParser,
        source: PaperSource,
    ): Paper? {
        var idUrl: String? = null
        var title: String? = null
        var summary: String? = null
        var published: String? = null
        var updated: String? = null
        var primaryCategory: String? = null
        var comment: String? = null
        var journalRef: String? = null
        var doi: String? = null
        var pdfUrl: String? = null
        val categories = mutableListOf<String>()
        val authors = mutableListOf<String>()

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "entry")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "id" -> idUrl = parser.nextText().trim()
                    "title" -> title = parser.nextText().normalizeWhitespace()
                    "summary" -> summary = parser.nextText().normalizeWhitespace()
                    "published" -> published = parser.nextText().trim()
                    "updated" -> updated = parser.nextText().trim()
                    "primary_category" -> primaryCategory = parser.getAttributeValue(null, "term")
                    "category" -> parser.getAttributeValue(null, "term")?.let(categories::add)
                    "comment" -> comment = parser.nextText().normalizeWhitespace()
                    "journal_ref" -> journalRef = parser.nextText().normalizeWhitespace()
                    "doi" -> doi = parser.nextText().trim()
                    "link" ->
                        if (parser.getAttributeValue(null, "title") == "pdf") {
                            pdfUrl = parser.getAttributeValue(null, "href")
                        }
                    "author" -> parseAuthor(parser)?.let(authors::add)
                }
            }
            event = parser.next()
        }

        val (arxivId, version) = ArxivId.parse(idUrl ?: return null) ?: return null
        val updatedAt = updated?.toInstantOrNull() ?: return null
        val publishedAt = published?.toInstantOrNull() ?: updatedAt
        // arXiv occasionally omits primary_category; first category is the convention.
        val primary = primaryCategory ?: categories.firstOrNull() ?: return null

        return Paper(
            ref = ArxivRef(arxivId),
            latestVersion = version ?: 1,
            title = title ?: return null,
            abstract = summary.orEmpty(),
            publishedAt = publishedAt,
            updatedAt = updatedAt,
            primaryCategory = primary,
            categories = (listOf(primary) + categories).distinct(),
            authors = authors,
            comment = comment,
            journalRef = journalRef,
            doi = doi,
            pdfUrl = pdfUrl ?: arxivId.pdfUrl(version),
            source = source,
        )
    }

    private fun parseAuthor(parser: XmlPullParser): String? {
        var name: String? = null
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "author")) {
            if (event == XmlPullParser.START_TAG && parser.name == "name") {
                name = parser.nextText().normalizeWhitespace()
            }
            event = parser.next()
        }
        return name?.takeIf { it.isNotBlank() }
    }

    private fun String.normalizeWhitespace(): String = trim().replace(Regex("\\s+"), " ")

    private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
}
