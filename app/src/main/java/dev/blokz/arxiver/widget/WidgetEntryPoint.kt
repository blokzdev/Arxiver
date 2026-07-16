package dev.blokz.arxiver.widget

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.blokz.arxiver.core.database.dao.InboxDao
import dev.blokz.arxiver.core.database.dao.RelevanceModelDao

/**
 * Hilt entry point for the Glance widget (P-Ambient PA.2). A `GlanceAppWidget`/`GlanceAppWidgetReceiver` is
 * instantiated by the system, NOT by Hilt, so it reaches the singleton Room DAOs through
 * `EntryPointAccessors.fromApplication(...)` against the `SingletonComponent` (the same DB instance the app +
 * `EmbeddingWorker` use — `AppModule` provides the DB as `@Singleton`). Read-only: the widget only queries.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun inboxDao(): InboxDao

    fun relevanceModelDao(): RelevanceModelDao
}
