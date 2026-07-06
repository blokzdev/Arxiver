package dev.blokz.arxiver.core.network.openalex

import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.PreprintBackend
import dev.blokz.arxiver.core.network.PreprintBackendRegistry
import dev.blokz.arxiver.core.network.PreprintPage
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The invariant that closes the old silent `AppModule.sidFor { else -> null }` gap (PF.3): a source is routed to
 * the OpenAlex backend **iff** it has an OpenAlex source id. If a future [Source] is added to
 * `PreprintBackendRegistry.backendFor`'s OpenAlex arm but not to `OpenAlexClient.sidFor` (or vice-versa), its
 * follows would silently fail at runtime — this test fails at CI instead.
 */
class OpenAlexSidTableTest {
    private val bio =
        object : PreprintBackend {
            override suspend fun browse(
                source: Source,
                category: String?,
                sinceIso: String,
                cursor: String?,
            ) = AppResult.Success(PreprintPage(emptyList(), null))
        }
    private val openAlex =
        object : PreprintBackend {
            override suspend fun browse(
                source: Source,
                category: String?,
                sinceIso: String,
                cursor: String?,
            ) = AppResult.Success(PreprintPage(emptyList(), null))
        }
    private val registry = PreprintBackendRegistry(bioRxivBackend = bio, openAlexBackend = openAlex)

    @Test
    fun `sidFor is non-null exactly for the OpenAlex-routed sources`() {
        Source.entries.forEach { s ->
            val routedToOpenAlex = registry.backendFor(s) === openAlex
            if (routedToOpenAlex) {
                assertNotNull(OpenAlexClient.sidFor(s), "${s.wire} routes to OpenAlex but has no SID")
            } else {
                assertNull(OpenAlexClient.sidFor(s), "${s.wire} has a SID but is NOT routed to OpenAlex")
            }
        }
    }
}
