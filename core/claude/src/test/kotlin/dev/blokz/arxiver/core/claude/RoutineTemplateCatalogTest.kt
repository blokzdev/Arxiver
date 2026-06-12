package dev.blokz.arxiver.core.claude

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the catalog to SPEC-ROUTINES-CATALOG §6 invariants. */
class RoutineTemplateCatalogTest {
    private val templates = RoutineTemplateCatalog.templates

    @Test
    fun `catalog v1 ships exactly eight templates with unique ids and names`() {
        assertEquals(1, RoutineTemplateCatalog.CATALOG_VERSION)
        assertEquals(8, templates.size)
        assertEquals(templates.size, templates.map { it.id }.distinct().size)
        assertEquals(templates.size, templates.map { it.name }.distinct().size)
    }

    @Test
    fun `action coverage - each dedicated action once, custom thrice, ping never`() {
        val byAction = templates.groupBy { it.action }
        listOf(
            RoutineAction.DIGEST,
            RoutineAction.DEEP_DIVE,
            RoutineAction.COMPARE,
            RoutineAction.WEEKLY_REVIEW,
            RoutineAction.LITERATURE_SCAN,
        ).forEach { action ->
            assertEquals(1, byAction[action]?.size, "expected exactly one template for $action")
        }
        assertEquals(3, byAction[RoutineAction.CUSTOM]?.size)
        assertFalse(RoutineAction.PING in byAction)
    }

    @Test
    fun `all text fields are non-blank`() {
        templates.forEach { template ->
            assertTrue(template.id.isNotBlank())
            assertTrue(template.name.isNotBlank())
            assertTrue(template.purpose.isNotBlank())
            assertTrue(template.instructionPreamble.isNotBlank())
            assertTrue(template.triggerGuidance.isNotBlank())
        }
    }

    @Test
    fun `generated instructions carry both recognition markers and stand-down language`() {
        templates.forEach { template ->
            val text = RoutineStarterInstructions.generateFor(template)
            assertTrue(text.startsWith(template.instructionPreamble), template.id)
            assertTrue("ARXIVER RESEARCH DISPATCH" in text, template.id)
            assertTrue("ARXIVER CONNECTIVITY TEST" in text, template.id)
            assertTrue("Skip your" in text && "stop" in text, template.id)
        }
    }

    @Test
    fun `recognition core is verbatim-identical between generic and template copy`() {
        val core = RoutineStarterInstructions.generate()
        templates.forEach { template ->
            assertEquals(
                template.instructionPreamble + "\n\n" + core,
                RoutineStarterInstructions.generateFor(template),
                template.id,
            )
        }
    }

    @Test
    fun `no secret-shaped content anywhere in the catalog`() {
        templates.forEach { template ->
            val everything =
                listOf(
                    template.purpose,
                    template.instructionPreamble,
                    template.triggerGuidance,
                ).joinToString("\n") + RoutineStarterInstructions.generateFor(template)
            assertFalse("Bearer " in everything, template.id)
            assertFalse("sk-" in everything, template.id)
        }
    }

    @Test
    fun `byId resolves every template and rejects unknowns`() {
        templates.forEach { template ->
            assertEquals(template, RoutineTemplateCatalog.byId(template.id))
        }
        assertEquals(null, RoutineTemplateCatalog.byId("not_a_template"))
    }
}
