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
 * Tier-LIGHT on-device engine: Qwen3-0.6B (Apache-2.0) via LiteRT-LM (P-Atlas PA.3). The **floor of
 * capable on-device** — a sub-GB model for the ~2–4 GB device segment that can't meet Gemma's RAM
 * floor (`DeviceCapability.LIGHT_RAM_FLOOR_MB`). It is *additive*, never a replacement: on a
 * Gemma-capable device Gemma wins (it ranks first in [TierSelector.fallbackOrder]).
 *
 * Identical lifecycle to [GemmaEngine] — a single [Mutex] guards lazy creation of the native [Engine]
 * (slow `initialize()`, background dispatcher), the model is never bundled (downloaded + SHA-256
 * verified), and a **zero-token stream is treated as a failure** (the F2 CPU-graph trap: a model with
 * no usable CPU decode graph loads but emits nothing — surface it, don't return a blank answer).
 *
 * `richness = PLAIN` and it means it: a 0.6B model emits valid structured tables far less reliably
 * than Gemma E2B (PA.0 research), so this tier stays prose-first and is never nudged toward tables.
 */
class QwenEngine(
    private val modelDownloader: ModelDownloader,
    private val dispatchers: DispatcherProvider,
    private val cacheDir: File,
) : OnDeviceEngine {
    override val tier: InferenceTier = InferenceTier.LIGHT

    override val richness: OutputRichness = OutputRichness.PLAIN

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
         * Pinned model: the **standard CPU** Qwen3-0.6B `.litertlm` (INT8, 614 MB) from the LiteRT
         * community (Apache-2.0), community-benchmarked decoding on CPU. Pinned by exact filename +
         * SHA-256 so we never pick up the repo's `Qwen3-0.6B.mediatek.mt6993.litertlm` sibling — that
         * one is **NPU-only** (the F2-trap analog: it would load but emit zero tokens on a CPU device).
         * PA.0b (`VERIFICATION.md` K10) confirms CPU load + non-zero tokens in our wrapper.
         */
        val SPEC =
            ModelDownloader.ModelSpec(
                fileName = "Qwen3-0.6B.litertlm",
                url =
                    "https://huggingface.co/litert-community/Qwen3-0.6B/" +
                        "resolve/main/Qwen3-0.6B.litertlm",
                sha256 = "555579ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4",
                // dimensions is embedding-specific and unused for an LLM.
                dimensions = 0,
                displayName = "Qwen3 0.6B (on-device light, text-only)",
            )
    }
}
