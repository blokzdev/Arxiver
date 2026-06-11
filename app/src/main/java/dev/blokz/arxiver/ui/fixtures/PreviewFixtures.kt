package dev.blokz.arxiver.ui.fixtures

import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.Paper
import java.time.Instant

/** Shared fixture data for @Preview composables (SPEC-UI §4). */
object PreviewFixtures {
    val paper =
        Paper(
            id = ArxivId("2403.01234"),
            latestVersion = 2,
            title = "Efficient State Space Models for Long-Context Sequence Modeling",
            abstract =
                "We study state space models as an alternative to attention for long " +
                    "sequences. Our approach reduces memory usage while matching quality on " +
                    "standard benchmarks across language and audio domains.",
            publishedAt = Instant.parse("2024-03-02T18:00:01Z"),
            updatedAt = Instant.parse("2024-03-15T17:59:59Z"),
            primaryCategory = "cs.LG",
            categories = listOf("cs.LG", "stat.ML"),
            authors = listOf("Ada Researcher", "Boris Scholar", "Carol Theorist"),
            comment = "24 pages, 7 figures. Accepted at ExampleConf 2024",
            citationCount = 87,
        )

    val papers =
        listOf(
            paper,
            paper.copy(
                id = ArxivId("2404.05678"),
                title = "A Survey of Retrieval-Augmented Generation for Scientific Literature",
                authors = listOf("Dmitri Surveyor"),
                primaryCategory = "cs.CL",
                citationCount = null,
            ),
            paper.copy(
                id = ArxivId("2405.00001"),
                title = "Tokenizer-Free Language Modeling at Scale",
                authors = listOf("Eve Modeler", "Frank Scaler"),
                primaryCategory = "cs.CL",
                citationCount = 12,
            ),
        )
}
