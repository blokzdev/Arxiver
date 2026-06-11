package dev.blokz.arxiver.core.ml

import java.io.InputStream

/**
 * BERT-style WordPiece tokenizer (uncased) for bge-small-en-v1.5
 * (SPEC-SEARCH §6). Pure Kotlin — no JNI tokenizer dependency; the vocab ships
 * as an asset and is injected as an [InputStream] so tests run on the JVM.
 */
class WordPieceTokenizer(vocabStream: InputStream) {
    private val vocab: Map<String, Int> =
        vocabStream.bufferedReader().useLines { lines ->
            lines.withIndex().associate { (index, token) -> token to index }
        }

    private val clsId = requireNotNull(vocab["[CLS]"]) { "vocab missing [CLS]" }
    private val sepId = requireNotNull(vocab["[SEP]"]) { "vocab missing [SEP]" }
    private val unkId = requireNotNull(vocab["[UNK]"]) { "vocab missing [UNK]" }
    private val padId = requireNotNull(vocab["[PAD]"]) { "vocab missing [PAD]" }

    data class Encoding(
        val inputIds: LongArray,
        val attentionMask: LongArray,
    ) {
        val length: Int get() = inputIds.size
    }

    /** Encodes [text] to at most [maxLength] tokens including [CLS]/[SEP]. */
    fun encode(
        text: String,
        maxLength: Int = MAX_LENGTH,
    ): Encoding {
        val pieces = mutableListOf(clsId)
        for (word in basicTokenize(text)) {
            val wordPieces = wordPiece(word)
            // Truncation guard: leave room for [SEP].
            if (pieces.size + wordPieces.size > maxLength - 1) break
            pieces += wordPieces
        }
        pieces += sepId
        val ids = pieces.map { it.toLong() }.toLongArray()
        return Encoding(inputIds = ids, attentionMask = LongArray(ids.size) { 1L })
    }

    /** Pads a batch of encodings to the longest sequence (right padding). */
    fun pad(encodings: List<Encoding>): Triple<Array<LongArray>, Array<LongArray>, Int> {
        val maxLen = encodings.maxOf { it.length }
        val ids =
            Array(encodings.size) { i ->
                LongArray(maxLen) { j -> encodings[i].inputIds.getOrElse(j) { padId.toLong() } }
            }
        val mask =
            Array(encodings.size) { i ->
                LongArray(maxLen) { j -> encodings[i].attentionMask.getOrElse(j) { 0L } }
            }
        return Triple(ids, mask, maxLen)
    }

    /** Lowercase, strip control chars, split on whitespace and punctuation. */
    private fun basicTokenize(text: String): List<String> {
        val cleaned = text.lowercase()
        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }
        for (ch in cleaned) {
            when {
                ch.isWhitespace() -> flush()
                ch.isPunctuation() -> {
                    flush()
                    tokens += ch.toString()
                }
                ch.isISOControl() -> flush()
                else -> current.append(ch)
            }
        }
        flush()
        return tokens
    }

    /** Greedy longest-match-first subword split; whole word → [UNK] on failure. */
    private fun wordPiece(word: String): List<Int> {
        if (word.length > MAX_WORD_CHARS) return listOf(unkId)
        val result = mutableListOf<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var match: Int? = null
            while (start < end) {
                val candidate = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = vocab[candidate]
                if (id != null) {
                    match = id
                    break
                }
                end--
            }
            if (match == null) return listOf(unkId)
            result += match
            start = end
        }
        return result
    }

    private fun Char.isPunctuation(): Boolean {
        if (this in '!'..'/' || this in ':'..'@' || this in '['..'`' || this in '{'..'~') return true
        val type = Character.getType(this)
        return type in PUNCTUATION_TYPES
    }

    companion object {
        const val MAX_LENGTH = 512
        private const val MAX_WORD_CHARS = 100
        private val PUNCTUATION_TYPES =
            setOf(
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
            )
    }
}
