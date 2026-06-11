package dev.blokz.arxiver.core.claude

import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** SPEC-CLAUDE-BRIDGE §3 outcome classes — they drive retry/queue behavior. */
sealed interface TriggerOutcome {
    /** Any 2xx — the routine run was accepted. */
    data class Accepted(val httpCode: Int) : TriggerOutcome

    /** 401/403 — token revoked or wrong; surface re-auth, never retry. */
    data class AuthRejected(val httpCode: Int) : TriggerOutcome

    /** Other 4xx — permanent failure, no retry. */
    data class Rejected(val httpCode: Int) : TriggerOutcome

    /** 5xx or transport failure — retryable, queue for DispatchWorker. */
    data class Retryable(val httpCode: Int?, val message: String?) : TriggerOutcome
}

/**
 * Posts payloads to a Claude routine's API trigger (SPEC-CLAUDE-BRIDGE §3).
 * One attempt per call — retry policy lives with the dispatch queue. The
 * transport details (Bearer header, JSON body) are isolated here by design:
 * if the real trigger contract differs, this is the one class to adapt.
 */
class RoutineTriggerClient(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {
    suspend fun send(
        triggerUrl: String,
        token: String,
        payloadJson: String,
    ): TriggerOutcome =
        withContext(dispatchers.io) {
            val request =
                Request.Builder()
                    .url(triggerUrl)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", userAgent)
                    .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> TriggerOutcome.Accepted(response.code)
                        response.code == 401 || response.code == 403 ->
                            TriggerOutcome.AuthRejected(response.code)
                        response.code in 500..599 ->
                            TriggerOutcome.Retryable(response.code, null)
                        else -> TriggerOutcome.Rejected(response.code)
                    }
                }
            }.getOrElse { e ->
                when (e) {
                    is IOException -> TriggerOutcome.Retryable(null, e.message)
                    else -> TriggerOutcome.Rejected(-1)
                }
            }
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DEFAULT_USER_AGENT = "Arxiver/0.1 (https://github.com/blokzdev/arxiver)"
    }
}

/**
 * Paste-ready instruction block for the user's routine (SPEC-CLAUDE-BRIDGE §2
 * "copy routine starter instructions").
 */
object RoutineStarterInstructions {
    fun generate(): String =
        """
        |You receive POST requests from Arxiver, a local-first arXiv research app.
        |Each request body is JSON with schema "arxiver/v1":
        |
        |- "action": one of digest, deep_dive, compare, weekly_review, literature_scan, custom, ping
        |- "instruction": what I want you to do this run (always read this first)
        |- "papers": arXiv papers with title, authors, abstract, categories, links
        |  (abs_url / pdf_url), and optionally "user" with my tags, status, rating, and notes
        |- "context.library_size": how many papers are in my library overall
        |
        |Guidelines:
        |- For "ping", reply with a short acknowledgement and do nothing else.
        |- For "deep_dive", fetch the PDF from pdf_url and analyze the full text.
        |- Use my notes and tags (when present) to match my framing and interests.
        |- Deliver results the way this routine is configured to (e.g. email, Drive doc).
        |- Keep digests structured: TL;DR, contributions, methods, limitations.
        """.trimMargin()
}
