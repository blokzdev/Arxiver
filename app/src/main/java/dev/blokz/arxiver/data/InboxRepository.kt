package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.database.dao.InboxDao
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.toListDomain
import dev.blokz.arxiver.core.model.Paper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class InboxPaper(
    val paper: Paper,
    val arrivedAt: Instant,
    val state: String,
    val score: Double?,
)

@Singleton
class InboxRepository
    @Inject
    constructor(
        private val inboxDao: InboxDao,
        private val libraryRepository: LibraryRepository,
    ) {
        fun observeInbox(): Flow<List<InboxPaper>> =
            inboxDao.observeActiveInbox().map { rows ->
                rows.map {
                    InboxPaper(
                        paper = it.paper.toListDomain(),
                        arrivedAt = Instant.ofEpochMilli(it.arrived_at),
                        state = it.state,
                        score = it.score,
                    )
                }
            }

        fun observeNewCount(): Flow<Int> = inboxDao.observeNewCount()

        suspend fun saveToLibrary(paperId: String) = libraryRepository.save(paperId)

        suspend fun dismiss(paperId: String) = inboxDao.setState(paperId, InboxItemEntity.STATE_DISMISSED)

        /** Undo support: put a triaged item back into its pre-swipe state. */
        suspend fun restoreState(
            paperId: String,
            state: String,
        ) = inboxDao.setState(paperId, state)

        suspend fun markSeen(paperId: String) = inboxDao.setState(paperId, InboxItemEntity.STATE_SEEN)
    }
