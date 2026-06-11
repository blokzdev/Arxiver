package dev.blokz.arxiver.core.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.common.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * On-device text embeddings via ONNX Runtime (SPEC-SEARCH §6).
 *
 * bge-small conventions: CLS-token pooling, L2 normalization, and the
 * retrieval instruction prefix applied to queries only.
 */
class EmbeddingService(
    private val modelDownloader: ModelDownloader,
    private val tokenizerProvider: () -> WordPieceTokenizer,
    private val dispatchers: DispatcherProvider,
) {
    private val mutex = Mutex()
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null

    val dimensions: Int get() = modelDownloader.run { ModelDownloader.ModelSpec.BGE_SMALL_EN_V15_Q8.dimensions }

    suspend fun embedQuery(text: String): AppResult<FloatArray> =
        embedBatch(listOf(QUERY_PREFIX + text)).map { it.first() }

    suspend fun embedPassages(texts: List<String>): AppResult<List<FloatArray>> = embedBatch(texts)

    private suspend fun embedBatch(texts: List<String>): AppResult<List<FloatArray>> =
        withContext(dispatchers.default) {
            ensureSession().map { activeSession ->
                val tok = checkNotNull(tokenizer)
                // Serialized: one inference at a time keeps peak memory bounded.
                mutex.withLock {
                    val encodings = texts.map { tok.encode(it) }
                    val (ids, mask, maxLen) = tok.pad(encodings)
                    val env = OrtEnvironment.getEnvironment()
                    val batch = texts.size.toLong()
                    val shape = longArrayOf(batch, maxLen.toLong())

                    OnnxTensor.createTensor(env, LongBuffer.wrap(ids.flatten()), shape).use { idsTensor ->
                        OnnxTensor.createTensor(env, LongBuffer.wrap(mask.flatten()), shape).use { maskTensor ->
                            OnnxTensor.createTensor(
                                env,
                                LongBuffer.wrap(LongArray(ids.size * maxLen)),
                                shape,
                            ).use { typeTensor ->
                                val inputs =
                                    mapOf(
                                        "input_ids" to idsTensor,
                                        "attention_mask" to maskTensor,
                                        "token_type_ids" to typeTensor,
                                    )
                                activeSession.run(inputs).use { outputs ->
                                    @Suppress("UNCHECKED_CAST")
                                    val hidden = outputs[0].value as Array<Array<FloatArray>>
                                    hidden.map { sequence -> sequence[0].l2Normalized() } // CLS pooling
                                }
                            }
                        }
                    }
                }
            }
        }

    private suspend fun ensureSession(): AppResult<OrtSession> =
        modelDownloader.ensureDownloaded().map { file ->
            mutex.withLock {
                session ?: run {
                    val env = OrtEnvironment.getEnvironment()
                    val options =
                        OrtSession.SessionOptions().apply {
                            setIntraOpNumThreads(INFERENCE_THREADS)
                        }
                    env.createSession(file.absolutePath, options).also {
                        session = it
                        tokenizer = tokenizerProvider()
                    }
                }
            }
        }

    private fun Array<LongArray>.flatten(): LongArray {
        val rowLength = firstOrNull()?.size ?: 0
        val out = LongArray(size * rowLength)
        forEachIndexed { i, row -> row.copyInto(out, i * rowLength) }
        return out
    }

    private fun FloatArray.l2Normalized(): FloatArray {
        var sum = 0f
        for (v in this) sum += v * v
        val norm = sqrt(sum)
        if (norm == 0f) return this
        return FloatArray(size) { this[it] / norm }
    }

    companion object {
        /** BGE retrieval convention — queries only, never passages. */
        const val QUERY_PREFIX = "Represent this sentence for searching relevant passages: "
        private const val INFERENCE_THREADS = 2

        /** Embedding input per SPEC-SEARCH §6. */
        fun passageText(
            title: String,
            abstract: String,
        ): String = "$title\n$abstract"
    }
}
