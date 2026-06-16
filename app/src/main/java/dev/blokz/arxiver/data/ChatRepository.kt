package dev.blokz.arxiver.data

import dev.blokz.arxiver.chat.ChatContextAssembler
import dev.blokz.arxiver.chat.ChatPreview
import dev.blokz.arxiver.chat.ChatPreviewBuilder
import dev.blokz.arxiver.chat.ChatTurn
import dev.blokz.arxiver.core.ai.AiException
import dev.blokz.arxiver.core.ai.AiProvider
import dev.blokz.arxiver.core.ai.ChatChunk
import dev.blokz.arxiver.core.ai.ChatRequest
import dev.blokz.arxiver.core.ai.ChatRole
import dev.blokz.arxiver.core.ai.ProviderId
import dev.blokz.arxiver.core.ai.ProviderResolution
import dev.blokz.arxiver.core.ai.ProviderResolver
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
    internal val provider: AiProvider,
)

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

            val request = assembler.assemble(question, chunks, history, includeNotes, provider.capability)

            ChatPrepareResult.Ready(
                PreparedChat(
                    scope = scope,
                    sessionId = sessionId,
                    question = question,
                    request = request,
                    providerId = provider.id,
                    isCloud = provider.capability.requiresKey,
                    preview = previewBuilder.build(request),
                    provider = provider,
                ),
            )
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
                            chatDao.updateMessage(assistantId, answer.toString(), ChatMessageEntity.STATUS_COMPLETE)
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

    fun observeSessions(scope: RetrievalScope): Flow<List<ChatSessionEntity>> {
        val (scopeKind, scopeId) = scope.toRow()
        return chatDao.observeSessions(scopeKind, scopeId)
    }

    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>> = chatDao.observeMessages(sessionId)

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
