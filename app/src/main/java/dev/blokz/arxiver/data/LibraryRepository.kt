package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.database.dao.InboxDao
import dev.blokz.arxiver.core.database.dao.LibraryDao
import dev.blokz.arxiver.core.database.entity.CollectionEntity
import dev.blokz.arxiver.core.database.entity.CollectionPaperCrossRef
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.NoteEntity
import dev.blokz.arxiver.core.database.entity.PaperTagCrossRef
import dev.blokz.arxiver.core.database.entity.TagEntity
import dev.blokz.arxiver.core.database.toListDomain
import dev.blokz.arxiver.core.model.Paper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class LibraryPaper(
    val paper: Paper,
    val addedAt: Instant,
    val status: String,
    val rating: Int?,
)

@Singleton
class LibraryRepository
    @Inject
    constructor(
        private val libraryDao: LibraryDao,
        private val inboxDao: InboxDao,
    ) {
        fun observeLibrary(): Flow<List<LibraryPaper>> =
            libraryDao.observeLibrary().map { rows ->
                rows.map {
                    LibraryPaper(
                        paper = it.paper.toListDomain(),
                        addedAt = Instant.ofEpochMilli(it.added_at),
                        status = it.status,
                        rating = it.rating,
                    )
                }
            }

        fun observeIsSaved(paperId: String): Flow<Boolean> = libraryDao.observeEntry(paperId).map { it != null }

        fun observeEntry(paperId: String): Flow<LibraryEntryEntity?> = libraryDao.observeEntry(paperId)

        suspend fun save(paperId: String) {
            libraryDao.upsertEntry(
                LibraryEntryEntity(paperId = paperId, addedAt = Instant.now().toEpochMilli()),
            )
            inboxDao.setState(paperId, InboxItemEntity.STATE_SAVED)
        }

        suspend fun unsave(paperId: String) = libraryDao.removeEntry(paperId)

        suspend fun setStatus(
            paperId: String,
            status: String,
        ) = libraryDao.setStatus(paperId, status)

        suspend fun setRating(
            paperId: String,
            rating: Int?,
        ) = libraryDao.setRating(paperId, rating)

        // --- collections ---

        fun observeCollections(): Flow<List<CollectionEntity>> = libraryDao.observeCollections()

        fun observeCollectionPapers(collectionId: Long): Flow<List<LibraryPaper>> =
            libraryDao.observeCollectionPapers(collectionId).map { rows ->
                rows.map {
                    LibraryPaper(it.paper.toListDomain(), Instant.ofEpochMilli(it.added_at), it.status, it.rating)
                }
            }

        suspend fun createCollection(name: String): Long =
            libraryDao.createCollection(CollectionEntity(name = name.trim(), createdAt = Instant.now().toEpochMilli()))

        suspend fun deleteCollection(id: Long) = libraryDao.deleteCollection(id)

        suspend fun addToCollection(
            collectionId: Long,
            paperId: String,
        ) = libraryDao.addToCollection(
            CollectionPaperCrossRef(collectionId, paperId, Instant.now().toEpochMilli()),
        )

        suspend fun removeFromCollection(
            collectionId: Long,
            paperId: String,
        ) = libraryDao.removeFromCollection(collectionId, paperId)

        // --- tags ---

        fun observeTags(): Flow<List<TagEntity>> = libraryDao.observeTags()

        fun observeTagsFor(paperId: String): Flow<List<TagEntity>> = libraryDao.observeTagsFor(paperId)

        fun observeTagPapers(tagId: Long): Flow<List<LibraryPaper>> =
            libraryDao.observeTagPapers(tagId).map { rows ->
                rows.map {
                    LibraryPaper(it.paper.toListDomain(), Instant.ofEpochMilli(it.added_at), it.status, it.rating)
                }
            }

        suspend fun addTag(
            paperId: String,
            name: String,
        ) {
            val trimmed = name.trim().removePrefix("#")
            if (trimmed.isEmpty()) return
            val tagId =
                libraryDao.insertTag(TagEntity(name = trimmed))
                    .takeIf { it != -1L } ?: requireNotNull(libraryDao.tagIdByName(trimmed))
            libraryDao.addPaperTag(PaperTagCrossRef(paperId = paperId, tagId = tagId))
        }

        suspend fun removeTag(
            paperId: String,
            tagId: Long,
        ) = libraryDao.removePaperTag(paperId, tagId)

        // --- notes ---

        fun observeNotesFor(paperId: String): Flow<List<NoteEntity>> = libraryDao.observeNotesFor(paperId)

        suspend fun addNote(
            paperId: String,
            content: String,
        ) {
            if (content.isBlank()) return
            val now = Instant.now().toEpochMilli()
            libraryDao.insertNote(
                NoteEntity(paperId = paperId, content = content.trim(), createdAt = now, updatedAt = now),
            )
        }

        suspend fun updateNote(
            noteId: Long,
            content: String,
        ) = libraryDao.updateNote(noteId, content.trim(), Instant.now().toEpochMilli())

        suspend fun deleteNote(noteId: Long) = libraryDao.deleteNote(noteId)
    }
