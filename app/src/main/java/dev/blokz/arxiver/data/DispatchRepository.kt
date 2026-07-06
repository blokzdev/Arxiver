package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.claude.PaperWithAnnotations
import dev.blokz.arxiver.core.claude.PayloadBuilder
import dev.blokz.arxiver.core.claude.PayloadCitationEdge
import dev.blokz.arxiver.core.claude.PayloadNeighbor
import dev.blokz.arxiver.core.claude.PayloadRelations
import dev.blokz.arxiver.core.claude.PayloadResult
import dev.blokz.arxiver.core.claude.PayloadSimilarityEdge
import dev.blokz.arxiver.core.claude.RoutineAction
import dev.blokz.arxiver.core.claude.RoutineTriggerClient
import dev.blokz.arxiver.core.claude.TokenVault
import dev.blokz.arxiver.core.claude.TriggerOutcome
import dev.blokz.arxiver.core.database.dao.CitationDao
import dev.blokz.arxiver.core.database.dao.EmbeddingDao
import dev.blokz.arxiver.core.database.dao.LibraryDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.database.dao.RoutineDao
import dev.blokz.arxiver.core.database.entity.PaperEmbeddingEntity
import dev.blokz.arxiver.core.database.entity.RoutineConfigEntity
import dev.blokz.arxiver.core.database.entity.RoutineDispatchEntity
import dev.blokz.arxiver.core.database.toDomain
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.PaperRef
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.search.dotSimilarity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

sealed interface DispatchSubmission {
    data class Sent(val dispatchId: Long) : DispatchSubmission

    /** [httpCode] present for 5xx-queued, null for offline/transport. */
    data class Queued(val dispatchId: Long, val httpCode: Int? = null) : DispatchSubmission

    data class Failed(val dispatchId: Long, val reason: String, val httpCode: Int? = null) : DispatchSubmission

    data class AuthRejected(val dispatchId: Long) : DispatchSubmission

    data class PayloadTooLarge(val byteSize: Int, val limit: Int) : DispatchSubmission

    /** A non-PING selection that built to zero papers (all filtered out) — nothing was POSTed. */
    data object NothingToDispatch : DispatchSubmission
}

/**
 * A non-PING dispatch whose selection was non-empty but built to ZERO papers (every selected paper was
 * dropped — e.g. an all-non-arXiv selection filtered out by [DispatchRepository]'s arXiv-only payload
 * chokepoint) must NOT POST a paperless envelope. PING legitimately carries zero papers, so it is never
 * suppressed. Pure so the guard is unit-testable without the repository's Room/TokenVault graph.
 */
