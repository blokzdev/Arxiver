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

    @Test
    fun `vision preset is hidden unless visionAvailable`() {
        // R3d.4 m3: default (visionAvailable=false) keeps the text-only chip set; gating reveals it.
        val without = AskPresets.forScope(isPaper = true)
        assertTrue(without.none { it.id == "summarize_with_figures" }, "vision preset hidden by default")
        val with = AskPresets.forScope(isPaper = true, visionAvailable = true)
        assertTrue(with.any { it.id == "summarize_with_figures" }, "vision preset shown when available")
    }

    @Test
    fun `vision preset never shows for a collection even when visionAvailable`() {
        val collection = AskPresets.forScope(isPaper = false, visionAvailable = true)
        assertTrue(collection.none { it.requiresVision }, "no vision preset in a collection sheet")
    }

    @Test
    fun `map relations is an artifact preset offered for a paper only`() {
        // P-Atlas PA.1: routed to runGraphArtifact (no LLM), PAPER scope (collection → PA.5).
        val preset = AskPresets.forScope(isPaper = true).firstOrNull { it.id == "map_relations" }
        assertTrue(preset != null && preset.artifact, "map_relations is an artifact preset on a paper")
        assertTrue(
            AskPresets.forScope(isPaper = false).none { it.id == "map_relations" },
            "not offered in a collection sheet yet",
        )
    }
}
