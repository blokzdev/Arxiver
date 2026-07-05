package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.database.dao.FollowDao
import dev.blokz.arxiver.core.database.dao.LibraryDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.dao.RoutineDao
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.NoteEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.PaperRef
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
data class BackupFollow(
    val type: String,
    val value: String,
    val label: String,
)

@Serializable
data class BackupCollection(
    val name: String,
    val paperIds: List<String>,
)

@Serializable
data class BackupRoutine(
    val name: String,
    val triggerUrl: String,
)

/**
 * SPEC-DATA §6 backup: every relational fact worth keeping, in one JSON file.
 * Tokens never appear (routines export name+URL only — re-enter tokens after
 * import); PDFs and embeddings are excluded as re-derivable.
 */
@Serializable
data class ArxiverBackup(
    val schema: String = SCHEMA,
    val exportedAt: String,
    val papers: List<ExportedPaper>,
    val follows: List<BackupFollow> = emptyList(),
    val collections: List<BackupCollection> = emptyList(),
    val routines: List<BackupRoutine> = emptyList(),
) {
    companion object {
        const val SCHEMA = "arxiver-backup/v2"

        /** Schemas [import] accepts: v2 (P-Sources, carries origin + real pdfUrl) and the legacy v1. */
        val SUPPORTED_SCHEMAS = setOf("arxiver-backup/v1", SCHEMA)
    }
}

data class ImportSummary(
    val papers: Int,
    val follows: Int,
    val collections: Int,
    val routinesNeedingTokens: Int,
)

/** Creates a token-less routine and returns its id (token re-entered by the user). */
fun interface RoutineRestorer {
    suspend fun restore(
        name: String,
        triggerUrl: String,
    ): Long
}

