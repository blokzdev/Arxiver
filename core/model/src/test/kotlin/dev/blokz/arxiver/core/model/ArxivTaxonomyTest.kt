package dev.blokz.arxiver.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArxivTaxonomyTest {
    @Test
    fun `byCode resolves a known category`() {
        val cat = ArxivTaxonomy.byCode("cs.LG")
        assertEquals(ArxivCategory("cs.LG", "Machine Learning", ArxivTaxonomy.GROUP_CS), cat)
    }

    @Test
    fun `byCode returns null for an unknown code`() {
        assertNull(ArxivTaxonomy.byCode("cs.ZZ"))
    }

    @Test
    fun `category codes are unique`() {
        val codes = ArxivTaxonomy.categories.map { it.code }
        assertEquals(codes.size, codes.toSet().size, "duplicate category codes present")
    }

    @Test
    fun `groups are the eight arXiv groups and distinct`() {
        assertEquals(8, ArxivTaxonomy.groups.size)
        assertEquals(ArxivTaxonomy.groups.size, ArxivTaxonomy.groups.toSet().size)
        assertTrue(ArxivTaxonomy.GROUP_CS in ArxivTaxonomy.groups)
        assertTrue(ArxivTaxonomy.GROUP_PHYSICS in ArxivTaxonomy.groups)
    }

    @Test
    fun `every category has non-blank code name and a known group`() {
        ArxivTaxonomy.categories.forEach { c ->
            assertTrue(c.code.isNotBlank() && c.name.isNotBlank())
            assertTrue(c.group in ArxivTaxonomy.groups)
        }
    }
}
