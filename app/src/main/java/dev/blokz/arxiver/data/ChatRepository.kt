package dev.blokz.arxiver.data

import dev.blokz.arxiver.chat.ChatContextAssembler
import dev.blokz.arxiver.chat.ChatMode
import dev.blokz.arxiver.chat.ChatPreview
import dev.blokz.arxiver.chat.ChatPreviewBuilder
import dev.blokz.arxiver.chat.ChatTurn
import dev.blokz.arxiver.chat.extractFollowUps
import dev.blokz.arxiver.core.ai.AiException
import dev.blokz.arxiver.core.ai.AiProvider
import dev.blokz.arxiver.core.ai.ChatChunk
import dev.blokz.arxiver.core.ai.ChatImage
import dev.blokz.arxiver.core.ai.ChatRequest
import dev.blokz.arxiver.core.ai.ChatRole
import dev.blokz.arxiver.core.ai.OnDeviceProvider
import dev.blokz.arxiver.core.ai.OutputRichness
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.ai.ProviderResolution
import dev.blokz.arxiver.core.ai.ProviderResolver
import dev.blokz.arxiver.core.ai.StructuredTableTransform
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.dao.ChatDao
import dev.blokz.arxiver.core.database.entity.ChatMessageEntity
import dev.blokz.arxiver.core.database.entity.ChatSessionEntity
import dev.blokz.arxiver.core.search.RagRetriever
import dev.blokz.arxiver.core.search.RetrievalScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A cited source backing an answer's `[index]` reference (P-Rich R0 — render-only). */
data class Citation(val index: Int, val paperId: String, val excerpt: String)

/** A resolved, ready-to-stream chat turn (everything embedded/retrieved/assembled). */
data class PreparedChat(
    val scope: RetrievalScope,
    val sessionId: Long?,
    val question: String,
    val request: ChatRequest,
    val providerId: ProviderId,
    /** Cloud providers require a "what leaves the device" confirm; on-device sends nothing. */
    val isCloud: Boolean,
    val preview: ChatPreview,
    /** The chunks cited as `[1..n]` in [request], so the UI can link `[n]` to its source. */
    val citations: List<Citation>,
    /** The resolved output richness of the turn — gates the PA.4 STRUCTURED table transform on settle. */
    val richness: OutputRichness,
    internal val provider: AiProvider,
)

/**
 * Render the PA.4 `TABLE::` intermediate to a valid table/list — but ONLY on a STRUCTURED turn
 * (else identity, so cloud FULL / the PLAIN tier / ordinary prose are byte-identical). Shared by the
 * repository's persist path and the ViewModel's display path so the DB and the UI never diverge.
 */
internal fun settleStructured(
    prepared: PreparedChat,
    strippedBody: String,
): String =
    if (prepared.richness == OutputRichness.STRUCTURED) {
        StructuredTableTransform.transform(strippedBody)
    } else {
        strippedBody
    }

/** Outcome of [ChatRepository.prepare]. */
sealed interface ChatPrepareResult {
    data class Ready(val prepared: PreparedChat) : ChatPrepareResult

    /** No usable provider — UI should prompt the user to configure one (K5). */
    data object NotConfigured : ChatPrepareResult

    data class Failed(val error: AppError) : ChatPrepareResult
}

/**
 * Orchestrates a grounded chat turn (SPEC-AI-PROVIDERS chat orchestration, P2.2):
 * embed → retrieve (RagRetriever) → assemble ChatRequest → resolve provider →
 * stream → persist. [prepare] is read-only so declining the privacy preview
 * persists nothing; [stream] persists the user turn + the assistant turn (with a
 * `status` that survives cancel/error). Pure-JVM testable with a fake [AiProvider]
 * + in-memory DB; embedding is an injected seam so CI stays ONNX-free.
 */
