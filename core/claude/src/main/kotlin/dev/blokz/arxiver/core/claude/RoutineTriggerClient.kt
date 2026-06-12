package dev.blokz.arxiver.core.claude

import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * Fires a Claude routine's API trigger (SPEC-CLAUDE-BRIDGE §3). Real contract,
 * verified against the live endpoint (task 4.8):
 *
 * ```
 * POST https://api.anthropic.com/v1/claude_code/routines/{trigger_id}/fire
 * Authorization: Bearer <token>
 * anthropic-version: 2023-06-01
 * anthropic-beta: experimental-cc-routine-2026-04-01
 * Content-Type: application/json
 * {"text": "<extra turn appended to the routine session>"}
 * ```
 *
 * The arxiver/v1 payload travels as the `text` turn; the routine's starter
 * instructions teach Claude to parse it. One attempt per call — retry policy
 * lives with the dispatch queue.
 */
class RoutineTriggerClient(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {
    private val json = Json

    @Serializable
    private data class FireBody(val text: String)

    suspend fun send(
        triggerUrl: String,
        token: String,
        payloadJson: String,
    ): TriggerOutcome =
        withContext(dispatchers.io) {
            val body = json.encodeToString(FireBody.serializer(), FireBody(text = payloadJson))
            val request =
                Request.Builder()
                    .url(normalizeTriggerUrl(triggerUrl))
                    .header("Authorization", "Bearer $token")
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("anthropic-beta", ANTHROPIC_BETA)
                    .header("User-Agent", userAgent)
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
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
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val ANTHROPIC_BETA = "experimental-cc-routine-2026-04-01"

        /**
         * Users paste trigger URLs with or without the `/fire` suffix; both
         * must work. Only routine-trigger-shaped paths are touched.
         */
        fun normalizeTriggerUrl(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            return if (trimmed.contains("/routines/") && !trimmed.endsWith("/fire")) {
                "$trimmed/fire"
            } else {
                trimmed
            }
        }
    }
}

/**
 * Paste-ready instruction block for the user's routine (SPEC-CLAUDE-BRIDGE §2
 * "copy routine starter instructions").
 */
object RoutineStarterInstructions {
    fun generate(): String =
        """
        |You may receive messages from Arxiver, a local-first arXiv research app, via this
        |routine's API trigger. They are easy to recognize:
        |
        |- "ARXIVER CONNECTIVITY TEST" — a ping from the app's Test button. Skip your
        |  normal instructions, reply with one short acknowledgement, and stop.
        |- "ARXIVER RESEARCH DISPATCH" — a real task. The message states the action and
        |  my instruction, lists the papers, and includes the full research payload as
        |  fenced JSON (schema "arxiver/v1") with per-paper title, authors, abstract,
        |  categories, links (abs_url / pdf_url), and optionally my tags/status/rating/
        |  notes under "user".
        |
        |For research dispatches:
        |- Follow the "MY INSTRUCTION FOR THIS RUN" section first.
        |- Actions: digest, deep_dive, compare, weekly_review, literature_scan, custom.
        |- The payload may include "relations": analysis primitives computed on my
        |  device — "similarity" (pairwise embedding cosine between the selected
        |  papers), "citations" (citation edges within the selection), and
        |  "library_neighbors" (papers from my local corpus semantically nearest to
        |  each selection, with "near" naming the anchor). Compose these to ground
        |  comparisons and spot clusters instead of re-deriving them from text.
        |- For deep_dive, fetch the PDF from pdf_url and analyze the full text.
        |- Use my notes and tags (when present) to match my framing and interests.
        |- Deliver results the way this routine is configured to (e.g. email, Drive doc).
        |- Keep digests structured: TL;DR, contributions, methods, limitations.
        """.trimMargin()
}
