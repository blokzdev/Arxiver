package dev.blokz.arxiver.feature.paper.ask

import dev.blokz.arxiver.chat.extractFollowUps
import dev.blokz.arxiver.data.Citation

/**
 * Localized headings for a Markdown export. Resolved at the UI edge (`stringResource`) and passed
 * in so [ConversationMarkdown] stays a pure, Context-free, locale-free, unit-testable object.
 */
data class ConversationMarkdownLabels(
    val you: String,
    val assistant: String,
    val sources: String,
    val footer: String,
)

/** Excerpt truncation shared by on-screen `CitationSources` and the Markdown export, so the shared
 *  text matches what the user saw (P-Rich R4). */
internal fun truncateExcerpt(excerpt: String): String {
    val s = excerpt.trim()
    return if (s.length > EXCERPT_MAX) s.take(EXCERPT_MAX).trimEnd() + "…" else s
}

private const val EXCERPT_MAX = 160

/**
 * Pure Markdown serializer for the "save / export / share" surface (P-Rich R4). Operates on the
 * in-memory [AskMessage] list the UI already holds — no DB round-trip, no Android, no I/O, no
 * timestamp/locale — so it is deterministic and JUnit-testable (mirrors `LibraryExporter`). It
 * defensively re-strips a trailing `FOLLOWUPS::` sentinel (the cancelled-cloud-Max partial path
 * doesn't run `settleFollowUps`), so an export never leaks the model's machine sentinel.
 *
 * Chat content is serialized **only** here — never routed through `BackupManager`/`LibraryExporter`
 * or any importable schema — keeping the "chat is excluded from automated exports" wall intact while
 * still honoring the user-initiated one-shot share.
 */
object ConversationMarkdown {
    /** A single assistant answer plus its `### Sources` block. */
    fun answer(
        message: AskMessage,
        labels: ConversationMarkdownLabels,
    ): String =
        buildString {
            append(cleanBody(message.text))
            appendSources(message.citations, labels)
        }

    /**
     * A whole conversation: an optional `# scopeLabel` header, then `## You` / `## Assistant` turns
     * (assistant turns carry their `### Sources`), then the footer. Streaming/error/blank turns are
     * skipped. [scopeLabel] is null for a deleted paper/collection target.
     */
    fun conversation(
        messages: List<AskMessage>,
        scopeLabel: String?,
        labels: ConversationMarkdownLabels,
    ): String =
        buildString {
            if (!scopeLabel.isNullOrBlank()) {
                appendLine("# $scopeLabel")
                appendLine()
            }
            messages.forEach { m ->
                when {
                    m.role == AskRole.USER && m.text.isNotBlank() -> {
                        appendLine("## ${labels.you}")
                        appendLine()
                        appendLine(cleanBody(m.text))
                        appendLine()
                    }
                    m.role == AskRole.ASSISTANT && !m.streaming && !m.error && m.text.isNotBlank() -> {
                        appendLine("## ${labels.assistant}")
                        appendLine()
                        append(cleanBody(m.text))
                        appendSources(m.citations, labels)
                        appendLine()
                        appendLine()
                    }
                }
            }
            append("— ${labels.footer}")
        }

    /** Strip any trailing model FOLLOWUPS sentinel + trim (defends the cancelled-Max-partial path). */
    private fun cleanBody(text: String): String = extractFollowUps(text).first.trim()

    private fun StringBuilder.appendSources(
        citations: List<Citation>,
        labels: ConversationMarkdownLabels,
    ) {
        if (citations.isEmpty()) return
        appendLine()
        appendLine()
        appendLine("### ${labels.sources}")
        citations.forEach { c ->
            appendLine("[${c.index}] arXiv:${c.paperId} — ${truncateExcerpt(c.excerpt)}")
        }
    }
}