class BackupManager(
    private val libraryExporter: LibraryExporter,
    private val paperDao: PaperDao,
    private val libraryDao: LibraryDao,
    private val followDao: FollowDao,
    private val routineDao: RoutineDao,
    private val routineRestorer: RoutineRestorer,
) {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    suspend fun export(): String {
        val papers = libraryExporter.collectExportedPapers()
        val follows =
            followDao.observeAll().first().map {
                BackupFollow(type = it.type, value = it.value, label = it.label)
            }
        val collections =
            libraryDao.observeCollections().first().map { collection ->
                BackupCollection(
                    name = collection.name,
                    paperIds = libraryDao.observeCollectionPapers(collection.id).first().map { it.paper.id },
                )
            }
        val routines =
            routineDao.observeConfigs().first().map {
                BackupRoutine(name = it.name, triggerUrl = it.triggerUrl)
            }
        return json.encodeToString(
            ArxiverBackup.serializer(),
            ArxiverBackup(
                exportedAt = Instant.now().toString(),
                papers = papers,
                follows = follows,
                collections = collections,
                routines = routines,
            ),
        )
    }

    /**
     * Transactional-in-spirit upsert: existing rows survive, imported rows
     * merge in. Embedding backfill is the caller's job (schedule embedNow).
     */
    suspend fun import(content: String): ImportSummary {
        val backup = json.decodeFromString(ArxiverBackup.serializer(), content)
        // Accept the current v2 AND the legacy v1 (whose papers lack origin/pdfUrl — the DTO defaults +
        // toEntity's arXiv re-synthesis restore them losslessly). A bare == would make every v1 file on
        // disk un-importable.
        require(backup.schema in ArxiverBackup.SUPPORTED_SCHEMAS) { "unsupported backup schema: ${backup.schema}" }

        backup.papers.forEach { exported ->
            paperDao.upsertPaperWithRelations(
                exported.toEntity(),
                exported.authors,
                exported.categories,
            )
            libraryDao.upsertEntry(
                LibraryEntryEntity(
                    paperId = exported.paperId,
                    addedAt = exported.addedAt.toEpochMilliOrNow(),
                    status = exported.status,
                    rating = exported.rating,
                ),
            )
            exported.tags.forEach { tag -> addTag(exported.paperId, tag) }
            val existingNotes = libraryDao.notesFor(exported.paperId).map { it.content }.toSet()
            exported.notes.filter { it !in existingNotes }.forEach { note ->
                val now = Instant.now().toEpochMilli()
                libraryDao.insertNote(
                    NoteEntity(paperId = exported.paperId, content = note, createdAt = now, updatedAt = now),
                )
            }
        }

        backup.follows.forEach {
            followDao.insert(
                FollowEntity(
                    type = it.type,
                    value = it.value,
                    label = it.label,
                    createdAt = Instant.now().toEpochMilli(),
                ),
            )
        }

        backup.collections.forEach { collection ->
            val id =
                libraryDao.createCollection(
                    dev.blokz.arxiver.core.database.entity.CollectionEntity(
                        name = collection.name,
                        createdAt = Instant.now().toEpochMilli(),
                    ),
                ).takeIf { it != -1L }
                    ?: libraryDao.observeCollections().first().first { it.name == collection.name }.id
            collection.paperIds.forEach { paperId ->
                libraryDao.addToCollection(
                    dev.blokz.arxiver.core.database.entity.CollectionPaperCrossRef(
                        collectionId = id,
                        paperId = paperId,
                        addedAt = Instant.now().toEpochMilli(),
                    ),
                )
            }
        }

        // Routines come back token-less: placeholder token forces re-auth.
        val existingUrls = routineDao.observeConfigs().first().map { it.triggerUrl }.toSet()
        val restoredRoutines = backup.routines.filter { it.triggerUrl !in existingUrls }
        restoredRoutines.forEach { routine ->
            val id = routineRestorer.restore(routine.name, routine.triggerUrl)
            routineDao.markAuthInvalid(id)
        }

        return ImportSummary(
            papers = backup.papers.size,
            follows = backup.follows.size,
            collections = backup.collections.size,
            routinesNeedingTokens = restoredRoutines.size,
        )
    }

    private suspend fun addTag(
        paperId: String,
        name: String,
    ) {
        libraryDao.insertTag(dev.blokz.arxiver.core.database.entity.TagEntity(name = name))
        libraryDao.tagIdByName(name)?.let { tagId ->
            libraryDao.addPaperTag(
                dev.blokz.arxiver.core.database.entity.PaperTagCrossRef(paperId = paperId, tagId = tagId),
            )
        }
    }

    private fun ExportedPaper.toEntity(): PaperEntity {
        // The PK is the single source of truth for identity origin (a v1 file's paperId is a bare arXiv id).
        val ref = PaperRef.fromStorageId(paperId)
        return PaperEntity(
            id = paperId,
            latestVersion = version,
            title = title,
            abstract = abstract,
            publishedAt = published.toEpochMilliOrNow(),
            updatedAt = updated.toEpochMilliOrNow(),
            primaryCategory = primaryCategory,
            authorsLine = authors.joinToString(", "),
            comment = comment,
            journalRef = journalRef,
            doi = doi,
            // v2 carries the real URL verbatim (chemRxiv's real pdf, or arXiv's stored pdf). A v1 arXiv row
            // has pdfUrl == null and re-synthesizes exactly what it stored before — never a mangled
            // arxiv.org URL for a non-arXiv paper (the pre-P-Sources bug).
            pdfUrl = pdfUrl ?: (ref as? ArxivRef)?.pdfUrl(version) ?: "",
            origin = ref.origin.wire,
            nativeId = ref.nativeId,
            citationCount = null,
            s2PaperId = null,
            // Acquisition path (distinct axis from identity origin): a restored paper is treated as MANUAL.
            source = "MANUAL",
            fetchedAt = Instant.now().toEpochMilli(),
            embeddedAt = null,
            citationsSyncedAt = null,
        )
    }

    private fun String.toEpochMilliOrNow(): Long =
        runCatching { Instant.parse(this).toEpochMilli() }.getOrDefault(Instant.now().toEpochMilli())
}
