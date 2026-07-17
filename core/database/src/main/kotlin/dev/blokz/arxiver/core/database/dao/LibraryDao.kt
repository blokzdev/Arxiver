package dev.blokz.arxiver.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.blokz.arxiver.core.database.entity.CollectionEntity
import dev.blokz.arxiver.core.database.entity.CollectionPaperCrossRef
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.NoteEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.database.entity.PaperTagCrossRef
import dev.blokz.arxiver.core.database.entity.TagEntity
import kotlinx.coroutines.flow.Flow

/** Library list row: entry metadata + the paper itself. */
data class LibraryRow(
    @Embedded val paper: PaperEntity,
    val added_at: Long,
    val status: String,
    val rating: Int?,
)

@Dao
interface LibraryDao {
    // --- entries ---

    @Upsert
    suspend fun upsertEntry(entry: LibraryEntryEntity)

    @Query("DELETE FROM library_entries WHERE paper_id = :paperId")
    suspend fun removeEntry(paperId: String)

    @Query("SELECT * FROM library_entries WHERE paper_id = :paperId")
    fun observeEntry(paperId: String): Flow<LibraryEntryEntity?>

    @Query("SELECT COUNT(*) FROM library_entries")
    suspend fun count(): Int

    @Query(
        """
        SELECT p.*, le.added_at, le.status, le.rating FROM papers p
        JOIN library_entries le ON le.paper_id = p.id
        ORDER BY le.added_at DESC
        """,
    )
    fun observeLibrary(): Flow<List<LibraryRow>>

    @Query("UPDATE library_entries SET status = :status WHERE paper_id = :paperId")
    suspend fun setStatus(
        paperId: String,
        status: String,
    )

    @Query("UPDATE library_entries SET rating = :rating WHERE paper_id = :paperId")
    suspend fun setRating(
        paperId: String,
        rating: Int?,
    )

    @Query("SELECT paper_id FROM library_entries")
    suspend fun allPaperIds(): List<String>

    /**
     * Saved-paper ids, most recent first, bounded in SQL — the P-RecShelf positive-seed source (order
     * feeds the recency blend; the LIMIT keeps a huge library from materializing a full id cursor).
     */
    @Query("SELECT paper_id FROM library_entries ORDER BY added_at DESC LIMIT :limit")
    suspend fun paperIdsByRecency(limit: Int): List<String>

    // --- collections ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun createCollection(collection: CollectionEntity): Long

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: Long)

    @Query("SELECT * FROM collections ORDER BY name")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToCollection(ref: CollectionPaperCrossRef)

    @Query("DELETE FROM collection_papers WHERE collection_id = :collectionId AND paper_id = :paperId")
    suspend fun removeFromCollection(
        collectionId: Long,
        paperId: String,
    )

    @Query(
        """
        SELECT p.*, COALESCE(le.added_at, cp.added_at) AS added_at,
               COALESCE(le.status, 'to_read') AS status, le.rating FROM papers p
        JOIN collection_papers cp ON cp.paper_id = p.id
        LEFT JOIN library_entries le ON le.paper_id = p.id
        WHERE cp.collection_id = :collectionId
        ORDER BY cp.added_at DESC
        """,
    )
    fun observeCollectionPapers(collectionId: Long): Flow<List<LibraryRow>>

    @Query("SELECT COUNT(*) FROM collection_papers WHERE collection_id = :collectionId")
    fun observeCollectionSize(collectionId: Long): Flow<Int>

    /** The member paper ids of a collection — the seed for the PA.5 collection knowledge map. */
    @Query("SELECT paper_id FROM collection_papers WHERE collection_id = :collectionId")
    suspend fun paperIdsForCollection(collectionId: Long): List<String>

    /** Collections this paper currently belongs to — drives the detail-screen picker. */
    @Query("SELECT collection_id FROM collection_papers WHERE paper_id = :paperId")
    fun observeCollectionMemberships(paperId: String): Flow<List<Long>>

    // --- tags ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT id FROM tags WHERE name = :name COLLATE NOCASE")
    suspend fun tagIdByName(name: String): Long?

    @Query("SELECT * FROM tags ORDER BY name")
    fun observeTags(): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addPaperTag(ref: PaperTagCrossRef)

    @Query("DELETE FROM paper_tags WHERE paper_id = :paperId AND tag_id = :tagId")
    suspend fun removePaperTag(
        paperId: String,
        tagId: Long,
    )

    @Query(
        """
        SELECT t.* FROM tags t
        JOIN paper_tags pt ON pt.tag_id = t.id
        WHERE pt.paper_id = :paperId
        ORDER BY t.name
        """,
    )
    fun observeTagsFor(paperId: String): Flow<List<TagEntity>>

    @Query(
        """
        SELECT p.*, COALESCE(le.added_at, 0) AS added_at,
               COALESCE(le.status, 'to_read') AS status, le.rating FROM papers p
        JOIN paper_tags pt ON pt.paper_id = p.id
        LEFT JOIN library_entries le ON le.paper_id = p.id
        WHERE pt.tag_id = :tagId
        ORDER BY le.added_at DESC
        """,
    )
    fun observeTagPapers(tagId: Long): Flow<List<LibraryRow>>

    // --- notes ---

    @Insert
    suspend fun insertNote(note: NoteEntity): Long

    @Query("UPDATE notes SET content = :content, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateNote(
        id: Long,
        content: String,
        updatedAt: Long,
    )

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: Long)

    @Query("SELECT * FROM notes WHERE paper_id = :paperId ORDER BY created_at DESC")
    fun observeNotesFor(paperId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE paper_id = :paperId")
    suspend fun notesFor(paperId: String): List<NoteEntity>
}
