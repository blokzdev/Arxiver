package dev.blokz.arxiver.ui.fixtures

import dev.blokz.arxiver.core.database.entity.RoutineDispatchEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.data.InboxPaper
import dev.blokz.arxiver.data.LibraryPaper
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

    val inboxPapers =
        listOf(
            InboxPaper(paper = papers[0], arrivedAt = paper.updatedAt, state = "new", score = 0.86),
            InboxPaper(paper = papers[1], arrivedAt = paper.updatedAt, state = "new", score = 0.61),
            InboxPaper(paper = papers[2], arrivedAt = paper.updatedAt, state = "new", score = null),
        )

    val libraryPapers =
        listOf(
            LibraryPaper(paper = papers[0], addedAt = paper.updatedAt, status = "reading", rating = 4),
            LibraryPaper(paper = papers[1], addedAt = paper.updatedAt, status = "to_read", rating = null),
            LibraryPaper(paper = papers[2], addedAt = paper.updatedAt, status = "read", rating = 5),
        )

    val dispatches =
        listOf(
            RoutineDispatchEntity(
                id = 1,
                routineId = 1,
                action = "digest",
                paperCount = 3,
                payloadJson = "{}",
                status = RoutineDispatchEntity.STATUS_SENT,
                httpCode = 200,
                createdAt = 1_760_000_000_000,
                sentAt = 1_760_000_000_500,
            ),
            RoutineDispatchEntity(
                id = 2,
                routineId = 1,
                action = "deep_dive",
                paperCount = 1,
                payloadJson = "{}",
                status = RoutineDispatchEntity.STATUS_QUEUED,
                createdAt = 1_760_000_100_000,
            ),
            RoutineDispatchEntity(
                id = 3,
                routineId = 1,
                action = "compare",
                paperCount = 2,
                payloadJson = "{}",
                status = RoutineDispatchEntity.STATUS_FAILED,
                httpCode = 401,
                error = "token rejected",
                createdAt = 1_760_000_200_000,
            ),
        )
}
