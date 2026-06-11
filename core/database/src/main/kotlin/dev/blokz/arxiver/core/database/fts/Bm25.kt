package dev.blokz.arxiver.core.database.fts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

/**
 * Okapi BM25 over SQLite FTS4 `matchinfo(table, 'pcnalx')` blobs (SPEC-DATA §3).
 *
 * Blob layout ('pcnalx', little-endian Int32s):
 *   p — phrase count (1 int)
 *   c — column count (1 int)
 *   n — total rows in table (1 int)
 *   a — average token count per column (c ints)
 *   l — token count per column, this row (c ints)
 *   x — for each phrase×column: [hits this row, hits all rows, docs with hits] (3·p·c ints)
 */
object Bm25 {
    private const val K1 = 1.2
    private const val B = 0.75

    /**
     * @param weights one weight per FTS column, in table column order.
     * @return relevance score; higher is better. 0.0 for blank/malformed input.
     */
    fun score(
        matchinfo: ByteArray,
        weights: DoubleArray,
    ): Double {
        if (matchinfo.size < 12) return 0.0
        val ints = ByteBuffer.wrap(matchinfo).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val phraseCount = ints.get(0)
        val columnCount = ints.get(1)
        if (phraseCount <= 0 || columnCount <= 0) return 0.0
        require(weights.size == columnCount) {
            "weights size ${weights.size} != column count $columnCount"
        }
        val expected = 3 + 2 * columnCount + 3 * phraseCount * columnCount
        if (ints.limit() < expected) return 0.0

        val totalDocs = ints.get(2).toDouble().coerceAtLeast(1.0)
        val avgLengths = DoubleArray(columnCount) { ints.get(3 + it).toDouble() }
        val lengths = DoubleArray(columnCount) { ints.get(3 + columnCount + it).toDouble() }
        val xBase = 3 + 2 * columnCount

        var score = 0.0
        for (phrase in 0 until phraseCount) {
            for (col in 0 until columnCount) {
                val base = xBase + 3 * (phrase * columnCount + col)
                val termFreq = ints.get(base).toDouble()
                if (termFreq <= 0.0) continue
                val docsWithHit = ints.get(base + 2).toDouble()
                val idf = ln((totalDocs - docsWithHit + 0.5) / (docsWithHit + 0.5) + 1.0)
                val lengthNorm = if (avgLengths[col] > 0) lengths[col] / avgLengths[col] else 1.0
                val tfComponent = (termFreq * (K1 + 1)) / (termFreq + K1 * (1 - B + B * lengthNorm))
                score += weights[col] * idf * tfComponent
            }
        }
        return score
    }

    /** papers_fts column weights: title, abstract, authors_line. */
    val PAPER_WEIGHTS = doubleArrayOf(10.0, 5.0, 3.0)

    /** notes_fts column weights: content. */
    val NOTE_WEIGHTS = doubleArrayOf(2.0)
}

/**
 * User text → safe FTS MATCH expression: embedded quotes stripped, terms
 * quoted (implicit AND), last term prefix-expanded for type-ahead feel.
 */
fun buildMatchQuery(raw: String): String {
    val terms =
        raw.trim()
            .split(Regex("\\s+"))
            .map { it.replace("\"", "") }
            .filter { it.isNotBlank() }
    if (terms.isEmpty()) return ""
    return terms.mapIndexed { index, term ->
        if (index == terms.lastIndex) "\"$term*\"" else "\"$term\""
    }.joinToString(" ")
}
