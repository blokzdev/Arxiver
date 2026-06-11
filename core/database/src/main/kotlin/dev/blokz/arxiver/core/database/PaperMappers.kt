package dev.blokz.arxiver.core.database

import dev.blokz.arxiver.core.database.dao.PaperWithRelations
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.PaperSource
import java.time.Instant

fun Paper.toEntity(): PaperEntity =
    PaperEntity(
        id = id.value,
        latestVersion = latestVersion,
        title = title,
        abstract = abstract,
        publishedAt = publishedAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
        primaryCategory = primaryCategory,
        comment = comment,
        journalRef = journalRef,
        doi = doi,
        pdfUrl = pdfUrl,
        citationCount = citationCount,
        s2PaperId = null,
        source = source.name,
        fetchedAt = fetchedAt.toEpochMilli(),
        embeddedAt = null,
        citationsSyncedAt = null,
    )

fun PaperWithRelations.toDomain(): Paper =
    Paper(
        id = ArxivId(paper.id),
        latestVersion = paper.latestVersion,
        title = paper.title,
        abstract = paper.abstract,
        publishedAt = Instant.ofEpochMilli(paper.publishedAt),
        updatedAt = Instant.ofEpochMilli(paper.updatedAt),
        primaryCategory = paper.primaryCategory,
        categories = categories,
        authors = authors,
        comment = paper.comment,
        journalRef = paper.journalRef,
        doi = paper.doi,
        pdfUrl = paper.pdfUrl,
        citationCount = paper.citationCount,
        source = runCatching { PaperSource.valueOf(paper.source) }.getOrDefault(PaperSource.SEARCH),
        fetchedAt = Instant.ofEpochMilli(paper.fetchedAt),
    )
