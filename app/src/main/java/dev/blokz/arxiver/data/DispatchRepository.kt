package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.claude.PaperWithAnnotations
import dev.blokz.arxiver.core.claude.PayloadBuilder
import dev.blokz.arxiver.core.claude.PayloadResult
import dev.blokz.arxiver.core.claude.RoutineAction
import dev.blokz.arxiver.core.claude.RoutineTriggerClient
import dev.blokz.arxiver.core.claude.TokenVault
import dev.blokz.arxiver.core.claude.TriggerOutcome
import dev.blokz.arxiver.core.database.dao.LibraryDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.dao.RoutineDao
import dev.blokz.arxiver.core.database.entity.RoutineConfigEntity
import dev.blokz.arxiver.core.database.entity.RoutineDispatchEntity
import dev.blokz.arxiver.core.database.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DispatchSubmission {
    data class Sent(val dispatchId: Long) : DispatchSubmission

    data class Queued(val dispatchId: Long) : DispatchSubmission

    data class Failed(val dispatchId: Long, val reason: String) : DispatchSubmission

    data class AuthRejected(val dispatchId: Long) : DispatchSubmission

    data class PayloadTooLarge(val byteSize: Int, val limit: Int) : DispatchSubmission
}

@Singleton
class DispatchRepository
    @Inject
    constructor(
        private val routineDao: RoutineDao,
        private val tokenVault: TokenVault,
        private val payloadBuilder: PayloadBuilder,
        private val triggerClient: RoutineTriggerClient,
        private val libraryDao: LibraryDao,
        private val paperDao: PaperDao,
    ) {
        // --- routine configs ---

        fun observeRoutines(): Flow<List<RoutineConfigEntity>> = routineDao.observeConfigs()

        suspend fun addRoutine(
            name: String,
            triggerUrl: String,
            token: String,
        ): Long {
            val alias = tokenVault.store(token)
            return routineDao.insertConfig(
                RoutineConfigEntity(
                    name = name.trim(),
                    triggerUrl = triggerUrl.trim(),
                    tokenAlias = alias,
                    createdAt = Instant.now().toEpochMilli(),
                ),
            )
        }

        suspend fun replaceToken(
            routineId: Long,
            token: String,
        ) {
            val config = routineDao.configById(routineId) ?: return
            tokenVault.replace(config.tokenAlias, token)
            routineDao.updateConfig(config.copy(authInvalid = false))
        }

        suspend fun deleteRoutine(routineId: Long) {
            routineDao.configById(routineId)?.let { tokenVault.delete(it.tokenAlias) }
            routineDao.deleteConfig(routineId)
        }

        // --- dispatching ---

        /**
         * Builds the payload, records the dispatch, and attempts delivery
         * (SPEC-CLAUDE-BRIDGE §3/§6). Retryable failures stay queued for
         * [dev.blokz.arxiver.sync.DispatchWorker].
         */
        suspend fun dispatch(
            routineId: Long,
            action: RoutineAction,
            instruction: String,
            paperIds: List<String>,
            includeNotes: Boolean,
        ): DispatchSubmission {
            val payload = buildPayload(action, instruction, paperIds, includeNotes)
            if (payload is PayloadResult.TooLarge) {
                return DispatchSubmission.PayloadTooLarge(payload.byteSize, payload.limit)
            }
            val ready = payload as PayloadResult.Ready
            val dispatchId =
                routineDao.insertDispatch(
                    RoutineDispatchEntity(
                        routineId = routineId,
                        action = action.wire,
                        paperCount = ready.paperCount,
                        payloadJson = ready.json,
                        createdAt = Instant.now().toEpochMilli(),
                    ),
                )
            return attemptSend(dispatchId)
        }

        /** Builds the exact JSON the confirm sheet previews (SPEC-CLAUDE-BRIDGE §5). */
        suspend fun previewPayload(
            action: RoutineAction,
            instruction: String,
            paperIds: List<String>,
            includeNotes: Boolean,
        ): PayloadResult = buildPayload(action, instruction, paperIds, includeNotes)

        suspend fun retry(dispatchId: Long): DispatchSubmission = attemptSend(dispatchId)

        suspend fun delete(dispatchId: Long) = routineDao.deleteDispatch(dispatchId)

        fun observeHistory(): Flow<List<RoutineDispatchEntity>> = routineDao.observeDispatches()

        suspend fun ping(routineId: Long): DispatchSubmission {
            val payload =
                payloadBuilder.build(
                    action = RoutineAction.PING,
                    instruction = PayloadBuilder.defaultInstruction(RoutineAction.PING),
                    papers = emptyList(),
                    includeNotes = false,
                    librarySize = libraryDao.count(),
                ) as PayloadResult.Ready
            val dispatchId =
                routineDao.insertDispatch(
                    RoutineDispatchEntity(
                        routineId = routineId,
                        action = RoutineAction.PING.wire,
                        paperCount = 0,
                        payloadJson = payload.json,
                        createdAt = Instant.now().toEpochMilli(),
                    ),
                )
            return attemptSend(dispatchId)
        }

        /** Shared by the UI path and DispatchWorker's queue drain. */
        suspend fun attemptSend(dispatchId: Long): DispatchSubmission {
            val dispatch =
                routineDao.dispatchById(dispatchId)
                    ?: return DispatchSubmission.Failed(dispatchId, "dispatch missing")
            val config =
                routineDao.configById(dispatch.routineId)
                    ?: return fail(dispatchId, null, "routine deleted")
            val token =
                tokenVault.retrieve(config.tokenAlias)
                    ?: return fail(dispatchId, null, "token unavailable — re-enter it")

            return when (val outcome = triggerClient.send(config.triggerUrl, token, dispatch.payloadJson)) {
                is TriggerOutcome.Accepted -> {
                    routineDao.updateDispatchStatus(
                        dispatchId,
                        RoutineDispatchEntity.STATUS_SENT,
                        outcome.httpCode,
                        null,
                        Instant.now().toEpochMilli(),
                    )
                    routineDao.markUsed(config.id, Instant.now().toEpochMilli())
                    DispatchSubmission.Sent(dispatchId)
                }
                is TriggerOutcome.AuthRejected -> {
                    routineDao.markAuthInvalid(config.id)
                    fail(dispatchId, outcome.httpCode, "token rejected")
                    DispatchSubmission.AuthRejected(dispatchId)
                }
                is TriggerOutcome.Rejected -> {
                    fail(dispatchId, outcome.httpCode, "rejected by endpoint")
                    DispatchSubmission.Failed(dispatchId, "HTTP ${outcome.httpCode}")
                }
                is TriggerOutcome.Retryable -> {
                    // Stays queued; DispatchWorker picks it up when network allows.
                    routineDao.updateDispatchStatus(
                        dispatchId,
                        RoutineDispatchEntity.STATUS_QUEUED,
                        outcome.httpCode,
                        outcome.message,
                        null,
                    )
                    DispatchSubmission.Queued(dispatchId)
                }
            }
        }

        suspend fun queuedDispatchIds(): List<Long> = routineDao.queuedDispatches().map { it.id }

        suspend fun pruneHistory() =
            routineDao.pruneOlderThan(Instant.now().minusSeconds(HISTORY_RETENTION_S).toEpochMilli())

        private suspend fun fail(
            dispatchId: Long,
            httpCode: Int?,
            error: String,
        ): DispatchSubmission {
            routineDao.updateDispatchStatus(dispatchId, RoutineDispatchEntity.STATUS_FAILED, httpCode, error, null)
            return DispatchSubmission.Failed(dispatchId, error)
        }

        private suspend fun buildPayload(
            action: RoutineAction,
            instruction: String,
            paperIds: List<String>,
            includeNotes: Boolean,
        ): PayloadResult {
            val annotated =
                paperIds.mapNotNull { id ->
                    val full = paperDao.paperWithRelations(id) ?: return@mapNotNull null
                    PaperWithAnnotations(
                        paper = full.toDomain(),
                        tags = libraryDao.observeTagsFor(id).first().map { it.name },
                        status = libraryDao.observeEntry(id).first()?.status,
                        rating = libraryDao.observeEntry(id).first()?.rating,
                        notes = libraryDao.notesFor(id).map { it.content },
                    )
                }
            return payloadBuilder.build(
                action = action,
                instruction = instruction,
                papers = annotated,
                includeNotes = includeNotes,
                librarySize = libraryDao.count(),
            )
        }

        companion object {
            private const val HISTORY_RETENTION_S = 90L * 24 * 3600
        }
    }
