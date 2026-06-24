package dev.blokz.arxiver.feature.paper.ask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure tests for the R3c preset registry — scope filtering, uniqueness, content sanity. */
class AskPresetsTest {
    @Test
    fun `ids are unique`() {
        val ids = AskPresets.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "preset ids must be unique")
    }

    @Test
    fun `every preset has a non-blank instruction`() {
        assertTrue(AskPresets.ALL.all { it.instruction.isNotBlank() })
    }

    @Test
    fun `paper scope includes BOTH and PAPER, not COLLECTION-only`() {
        val paper = AskPresets.forScope(isPaper = true)
        assertTrue(paper.none { it.appliesTo == PresetScope.COLLECTION })
        assertTrue(paper.any { it.id == "bibtex" }, "paper-only bibtex is offered for a paper")
        assertTrue(paper.any { it.id == "summarize" }, "BOTH presets are always offered")
    }

    @Test
    fun `collection scope excludes paper-only presets`() {
        val collection = AskPresets.forScope(isPaper = false)
        assertTrue(collection.none { it.appliesTo == PresetScope.PAPER })
        assertTrue(collection.none { it.id == "bibtex" }, "bibtex hidden for a collection")
        assertTrue(collection.none { it.id == "reproducibility" }, "reproducibility hidden for a collection")
        assertTrue(collection.any { it.id == "compare_related" }, "BOTH presets still show")
    }

    @Test
    fun `summarize preset reuses the canonical prompt`() {
        val summarize = AskPresets.ALL.first { it.id == "summarize" }
        assertEquals(AskViewModel.SUMMARIZE_PROMPT, summarize.instruction)
    }
}
