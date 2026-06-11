package dev.blokz.arxiver.core.network.arxiv

import dev.blokz.arxiver.core.model.ArxivId
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AtomFeedParserTest {
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/arxiv_feed_sample.xml"))

    @Test
    fun `parses feed metadata`() {
        val feed = AtomFeedParser().parse(fixture())
        assertEquals(217776, feed.totalResults)
        assertEquals(0, feed.startIndex)
        assertEquals(2, feed.itemsPerPage)
        assertEquals(2, feed.papers.size)
    }

    @Test
    fun `parses modern entry with full metadata`() {
        val paper = AtomFeedParser().parse(fixture()).papers[0]

        assertEquals(ArxivId("2403.01234"), paper.id)
        assertEquals(2, paper.latestVersion)
        // Newlines inside title/comment collapse to single spaces.
        assertEquals("Efficient State Space Models for Long-Context Sequence Modeling", paper.title)
        assertEquals("24 pages, 7 figures. Accepted at ExampleConf 2024", paper.comment)
        assertEquals(listOf("Ada Researcher", "Boris Scholar"), paper.authors)
        assertEquals("cs.LG", paper.primaryCategory)
        assertEquals(listOf("cs.LG", "stat.ML"), paper.categories)
        assertEquals("10.1000/example.doi", paper.doi)
        assertEquals("Journal of Examples 12 (2024) 34-56", paper.journalRef)
        assertEquals("http://arxiv.org/pdf/2403.01234v2", paper.pdfUrl)
        assertEquals(Instant.parse("2024-03-02T18:00:01Z"), paper.publishedAt)
        assertEquals(Instant.parse("2024-03-15T17:59:59Z"), paper.updatedAt)
        assertEquals(
            "We study state space models as an alternative to attention for long sequences. " +
                "Our approach reduces memory usage while matching quality on standard benchmarks.",
            paper.abstract,
        )
    }

    @Test
    fun `parses legacy id entry with minimal metadata`() {
        val paper = AtomFeedParser().parse(fixture()).papers[1]

        assertEquals(ArxivId("math/0211159"), paper.id)
        assertEquals(1, paper.latestVersion)
        assertEquals("math.DG", paper.primaryCategory)
        assertEquals(listOf("Grisha Perelman"), paper.authors)
        assertNull(paper.doi)
        assertNull(paper.comment)
    }

    @Test
    fun `empty feed parses to empty list`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
              <opensearch:totalResults>0</opensearch:totalResults>
              <opensearch:startIndex>0</opensearch:startIndex>
              <opensearch:itemsPerPage>0</opensearch:itemsPerPage>
            </feed>
            """.trimIndent()
        val feed = AtomFeedParser().parse(xml.byteInputStream())
        assertEquals(0, feed.totalResults)
        assertEquals(emptyList(), feed.papers)
    }
}
