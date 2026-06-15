package dev.blokz.arxiver.core.search

import dev.blokz.arxiver.core.database.entity.ChunkEmbeddingEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextChunkerTest {
    private val chunker = TextChunker(maxChars = 60, overlapChars = 10)

    @Test
    fun `short abstract becomes a single chunk with the title prefixed`() {
        val chunks = chunker.chunk(title = "Graphs", abstract = "We study graphs.")
        assertEquals(1, chunks.size)
        assertEquals(ChunkEmbeddingEntity.SOURCE_ABSTRACT, chunks.single().sourceKind)
        assertEquals(0, chunks.single().ordinal)
        assertTrue(chunks.single().text.startsWith("Graphs"))
        assertTrue(chunks.single().text.contains("We study graphs."))
    }

    @Test
    fun `empty title and abstract and notes yields nothing`() {
        assertEquals(emptyList(), chunker.chunk(title = "", abstract = "  ", notes = listOf("", "  ")))
    }

    @Test
    fun `long abstract splits into ordered bounded chunks`() {
        val abstract = (1..20).joinToString(" ") { "Sentence number $it here." }
        val chunks = chunker.chunk(title = "T", abstract = abstract)

        assertTrue(chunks.size > 1)
        assertEquals(chunks.indices.toList(), chunks.map { it.ordinal })
        assertTrue(chunks.all { it.sourceKind == ChunkEmbeddingEntity.SOURCE_ABSTRACT })
        assertTrue(chunks.all { it.text.length <= 60 }, "every chunk within maxChars")
    }

    @Test
    fun `notes are numbered continuously across notes and tagged as note source`() {
        val longNote = (1..10).joinToString(" ") { "Note sentence $it." }
        val chunks =
            chunker.chunk(
                title = "T",
                abstract = "A.",
                notes = listOf(longNote, "Second short note."),
            )

        val noteChunks = chunks.filter { it.sourceKind == ChunkEmbeddingEntity.SOURCE_NOTE }
        assertTrue(noteChunks.size >= 2)
        assertEquals(noteChunks.indices.toList(), noteChunks.map { it.ordinal })
    }

    @Test
    fun `an oversized single sentence is hard split`() {
        val giant = "x".repeat(200)
        val chunks = chunker.chunk(title = "", abstract = giant)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= 60 })
    }
}
