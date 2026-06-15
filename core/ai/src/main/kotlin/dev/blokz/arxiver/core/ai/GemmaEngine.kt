package dev.blokz.arxiver.core.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.ml.ModelDownloader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Tier-2 on-device engine: Gemma 4 E2B (text-only, Apache-2.0) via LiteRT-LM
 * (SPEC-AI-PROVIDERS §3; API reference `docs/reference/on-device-ai.md`).
 *
 * Mirrors `EmbeddingService`'s lifecycle: a single `Mutex` guards lazy creation
 * of the native [Engine], whose `initialize()` is slow (~seconds) and runs on a
 * background dispatcher after the `.litertlm` model has been downloaded. The
 * model is **never bundled** — it's fetched on unmetered networks by the app's
 * download worker and verified by SHA-256 ([SPEC]).
 */
class GemmaEngine(
    private val modelDownloader: ModelDownloader,
    private val dispatchers: DispatcherProvider,
    private val cacheDir: File,
) : OnDeviceEngine {
    override val tier: InferenceTier = InferenceTier.GEMMA

    private val mutex = Mutex()
    private var engine: Engine? = null

    /** Ready once the model file is on disk; the engine itself is built lazily on first use. */
    override suspend fun isReady(): Boolean = modelDownloader.modelFile.exists()

    override fun generate(request: ChatRequest): Flow<ChatChunk> =
        flow {
            val activeEngine = ensureEngine()
            val config =
                request.system
                    ?.let { ConversationConfig(systemInstruction = Contents.of(it)) }
                    ?: ConversationConfig()
            activeEngine.createConversation(config).use { conversation ->
                conversation.sendMessageAsync(promptOf(request)).collect { message ->
                    val text = message.text()
                    if (text.isNotEmpty()) emit(ChatChunk.Delta(text))
                }
            }
            emit(ChatChunk.Done())
        }.catch { e ->
            throw if (e is AiException) e else AiException(AppError.Unexpected(e))
        }.flowOn(dispatchers.default)

    private suspend fun ensureEngine(): Engine =
        mutex.withLock {
            engine ?: run {
                val modelFile =
                    when (val result = modelDownloader.ensureDownloaded()) {
                        is AppResult.Success -> result.value
                        is AppResult.Failure -> throw AiException(result.error)
                    }
                Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = cacheDir.absolutePath,
                    ),
                ).also {
                    it.initialize()
                    engine = it
                }
            }
        }

    /** Single-turn prompt for now; multi-turn chat history arrives with the P2 chat UI. */
    private fun promptOf(request: ChatRequest): String =
        request.messages
            .filter { it.role != ChatRole.SYSTEM }
            .joinToString("\n\n") { it.content }

    private fun Message.text(): String =
        contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }

    companion object {
        /**
         * Pinned model: the **text-only** Gemma 4 E2B `.litertlm` from the LiteRT
         * community (Apache-2.0). ~1.87 GB download; Android runtime RAM ~1.4–1.7 GB.
         * The variant is WebGPU-tuned — if it fails to load on Android CPU, the
         * fallback is the standard `gemma-4-E2B-it.litertlm` (tracked in VERIFICATION.md).
         */
        val SPEC =
            ModelDownloader.ModelSpec(
                fileName = "gemma-4-E2B-it-web.litertlm",
                url =
                    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/" +
                        "resolve/main/gemma-4-E2B-it-web.litertlm",
                sha256 = "3a08e8d94e23b814ae5414469c370c503813949acb8ceaa17e4ebf8a35af35b5",
                // dimensions is embedding-specific and unused for an LLM.
                dimensions = 0,
                displayName = "Gemma 4 E2B (on-device, text-only)",
            )
    }
}
