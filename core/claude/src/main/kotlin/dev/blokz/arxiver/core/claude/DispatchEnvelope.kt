package dev.blokz.arxiver.core.claude

import kotlinx.serialization.json.Json

/**
 * Renders the text turn that actually reaches the routine session
 * (SPEC-CLAUDE-BRIDGE §3.1). Field-verified lesson: routines have their own
 * instructions and have never seen ours, so a bare JSON blob gets ignored or
 * misread. Every dispatch is therefore self-describing — a human-readable
 * header that tells Claude what this is and what to do, with the canonical
 * arxiver/v1 JSON fenced inside.
 *
 * Pings are special: firing a trigger ALWAYS starts a real run (the API has
 * no dry-run), so the ping turn's whole job is to stand the routine down.
 */
object DispatchEnvelope {
    private val json =
        Json {
            ignoreUnknownKeys = true
            // PayloadBuilder omits null fields (explicitNulls=false); mirror that
            // on decode so absent nullable keys (e.g. doi) read back as null.
            explicitNulls = false
        }
    private const val MAX_LISTED_PAPERS = 10

    fun render(payloadJson: String): String {
        val payload =
            runCatching {
                json.decodeFromString(ArxiverPayload.serializer(), payloadJson)
            }.getOrNull() ?: return fallback(payloadJson)

        return if (payload.action == RoutineAction.PING.wire) ping() else dispatch(payload, payloadJson)
    }

    private fun ping(): String =
        buildString {
            appendLine("ARXIVER CONNECTIVITY TEST")
            appendLine()
            appendLine("This run was started by the \"Test\" button in the Arxiver app to verify this")
            appendLine("trigger's URL and token. It is NOT a real task.")
            appendLine()
            appendLine("IMPORTANT: skip your routine's normal instructions for this run. Do not read,")
            appendLine("modify, or produce anything. Reply with exactly one short line confirming the")
            append("ping was received, then end the run.")
        }

    private fun dispatch(
        payload: ArxiverPayload,
        payloadJson: String,
    ): String =
        buildString {
            appendLine("ARXIVER RESEARCH DISPATCH (schema arxiver/v1)")
            appendLine()
            appendLine("Sent from my Arxiver app. Action: ${payload.action} · Papers attached: ${payload.papers.size}")
            appendLine()
            appendLine("MY INSTRUCTION FOR THIS RUN:")
            appendLine(payload.instruction.ifBlank { "(none provided — act on the \"${payload.action}\" intent)" })
            if (payload.papers.isNotEmpty()) {
                appendLine()
                appendLine("PAPERS:")
                payload.papers.take(MAX_LISTED_PAPERS).forEach { paper ->
                    // Source-aware label (P-Dispatch): arXiv papers show arXiv:<id>; a non-arXiv paper shows
                    // <source>:<native_id> — never "arXiv:null".
                    val ref = paper.arxivId?.let { "arXiv:$it" } ?: "${paper.source}:${paper.nativeId}"
                    appendLine("- ${paper.title} ($ref)")
                }
                if (payload.papers.size > MAX_LISTED_PAPERS) {
                    appendLine("- …and ${payload.papers.size - MAX_LISTED_PAPERS} more")
                }
            }
            appendLine()
            appendLine("The complete research payload follows as JSON. It contains, per paper: title,")
            appendLine("authors, abstract, categories, links, citation counts, and optionally my own")
            appendLine("tags/status/rating/notes under \"user\". Each paper is EITHER an arXiv paper (it")
            appendLine("has an \"arxiv_id\" with arxiv.org \"abs_url\"/\"pdf_url\") OR a non-arXiv preprint")
            appendLine("(it has \"source\"+\"native_id\"+\"url\" instead). A paper with \"pdf_fetchable\":")
            appendLine("false likely can't have its full PDF retrieved — work from the abstract for that one.")
            if (payload.relations != null && !payload.relations.isEmpty()) {
                appendLine("It also includes \"relations\" — analysis precomputed on my device (embedding")
                appendLine("similarity, citation edges, library neighbors): compose these instead of")
                appendLine("re-deriving relationships from the text.")
            }
            appendLine("Work from this payload; fetch a paper's PDF from \"pdf_url\" when full text is")
            appendLine("needed and its \"pdf_fetchable\" isn't false.")
            appendLine()
            appendLine("```json")
            appendLine(payloadJson)
            append("```")
        }

    /** Unparseable payloads still ship with enough framing to be understood. */
    private fun fallback(payloadJson: String): String =
        buildString {
            appendLine("ARXIVER RESEARCH DISPATCH (schema arxiver/v1)")
            appendLine()
            appendLine("The research payload follows as JSON:")
            appendLine()
            appendLine("```json")
            appendLine(payloadJson)
            append("```")
        }
}
