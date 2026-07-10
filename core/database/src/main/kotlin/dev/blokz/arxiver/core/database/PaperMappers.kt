package dev.blokz.arxiver.core.database

import dev.blokz.arxiver.core.database.dao.PaperWithRelations
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.PaperRef
import dev.blokz.arxiver.core.model.PaperSource
import dev.blokz.arxiver.core.model.normalizeDoi
import java.time.Instant

fun Paper.toEntity(): PaperEntity =
    PaperEntity(
        id = ref.storageId,
        origin = ref.origin.wire,
        nativeId = ref.nativeId,
        latestVersion = latestVersion,
        title = title,
        abstract = abstract,
        publishedAt = publishedAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
        primaryCategory = primaryCategory,
        authorsLine = authors.joinToString(", "),
        comment = comment,
        journalRef = journalRef,
        doi = doi,
        // The single write chokepoint for the de-dup key: every stored row's `doi_norm` is, by construction,
        // exactly what `paperIdByDoi` will be queried with (P-Explorer PE.2).
        doiNorm = normalizeDoi(doi),
        pdfUrl = pdfUrl,
        citationCount = citationCount,
        s2PaperId = null,
        source = source.name,
        fetchedAt = fetchedAt.toEpochMilli(),
        embeddedAt = null,
        citationsSyncedAt = null,
    )

/**
 * List-row mapping: authors come from the denormalized line, categories carry
 * only the primary. Detail screens use [PaperWithRelations.toDomain].
 */
fun PaperEntity.toListDomain(): Paper =
    Paper(
        // Reconstruct from the storage-id PK alone (the single source of truth); origin/native_id are
        // redundant filter columns, never the reconstruction key — avoids discriminator-disagreement bugs.
        ref = PaperRef.fromStorageId(id),
        latestVersion = latestVersion,
        title = title,
        abstract = abstract,
        publishedAt = Instant.ofEpochMilli(publishedAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        primaryCategory = primaryCategory,
        categories = listOf(primaryCategory),
        authors = authorsLine.split(", ").filter { it.isNotBlank() },
        comment = comment,
        journalRef = journalRef,
        doi = doi,
        pdfUrl = pdfUrl,
        citationCount = citationCount,
        source = runCatching { PaperSource.valueOf(source) }.getOrDefault(PaperSource.SEARCH),
        fetchedAt = Instant.ofEpochMilli(fetchedAt),
    )

fun PaperWithRelations.toDomain(): Paper =
    Paper(
        ref = PaperRef.fromStorageId(paper.id),
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
