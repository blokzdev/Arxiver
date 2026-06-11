package dev.blokz.arxiver.core.ml

import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordPieceTokenizerTest {
    // The real production vocab, read straight from the bundled asset.
    private val tokenizer by lazy {
        WordPieceTokenizer(File("src/main/assets/bge_vocab.txt").inputStream())
    }

    @Test
    fun `encodes simple sentence with CLS and SEP`() {
        val encoding = tokenizer.encode("hello world")
        // 101 [CLS], 7592 hello, 2088 world, 102 [SEP] — reference ids from HF tokenizer.
        assertContentEquals(longArrayOf(101, 7592, 2088, 102), encoding.inputIds)
        assertContentEquals(longArrayOf(1, 1, 1, 1), encoding.attentionMask)
    }

    @Test
    fun `lowercases and splits punctuation`() {
        val encoding = tokenizer.encode("Hello, World!")
        // hello , world ! → 7592 1010 2088 999
        assertContentEquals(longArrayOf(101, 7592, 1010, 2088, 999, 102), encoding.inputIds)
    }

    @Test
    fun `whole-vocab word wins over subword split`() {
        // "transformers" exists whole in the vocab (id 19081); greedy match keeps it intact.
        val encoding = tokenizer.encode("transformers")
        assertContentEquals(longArrayOf(101, 19081, 102), encoding.inputIds)
    }

    @Test
    fun `out-of-vocab word splits to subwords`() {
        // "quantumness" is absent; splits to quantum (8559) + ##ness (2791).
        val encoding = tokenizer.encode("quantumness")
        assertContentEquals(longArrayOf(101, 8559, 2791, 102), encoding.inputIds)
    }

    @Test
    fun `unknown glyph maps to UNK`() {
        val encoding = tokenizer.encode("☃")
        assertContentEquals(longArrayOf(101, 100, 102), encoding.inputIds)
    }

    @Test
    fun `truncates at max length`() {
        val text = (1..2000).joinToString(" ") { "word" }
        val encoding = tokenizer.encode(text, maxLength = 512)
        assertTrue(encoding.length <= 512)
        assertEquals(102, encoding.inputIds.last())
        assertEquals(101, encoding.inputIds.first())
    }

    @Test
    fun `pad aligns batch to longest`() {
        val batch = listOf(tokenizer.encode("hello"), tokenizer.encode("hello world today"))
        val (ids, mask, maxLen) = tokenizer.pad(batch)
        assertEquals(maxLen, ids[0].size)
        assertEquals(maxLen, ids[1].size)
        assertEquals(0L, ids[0].last()) // [PAD] id 0
        assertEquals(0L, mask[0].last())
        assertEquals(1L, mask[1].last())
    }
}
