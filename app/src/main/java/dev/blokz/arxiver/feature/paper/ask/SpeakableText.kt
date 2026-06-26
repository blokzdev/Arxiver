package dev.blokz.arxiver.feature.paper.ask

import dev.blokz.arxiver.chat.extractFollowUps

/**
 * Spoken-form captions for the rich blocks TTS can't read aloud, resolved at the UI edge
 * (`stringResource`) and passed in so [SpeakableText] stays a pure, Context-free, locale-free,
 * unit-testable object (mirrors `ConversationMarkdownLabels`).
 */
data class SpeakableLabels(
    val diagram: String,
    val equation: String,
    val image: String,
    val code: String,
)

/**
 * Turns a settled assistant answer (the raw `message.text`) into **speakable prose** for read-aloud
 * (P-Share PS.2). The model's answer is full of things TTS must not voice literally — LaTeX
 * (`$$\int$$`), Mermaid/SVG source, `[n]` citation markers, markdown syntax — so each is replaced by
 * a short spoken caption ("Equation", "Diagram", "Image") or stripped. Pure (no Android, no TTS
 * engine) → JVM-golden-testable, mirroring [ConversationMarkdown]; the `TextToSpeechManager` just
 * speaks whatever string this returns. The `### Sources` block is never spoken — it lives in the
 * message's citations, not in `text`, so reading `text` excludes it by construction.
 */
object SpeakableText {
    // A fenced ```lang block (lang on the open line; body until the closing fence).
    private val FENCE = Regex("(?s)```([A-Za-z0-9]*)[^\\n]*\\n?(.*?)```")
    private val DISPLAY_MATH = Regex("(?s)\\$\\$.*?\\$\\$")
    private val INLINE_MATH = Regex("\\$([^$\\n]+)\\$")
    private val CITATION = Regex("""\s*\[\d{1,3}]""")
    private val ARXIV = Regex("""arXiv:""")
    private val MD_LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val INLINE_CODE = Regex("`([^`\\n]+)`")
    private val EMPHASIS = Regex("""(\*\*|__|\*|_|~~)""")
    private val HEADING_OR_QUOTE = Regex("""(?m)^\s{0,3}(#{1,6}|>)\s*""")
    private val LIST_BULLET = Regex("""(?m)^\s{0,6}([-*+]|\d+\.)\s+""")
    private val TABLE_SEP = Regex("""(?m)^\s*\|?\s*:?-{2,}.*$""")
    private val SHORT_VAR = Regex("""^[A-Za-z0-9]{1,3}$""")
    private val WHITESPACE = Regex("""[ \t]{2,}""")
    private val BLANKS = Regex("""\n{3,}""")

    /** The spoken form of [text] (a raw assistant answer), with rich blocks captioned + markdown stripped. */
    fun forAnswer(
        text: String,
        labels: SpeakableLabels,
    ): String {
        var s = extractFollowUps(text).first
        // Fenced blocks → a caption by language (mermaid/math/svg/other).
        s =
            FENCE.replace(s) { m ->
                when (m.groupValues[1].lowercase()) {
                    "mermaid" -> labels.diagram
                    "math", "latex", "tex" -> labels.equation
                    "svg" -> labels.image
                    else -> labels.code
                }
            }
        s = DISPLAY_MATH.replace(s) { labels.equation }
        // Inline math: read a trivial single variable, caption anything more complex.
        s =
            INLINE_MATH.replace(s) { m ->
                val inner = m.groupValues[1].trim()
                if (SHORT_VAR.matches(inner)) inner else labels.equation
            }
        // Citations are visual cross-refs, not speech ("[1]" → silence, not "bracket one").
        s = CITATION.replace(s, "")
        s = ARXIV.replace(s, "arXiv ")
        // Markdown → plain words.
        s = MD_LINK.replace(s) { it.groupValues[1] }
        s = INLINE_CODE.replace(s) { it.groupValues[1] }
        s = TABLE_SEP.replace(s, "")
        s = HEADING_OR_QUOTE.replace(s, "")
        s = LIST_BULLET.replace(s, "")
        s = EMPHASIS.replace(s, "")
        s = s.replace("|", ", ")
        // Tidy whitespace for natural pauses.
        s = WHITESPACE.replace(s, " ")
        s = BLANKS.replace(s, "\n\n")
        return s.trim()
    }
}
