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
            var emittedAny = false
            activeEngine.createConversation(config).use { conversation ->
                conversation.sendMessageAsync(promptOf(request)).collect { message ->
                    val text = message.text()
                    if (text.isNotEmpty()) {
                        emittedAny = true
                        emit(ChatChunk.Delta(text))
                    }
                }
            }
            // A stream that produced zero tokens is a failure (e.g. a model with no usable decode
            // graph for this backend) — surface it instead of completing with a silent blank answer.
            if (!emittedAny) throw AiException(AppError.Unexpected())
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
         * Pinned model: the **standard** (CPU/GPU) Gemma 4 E2B `.litertlm` from the LiteRT
         * community (Apache-2.0), ~2.59 GB. The earlier `-web` variant is WebGPU-only — it ships
         * a single `gpu_artisan` decoder with **no CPU `TF_LITE_PREFILL_DECODE` graph**, so on a
         * CPU-backend device (e.g. Galaxy S20, NPU registration fails) it loads but generates zero
         * tokens. This standard build carries the CPU prefill/decode graph (XNNPACK). Verified on
         * device → VERIFICATION.md §J6/§K3. The model is never bundled — fetched on unmetered
         * networks by the download worker and SHA-256-verified.
         */
        val SPEC =
            ModelDownloader.ModelSpec(
                fileName = "gemma-4-E2B-it.litertlm",
                url =
                    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/" +
                        "resolve/main/gemma-4-E2B-it.litertlm",
                sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
                // dimensions is embedding-specific and unused for an LLM.
                dimensions = 0,
                displayName = "Gemma 4 E2B (on-device, text-only)",
            )
    }
}
