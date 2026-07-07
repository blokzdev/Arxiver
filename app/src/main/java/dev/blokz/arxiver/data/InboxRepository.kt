package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.database.dao.InboxDao
import dev.blokz.arxiver.core.database.dao.PaperFeedbackDao
import dev.blokz.arxiver.core.database.entity.InboxItemEntity
import dev.blokz.arxiver.core.database.entity.PaperFeedbackEntity
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
    /** The user's explicit relevance thumb: +1 up, -1 down, null if unvoted (P4.2). */
    val vote: Int?,
)

@Singleton
class InboxRepository
    @Inject
    constructor(
        private val inboxDao: InboxDao,
        private val libraryRepository: LibraryRepository,
        private val paperFeedbackDao: PaperFeedbackDao,
    ) {
        fun observeInbox(): Flow<List<InboxPaper>> =
            inboxDao.observeActiveInbox().map { rows ->
                rows.map {
                    InboxPaper(
                        paper = it.paper.toListDomain(),
                        arrivedAt = Instant.ofEpochMilli(it.arrived_at),
                        state = it.state,
                        score = it.score,
                        vote = it.vote,
                    )
                }
            }

        fun observeNewCount(): Flow<Int> = inboxDao.observeNewCount()

        suspend fun saveToLibrary(paperId: String) = libraryRepository.save(paperId)

        /**
         * Dismiss a paper: flip its inbox state AND snapshot a durable negative label (P4). The label lives
         * in `paper_feedback`, not `inbox_items`, so it survives [InboxDao.pruneDismissed] and keeps teaching
         * the two-sided ranker to demote similar papers.
         */
        suspend fun dismiss(paperId: String) {
            inboxDao.setState(paperId, InboxItemEntity.STATE_DISMISSED)
            paperFeedbackDao.upsert(
                PaperFeedbackEntity(
                    paperId = paperId,
                    signal = PaperFeedbackEntity.SIGNAL_NEGATIVE,
                    source = PaperFeedbackEntity.SOURCE_DISMISS,
                    createdAt = Instant.now().toEpochMilli(),
                ),
            )
        }

        /** Undo a dismiss: restore the pre-swipe state AND clear the negative label the dismiss wrote. */
        suspend fun undoDismiss(
            paperId: String,
            previousState: String,
        ) {
            inboxDao.setState(paperId, previousState)
            paperFeedbackDao.clear(paperId)
        }

        /** Undo support: put a triaged item back into its pre-swipe state (save-undo path; no feedback written). */
        suspend fun restoreState(
            paperId: String,
            state: String,
        ) = inboxDao.setState(paperId, state)

        suspend fun markSeen(paperId: String) = inboxDao.setState(paperId, InboxItemEntity.STATE_SEEN)

        /**
         * Toggle an explicit relevance thumb (P4.2). Tapping the already-set direction clears it; otherwise
         * the vote is (re)set. Thumb-up joins the ranker's positives, thumb-down its negatives — but unlike a
         * dismiss the paper stays in the inbox (a soft "less/more like this", not a removal).
         */
        suspend fun setRelevanceVote(
            paperId: String,
            up: Boolean,
        ) {
            val desired = if (up) PaperFeedbackEntity.SIGNAL_POSITIVE else PaperFeedbackEntity.SIGNAL_NEGATIVE
            if (paperFeedbackDao.voteFor(paperId) == desired) {
                paperFeedbackDao.clear(paperId)
            } else {
                paperFeedbackDao.upsert(
                    PaperFeedbackEntity(
                        paperId = paperId,
                        signal = desired,
                        source = PaperFeedbackEntity.SOURCE_THUMB,
                        createdAt = Instant.now().toEpochMilli(),
                    ),
                )
            }
        }
    }
