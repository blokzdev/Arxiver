package dev.blokz.arxiver.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.blokz.arxiver.core.database.dao.CategoryDao
import dev.blokz.arxiver.core.database.dao.CitationDao
import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.database.dao.FollowDao
import dev.blokz.arxiver.core.database.dao.InboxDao
import dev.blokz.arxiver.core.database.dao.LibraryDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.dao.RoutineDao
import dev.blokz.arxiver.core.database.dao.SearchDao
import dev.blokz.arxiver.core.database.entity.AuthorEntity
import dev.blokz.arxiver.core.database.entity.CategoryEntity
import dev.blokz.arxiver.core.database.entity.CitationEdgeEntity
import dev.blokz.arxiver.core.database.entity.CollectionEntity
import dev.blokz.arxiver.core.database.entity.CollectionPaperCrossRef
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.entity.LibraryEntryEntity
import dev.blokz.arxiver.core.database.entity.NoteEntity
import dev.blokz.arxiver.core.database.entity.NoteFtsEntity
import dev.blokz.arxiver.core.database.entity.PaperAuthorCrossRef
import dev.blokz.arxiver.core.database.entity.PaperCategoryCrossRef
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.PaperEntity
import dev.blokz.arxiver.core.database.entity.PaperFtsEntity
import dev.blokz.arxiver.core.database.entity.PaperTagCrossRef
import dev.blokz.arxiver.core.database.entity.RelatedPaperEntity
import dev.blokz.arxiver.core.database.entity.RoutineConfigEntity
import dev.blokz.arxiver.core.database.entity.RoutineDispatchEntity
import dev.blokz.arxiver.core.database.entity.TagEntity

@Database(
    entities = [
        PaperEntity::class,
        AuthorEntity::class,
        PaperAuthorCrossRef::class,
        CategoryEntity::class,
        PaperCategoryCrossRef::class,
        FollowEntity::class,
        LibraryEntryEntity::class,
        CollectionEntity::class,
        CollectionPaperCrossRef::class,
        TagEntity::class,
        PaperTagCrossRef::class,
        NoteEntity::class,
        InboxItemEntity::class,
        PaperFtsEntity::class,
        NoteFtsEntity::class,
        PaperEmbeddingEntity::class,
        RelatedPaperEntity::class,
        CitationEdgeEntity::class,
        RoutineConfigEntity::class,
        RoutineDispatchEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ArxiverDatabase : RoomDatabase() {
    abstract fun paperDao(): PaperDao

    abstract fun categoryDao(): CategoryDao

    abstract fun followDao(): FollowDao

    abstract fun libraryDao(): LibraryDao

    abstract fun inboxDao(): InboxDao

    abstract fun searchDao(): SearchDao

    abstract fun embeddingDao(): EmbeddingDao

    abstract fun citationDao(): CitationDao

    abstract fun routineDao(): RoutineDao

    companion object {
        const val NAME = "arxiver.db"

        /**
         * Destructive migrations are forbidden (CLAUDE.md): every future schema
         * version must register a Migration here.
         */
        fun build(context: Context): ArxiverDatabase =
            Room.databaseBuilder(context, ArxiverDatabase::class.java, NAME)
                .build()
    }
}
