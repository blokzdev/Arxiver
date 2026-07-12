package dev.blokz.arxiver.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the [SearchTrace] section-name literals (P-Prove PP.3b). The PP.3b Macrobenchmark `TraceSectionMetric`s query
 * these exact strings; a rename or a copy-paste duplicate here would make a suite silently read zero (or double-count)
 * on device where CI can't see it. This JVM test is the primary drift guard (Robolectric's async-trace shadow is
 * version-dependent and unreliable), so it runs in every `./gradlew build`.
 */
class SearchTraceContractTest {
    @Test
    fun `the pinned section literals are exact`() {
        assertEquals("hybrid_search", SearchTrace.HYBRID_SEARCH)
        assertEquals("hybrid_fuse", SearchTrace.HYBRID_FUSE)
        assertEquals("vector_topk_scan", SearchTrace.VECTOR_TOPK_SCAN)
    }

    @Test
    fun `the section names are unique and non-blank`() {
        val all = listOf(SearchTrace.HYBRID_SEARCH, SearchTrace.HYBRID_FUSE, SearchTrace.VECTOR_TOPK_SCAN)
        assertEquals(3, all.toSet().size, "duplicate section names would make TraceSectionMetric double-count")
        assertTrue(all.all { it.isNotBlank() })
    }
}
