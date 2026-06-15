package dev.blokz.arxiver.core.search

import dev.blokz.arxiver.core.database.entity.ChunkEmbeddingEntity

/** A unit of text to embed: its provenance, its position, and the text itself. */
data class TextChunk(
    val sourceKind: String,
    val ordinal: Int,
    val text: String,
)

/**
 * Splits a paper's text into bounded chunks for RAG embedding (SPEC-SEARCH §8).
 *
 * The bge model truncates input at 512 tokens, so content past that window is
 * invisible to a single embedding; chunking keeps every passage retrievable.
 * Splitting is sentence-aware with a small overlap so a chunk boundary doesn't
 * sever a thought. Budgets are in characters (a cheap, tokenizer-free proxy for
 * the 512-token window — well under it for English), keeping this pure and
 * CI-testable. Ordinals are unique within each `(paperId, sourceKind)`.
 */
class TextChunker(
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val overlapChars: Int = DEFAULT_OVERLAP_CHARS,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(overlapChars in 0 until maxChars) { "overlapChars must be in [0, maxChars)" }
    }

    /**
     * @param notes note bodies; all note chunks share `source_kind = note` and are
     *   numbered continuously so the `(paperId, source_kind, ordinal)` key stays unique.
     */
    fun chunk(
        title: String,
        abstract: String,
        notes: List<String> = emptyList(),
    ): List<TextChunk> {
        val out = mutableListOf<TextChunk>()

        val abstractText = listOf(title, abstract).filter { it.isNotBlank() }.joinToString("\n")
        splitText(abstractText).forEachIndexed { i, text ->
            out += TextChunk(ChunkEmbeddingEntity.SOURCE_ABSTRACT, i, text)
        }

        var noteOrdinal = 0
        for (note in notes) {
            for (text in splitText(note)) {
                out += TextChunk(ChunkEmbeddingEntity.SOURCE_NOTE, noteOrdinal++, text)
            }
        }
        return out
    }

    /** Sentence-greedy packing under [maxChars] with [overlapChars] carry-over. */
    private fun splitText(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        if (text.length <= maxChars) return listOf(text)

        val sentences = splitSentences(text)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotBlank()) chunks += current.toString().trim()
        }

        for (sentence in sentences) {
            // A single oversized sentence is hard-split on character windows.
            if (sentence.length > maxChars) {
                flush()
                current.clear()
                var start = 0
                while (start < sentence.length) {
                    val end = minOf(start + maxChars, sentence.length)
                    chunks += sentence.substring(start, end).trim()
                    if (end == sentence.length) break
                    start = end - overlapChars
                }
                continue
            }
            if (current.length + sentence.length + 1 > maxChars && current.isNotBlank()) {
                flush()
                val tail = current.toString().takeLast(overlapChars)
                current.clear()
                current.append(tail)
                if (tail.isNotEmpty()) current.append(' ')
            }
            current.append(sentence).append(' ')
        }
        flush()
        return chunks.filter { it.isNotBlank() }
    }

    /** Coarse sentence split on terminal punctuation followed by whitespace, newlines kept as breaks. */
    private fun splitSentences(text: String): List<String> =
        text.split(SENTENCE_BOUNDARY).map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_MAX_CHARS = 1200
        const val DEFAULT_OVERLAP_CHARS = 150
        private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+|\\n+")
    }
}
