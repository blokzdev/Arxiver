package dev.blokz.arxiver.core.network.openalex

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** P-FeedPolish de-dup: extracting an OpenAlex work's arXiv cross-id from its `locations[]`. */
class OpenAlexWorkTest {
    private fun loc(
        url: String?,
        sid: String?,
    ) = OpenAlexLocation(landingPageUrl = url, source = sid?.let { OpenAlexSource(id = "https://openalex.org/$it") })

    @Test
    fun `arxivLandingUrl extracts the arXiv-source location of a chemRxiv-primary work`() {
        val work =
            OpenAlexWork(
                doi = "https://doi.org/10.26434/chemrxiv-2024-x",
                // chemRxiv is primary; the arXiv cross-location coexists (the fork case).
                locations =
                    listOf(
                        loc("https://chemrxiv.org/x", "S4393918830"),
                        loc("https://arxiv.org/abs/2403.09999", "S4306400194"),
                    ),
            )
        assertEquals("https://arxiv.org/abs/2403.09999", work.arxivLandingUrl())
    }

    @Test
    fun `arxivLandingUrl is null when the work has no arXiv location`() {
        assertNull(OpenAlexWork(locations = listOf(loc("https://chemrxiv.org/x", "S4393918830"))).arxivLandingUrl())
    }

    @Test
    fun `arxivLandingUrl prefers the parse-safe arxiv-org form over the doi-org 10_48550 form`() {
        val work =
            OpenAlexWork(
                // The doi.org/10.48550 form is NOT ArxivId.parse-safe; the arxiv.org form is → it's preferred.
                locations =
                    listOf(
                        loc("https://doi.org/10.48550/arxiv.2403.09999", "S4306400194"),
                        loc("https://arxiv.org/abs/2403.09999", "S4306400194"),
                    ),
            )
        assertEquals("https://arxiv.org/abs/2403.09999", work.arxivLandingUrl())
    }
}
