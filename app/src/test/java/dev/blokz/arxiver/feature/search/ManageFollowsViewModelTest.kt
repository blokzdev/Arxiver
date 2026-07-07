package dev.blokz.arxiver.feature.search

import dev.blokz.arxiver.core.database.entity.FollowEntity
import dev.blokz.arxiver.core.model.Source
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure grouping logic behind the PFP.2 manage screen — origin→source mapping, type coverage, row order. */
class ManageFollowsViewModelTest {
    private fun follow(
        type: String,
        value: String,
        label: String,
        origin: String,
    ) = FollowEntity(type = type, value = value, label = label, createdAt = 0, origin = origin)

    @Test
    fun `origin arxiv groups under the arXiv source, not a fallback bucket`() {
        // Regression guard: Source.BY_PREFIX excludes ARXIV, so a naive BY_PREFIX map would misgroup arXiv follows.
        val groups =
            ManageFollowsViewModel.groupBySource(listOf(follow("category", "cs.LG", "Machine Learning", "arxiv")))
        assertEquals(Source.ARXIV, groups.single().source)
        assertEquals("Machine Learning", groups.single().rows.single().label)
    }

    @Test
    fun `author and query follows are listed under their source group`() {
        val groups =
            ManageFollowsViewModel.groupBySource(
                listOf(
                    follow("author", "Yann LeCun", "Yann LeCun", "arxiv"),
                    follow("query", "diffusion", "diffusion", "arxiv"),
                ),
            )
        val types = groups.single().rows.map { it.type }.toSet()
        assertEquals(setOf(FollowEntity.TYPE_AUTHOR, FollowEntity.TYPE_QUERY), types)
    }

    @Test
    fun `whole-source rows sort first, then categories alphabetically`() {
        val groups =
            ManageFollowsViewModel.groupBySource(
                listOf(
                    follow("category", "fields/16", "Zoology", "chemrxiv"),
                    follow("category", "fields/12", "Anatomy", "chemrxiv"),
                    // whole-source follow (value="")
                    follow("category", "", "chemRxiv", "chemrxiv"),
                ),
            )
        val rows = groups.single().rows
        assertTrue(rows.first().isWholeSource, "whole-source row is first")
        assertEquals(listOf("Anatomy", "Zoology"), rows.drop(1).map { it.label })
    }

    @Test
    fun `groups are ordered arXiv-first by source declaration order`() {
        val groups =
            ManageFollowsViewModel.groupBySource(
                listOf(
                    follow("category", "", "chemRxiv", "chemrxiv"),
                    follow("category", "cs.LG", "ML", "arxiv"),
                ),
            )
        assertEquals(listOf(Source.ARXIV, Source.CHEMRXIV), groups.map { it.source })
    }
}
