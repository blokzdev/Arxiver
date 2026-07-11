package dev.blokz.arxiver.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.blokz.arxiver.core.database.dao.CategoryDao
import dev.blokz.arxiver.core.database.dao.ChatDao
import dev.blokz.arxiver.core.database.dao.ChunkEmbeddingDao
import dev.blokz.arxiver.core.database.dao.CitationDao
import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.database.dao.FollowDao
import dev.blokz.arxiver.core.database.dao.InboxDao
import dev.blokz.arxiver.core.database.dao.LibraryDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.dao.PaperFeedbackDao
import dev.blokz.arxiver.core.database.dao.RelevanceModelDao
import dev.blokz.arxiver.core.database.dao.RoutineDao
import dev.blokz.arxiver.core.database.dao.SearchDao
import dev.blokz.arxiver.core.database.entity.AuthorEntity
import dev.blokz.arxiver.core.database.entity.CategoryEntity
import dev.blokz.arxiver.core.database.entity.ChatMessageEntity
import dev.blokz.arxiver.core.database.entity.ChatSessionEntity
import dev.blokz.arxiver.core.database.entity.ChunkEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.ChunkFtsEntity
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
import dev.blokz.arxiver.core.database.entity.PaperFeedbackEntity
import dev.blokz.arxiver.core.database.entity.PaperFtsEntity
import dev.blokz.arxiver.core.database.entity.PaperTagCrossRef
import dev.blokz.arxiver.core.database.entity.RelatedPaperEntity
import dev.blokz.arxiver.core.database.entity.RelevanceModelEntity
import dev.blokz.arxiver.core.database.entity.RoutineConfigEntity
import dev.blokz.arxiver.core.database.entity.RoutineDispatchEntity
import dev.blokz.arxiver.core.database.entity.TagEntity
import dev.blokz.arxiver.core.database.entity.ToolInvocationEntity
import dev.blokz.arxiver.core.database.migration.MIGRATION_10_11
import dev.blokz.arxiver.core.database.migration.MIGRATION_11_12
import dev.blokz.arxiver.core.database.migration.MIGRATION_12_13
import dev.blokz.arxiver.core.database.migration.MIGRATION_13_14
import dev.blokz.arxiver.core.database.migration.MIGRATION_14_15
import dev.blokz.arxiver.core.database.migration.MIGRATION_1_2
import dev.blokz.arxiver.core.database.migration.MIGRATION_2_3
import dev.blokz.arxiver.core.database.migration.MIGRATION_3_4
import dev.blokz.arxiver.core.database.migration.MIGRATION_4_5
import dev.blokz.arxiver.core.database.migration.MIGRATION_5_6
import dev.blokz.arxiver.core.database.migration.MIGRATION_6_7
import dev.blokz.arxiver.core.database.migration.MIGRATION_7_8
import dev.blokz.arxiver.core.database.migration.MIGRATION_8_9
import dev.blokz.arxiver.core.database.migration.MIGRATION_9_10

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
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        ToolInvocationEntity::class,
        PaperFtsEntity::class,
        NoteFtsEntity::class,
        PaperEmbeddingEntity::class,
        PaperFeedbackEntity::class,
        ChunkEmbeddingEntity::class,
        ChunkFtsEntity::class,
        RelatedPaperEntity::class,
        RelevanceModelEntity::class,
        CitationEdgeEntity::class,
        RoutineConfigEntity::class,
        RoutineDispatchEntity::class,
    ],
    version = ArxiverDatabase.VERSION,
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

    abstract fun paperFeedbackDao(): PaperFeedbackDao

    abstract fun relevanceModelDao(): RelevanceModelDao

    abstract fun chunkEmbeddingDao(): ChunkEmbeddingDao

    abstract fun chatDao(): ChatDao

    abstract fun citationDao(): CitationDao

    abstract fun routineDao(): RoutineDao

    companion object {
        const val NAME = "arxiver.db"

        /** Single source of truth for the schema version (also read by MigrationHarnessTest). */
        const val VERSION = 15

        /**
         * Destructive migrations are forbidden (CLAUDE.md): every future schema
         * version must register a Migration here.
         */
        fun build(context: Context): ArxiverDatabase =
            Room.databaseBuilder(context, ArxiverDatabase::class.java, NAME)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                )
                .build()
    }
}