internal fun isEmptyNonPingDispatch(
    requestedPaperCount: Int,
    builtPaperCount: Int,
    action: RoutineAction,
): Boolean = requestedPaperCount > 0 && builtPaperCount == 0 && action != RoutineAction.PING

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
        private val citationDao: CitationDao,
        private val embeddingDao: EmbeddingDao,
    ) : RoutineSetupGateway {
        // --- routine configs ---

        fun observeRoutines(): Flow<List<RoutineConfigEntity>> = routineDao.observeConfigs()

        override suspend fun addRoutine(
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

        override suspend fun updateRoutine(
            routineId: Long,
            name: String,
            triggerUrl: String,
        ) {
            val config = routineDao.configById(routineId) ?: return
            routineDao.updateConfig(config.copy(name = name.trim(), triggerUrl = triggerUrl.trim()))
        }

        override suspend fun replaceToken(
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
            if (isEmptyNonPingDispatch(paperIds.size, ready.paperCount, action)) {
                // Every selected paper was filtered out (e.g. an all-non-arXiv selection). Don't record or
                // POST a paperless envelope — surface "nothing to dispatch" to the caller instead.
                return DispatchSubmission.NothingToDispatch
            }
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

        override suspend fun ping(routineId: Long): DispatchSubmission {
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
                    ?: return fail(dispatchId, null, REASON_TOKEN_UNAVAILABLE)

            val turnText = dev.blokz.arxiver.core.claude.DispatchEnvelope.render(dispatch.payloadJson)
            return when (val outcome = triggerClient.send(config.triggerUrl, token, turnText)) {
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
                    DispatchSubmission.Failed(dispatchId, "HTTP ${outcome.httpCode}", outcome.httpCode)
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
                    DispatchSubmission.Queued(dispatchId, outcome.httpCode)
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
            return DispatchSubmission.Failed(dispatchId, error, httpCode)
        }

        private suspend fun buildPayload(
            action: RoutineAction,
            instruction: String,
            paperIds: List<String>,
            includeNotes: Boolean,
        ): PayloadResult {
            val annotated =
                paperIds
                    // PS.1: routine payloads are arXiv-shaped (SPEC-CLAUDE-BRIDGE — eprint id + arxiv.org
                    // URL). A non-arXiv (chemRxiv) paper is excluded here rather than dispatched with a
                    // mangled arxiv.org URL; this single chokepoint covers every dispatch entry point
                    // (detail, multi-select Library/Filtered/Explore). Source-aware payloads are PS.2.
                    .filter { PaperRef.fromStorageId(it).origin == Source.ARXIV }
                    .mapNotNull { id ->
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
                relations = relationsFor(annotated.map { it.paper.ref.storageId }),
            )
        }

        /**
         * On-device analysis primitives for the routine to compose
         * (SPEC-CLAUDE-BRIDGE §4 `relations`): pairwise embedding cosine and
         * citation edges within the selection, plus each paper's precomputed
         * corpus neighbors. The builder gates neighbors behind the notes toggle.
         */
        private suspend fun relationsFor(paperIds: List<String>): PayloadRelations? {
            if (paperIds.isEmpty()) return null
            val vectors =
                paperIds.mapNotNull { id ->
                    embeddingDao.byPaperId(id)?.let { id to PaperEmbeddingEntity.blobToFloats(it.vector) }
                }
            val similarity =
                buildList {
                    for (i in vectors.indices) {
                        for (j in i + 1 until vectors.size) {
                            val (idA, a) = vectors[i]
                            val (idB, b) = vectors[j]
                            if (a.size == b.size) add(PayloadSimilarityEdge(idA, idB, dotSimilarity(a, b).round3()))
                        }
                    }
                }
            val citations =
                citationDao.edgesAmong(paperIds).map { PayloadCitationEdge(it.citingId, it.citedId) }
            val selection = paperIds.toSet()
            val neighbors =
                paperIds.flatMap { id ->
                    embeddingDao.neighborsFor(id, NEIGHBORS_PER_PAPER)
                        // PS.1: a non-arXiv neighbor would synthesize a mangled arxiv.org URL below — exclude
                        // it. Source-aware neighbor URLs ship with the rest of the payload work in PS.2.
                        .filter { it.paper.id !in selection && it.paper.origin == Source.ARXIV.wire }
                        .map {
                            PayloadNeighbor(
                                arxivId = it.paper.id,
                                near = id,
                                title = it.paper.title,
                                cosine = it.similarity.round3(),
                                inLibrary = it.in_library,
                                absUrl = ArxivId(it.paper.id).absUrl(it.paper.latestVersion),
                            )
                        }
                }
            return PayloadRelations(similarity, citations, neighbors.takeIf { it.isNotEmpty() })
                .takeUnless { it.isEmpty() }
        }

        private fun Double.round3(): Double = (this * 1000).roundToInt() / 1000.0

        companion object {
            private const val HISTORY_RETENTION_S = 90L * 24 * 3600
            private const val NEIGHBORS_PER_PAPER = 3

            /** Vault miss — referenced by the verification error mapper. */
            const val REASON_TOKEN_UNAVAILABLE = "token unavailable — re-enter it"
        }
    }
