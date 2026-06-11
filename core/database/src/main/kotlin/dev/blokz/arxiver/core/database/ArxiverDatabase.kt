package dev.blokz.arxiver.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.blokz.arxiver.core.database.dao.CategoryDao
import dev.blokz.arxiver.core.database.dao.FollowDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.entity.AuthorEntity
import dev.blokz.arxiver.core.database.entity.CategoryEntity
import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.database.entity.PaperAuthorCrossRef
import dev.blokz.arxiver.core.database.entity.PaperCategoryCrossRef
import dev.blokz.arxiver.core.database.entity.PaperEntity

@Database(
    entities = [
        PaperEntity::class,
        AuthorEntity::class,
        PaperAuthorCrossRef::class,
        CategoryEntity::class,
        PaperCategoryCrossRef::class,
        FollowEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ArxiverDatabase : RoomDatabase() {
    abstract fun paperDao(): PaperDao

    abstract fun categoryDao(): CategoryDao

    abstract fun followDao(): FollowDao

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