class ChatRepository(
    private val chatDao: ChatDao,
    private val ragRetriever: RagRetriever,
    private val providerResolver: ProviderResolver,
    private val assembler: ChatContextAssembler,
    private val previewBuilder: ChatPreviewBuilder,
    private val embedQuery: suspend (String) -> AppResult<FloatArray>,
    private val dispatchers: DispatcherProvider,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun prepare(
        scope: RetrievalScope,
        sessionId: Long?,
        question: String,
        includeNotes: Boolean,
        mode: ChatMode = ChatMode.STANDARD,
        attachment: ChatImage? = null,
    ): ChatPrepareResult =
        withContext(dispatchers.io) {
            val provider =
                when (val resolution = providerResolver.resolve()) {
                    is ProviderResolution.Resolved -> resolution.provider
                    ProviderResolution.NotConfigured -> return@withContext ChatPrepareResult.NotConfigured
                }

            // Embedding failure (e.g. model not ready) degrades to keyword-only retrieval.
            val queryVector =
                when (val embedded = embedQuery(question)) {
                    is AppResult.Success -> embedded.value
                    is AppResult.Failure -> null
                }
            val chunks = ragRetriever.retrieve(queryVector, question, scope)

            val history =
                sessionId
                    ?.let { chatDao.messagesFor(it) }
                    ?.filter { it.status == ChatMessageEntity.STATUS_COMPLETE }
                    ?.map { ChatTurn(it.role.toRole(), it.content) }
                    .orEmpty()

            // Resolve the per-turn richness from the engine that will actually run (P-Atlas PA.2):
            // OnDeviceProvider's static capability is a PLAIN placeholder, so override it with the
            // picked engine's richness (Gemma → STRUCTURED, Nano → PLAIN). Cloud capability is static.
            val capability =
                if (provider is OnDeviceProvider) {
                    provider.capability.copy(richness = provider.resolveRichness())
                } else {
                    provider.capability
                }

            // The assembler drops the image unless capability.vision is true (R3d M2/M3),
            // so an attachment never reaches a non-vision/on-device provider.
            val assembled =
                assembler.assemble(question, chunks, history, includeNotes, capability, mode, attachment)
            val citations = assembled.citedChunks.mapIndexed { i, c -> Citation(i + 1, c.paperId, c.text) }

            ChatPrepareResult.Ready(
                PreparedChat(
                    scope = scope,
                    sessionId = sessionId,
                    question = question,
                    request = assembled.request,
                    providerId = provider.id,
                    isCloud = provider.capability.requiresKey,
                    preview = previewBuilder.build(assembled.request),
                    citations = citations,
                    richness = capability.richness,
                    provider = provider,
                ),
            )
        }

    /**
     * Read-only: is the provider that would handle a turn right now vision-capable? Drives
     * R3d.4 preset gating at sheet-open AND the action-time re-check in `runVisionPreset` (no
     * embed/retrieve/assemble/persist, no network — provider resolution is local store reads
     * only, so it never trips the arXiv limiter). NotConfigured → false (no usable provider).
     */
    suspend fun resolveVisionCapable(): Boolean =
        withContext(dispatchers.io) {
            when (val resolution = providerResolver.resolve()) {
                is ProviderResolution.Resolved -> resolution.provider.capability.vision
                ProviderResolution.NotConfigured -> false
            }
        }

    /**
     * Streams the assistant reply, persisting turns. Re-emits the provider's
     * [ChatChunk]s; an [AiException] propagates after the partial turn is saved as
     * `error`, and cancellation leaves the partial as `incomplete`.
     */
    fun stream(prepared: PreparedChat): Flow<ChatChunk> =
        flow {
            val now = clock()
            val (scopeKind, scopeId) = prepared.scope.toRow()
            val sessionId =
                prepared.sessionId ?: chatDao.insertSession(
                    ChatSessionEntity(
                        scope = scopeKind,
                        scopeId = scopeId,
                        providerId = prepared.providerId.name,
                        createdAt = now,
                        lastMessageAt = now,
                    ),
                )

            chatDao.insertMessage(
                ChatMessageEntity(
                    sessionId = sessionId,
                    role = ChatMessageEntity.ROLE_USER,
                    content = prepared.question,
                    status = ChatMessageEntity.STATUS_COMPLETE,
                    createdAt = now,
                ),
            )
            val assistantId =
                chatDao.insertMessage(
                    ChatMessageEntity(
                        sessionId = sessionId,
                        role = ChatMessageEntity.ROLE_ASSISTANT,
                        content = "",
                        status = ChatMessageEntity.STATUS_INCOMPLETE,
                        createdAt = clock(),
                    ),
                )

            val answer = StringBuilder()
            try {
                prepared.provider.chat(prepared.request).collect { chunk ->
                    when (chunk) {
                        is ChatChunk.Delta -> answer.append(chunk.text)
                        is ChatChunk.Done ->
                            // Persist the settled body: strip any model FOLLOWUPS:: sentinel (P-Rich
                            // R3b.2), then on a STRUCTURED turn render the TABLE:: intermediate to a
                            // valid table/list (PA.4). The ViewModel display path runs the SAME two
                            // transforms on the same input, so DB == UI == export (no resurrection).
                            chatDao.updateMessage(
                                assistantId,
                                settleStructured(prepared, extractFollowUps(answer.toString()).first),
                                ChatMessageEntity.STATUS_COMPLETE,
                            )
                    }
                    emit(chunk)
                }
            } catch (e: AiException) {
                finalize(assistantId, answer.toString(), ChatMessageEntity.STATUS_ERROR, sessionId)
                throw e
            } catch (e: CancellationException) {
                finalize(assistantId, answer.toString(), ChatMessageEntity.STATUS_INCOMPLETE, sessionId)
                throw e
            }
            chatDao.touchSession(sessionId, clock())
        }.flowOn(dispatchers.io)

    /**
     * Persist a complete, **app-composed** turn (P-Atlas PA.1) with **no provider call**: a
     * `STATUS_COMPLETE` user turn ([question]) + a `STATUS_COMPLETE` assistant turn carrying
     * [content] (e.g. an app-drawn Mermaid graph). Bypasses `prepare()`/`stream()`/the privacy
     * preview entirely — nothing is sent off-device — so the redaction goldens are untouched.
     * Returns the new-or-reused session id. `providerId` is recorded as on-device (the artifact is
     * computed locally) to keep the history-list label honest without a real provider.
     */
    suspend fun insertArtifactTurn(
        scope: RetrievalScope,
        sessionId: Long?,
        question: String,
        content: String,
    ): Long =
        withContext(dispatchers.io) {
            val now = clock()
            val (scopeKind, scopeId) = scope.toRow()
            val sid =
                sessionId ?: chatDao.insertSession(
                    ChatSessionEntity(
                        scope = scopeKind,
                        scopeId = scopeId,
                        providerId = ProviderId.ON_DEVICE.name,
                        createdAt = now,
                        lastMessageAt = now,
                    ),
                )
            chatDao.insertMessage(
                ChatMessageEntity(
                    sessionId = sid,
                    role = ChatMessageEntity.ROLE_USER,
                    content = question,
                    status = ChatMessageEntity.STATUS_COMPLETE,
                    createdAt = now,
                ),
            )
            chatDao.insertMessage(
                ChatMessageEntity(
                    sessionId = sid,
                    role = ChatMessageEntity.ROLE_ASSISTANT,
                    content = content,
                    status = ChatMessageEntity.STATUS_COMPLETE,
                    createdAt = clock(),
                ),
            )
            chatDao.touchSession(sid, clock())
            sid
        }

    fun observeSessions(scope: RetrievalScope): Flow<List<ChatSessionEntity>> {
        val (scopeKind, scopeId) = scope.toRow()
        return chatDao.observeSessions(scopeKind, scopeId)
    }

    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>> = chatDao.observeMessages(sessionId)

    /** All sessions across scopes, most-recently-active first (chat-history list). */
    fun observeAllSessions(): Flow<List<ChatSessionEntity>> = chatDao.observeAllSessions()

    suspend fun deleteSession(id: Long) = withContext(dispatchers.io) { chatDao.deleteSession(id) }

    /** Persist a partial/failed turn even if the surrounding coroutine is cancelled. */
    private suspend fun finalize(
        messageId: Long,
        content: String,
        status: String,
        sessionId: Long,
    ) = withContext(NonCancellable) {
        chatDao.updateMessage(messageId, content, status)
        chatDao.touchSession(sessionId, clock())
    }

    private fun String.toRole(): ChatRole =
        if (this == ChatMessageEntity.ROLE_ASSISTANT) ChatRole.ASSISTANT else ChatRole.USER

    private fun RetrievalScope.toRow(): Pair<String, String> =
        when (this) {
            is RetrievalScope.Paper -> ChatSessionEntity.SCOPE_PAPER to paperId
            is RetrievalScope.Collection -> ChatSessionEntity.SCOPE_COLLECTION to collectionId.toString()
        }
}
