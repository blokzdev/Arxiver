package dev.blokz.arxiver.core.network.openalex

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.PreprintBackend
import dev.blokz.arxiver.core.network.PreprintHit
import dev.blokz.arxiver.core.network.PreprintPage

/**
 * [PreprintBackend] for the OpenAlex-served sources (chemRxiv — CF-dead direct — + new sources, PF.3) over the
 * [client] (P-Feeds PF.2). [sidFor] maps a [Source] to its OpenAlex source id. Cursor-paginated via OpenAlex's
 * `next_cursor`. Category (an OpenAlex Field) filtering lands in PF.3; PF.2 browses the whole source.
 */
class OpenAlexBackend(
    private val client: OpenAlexClient,
    private val sidFor: (Source) -> String?,
) : PreprintBackend {
    override suspend fun browse(
        source: Source,
        category: String?,
        sinceIso: String,
        cursor: String?,
    ): AppResult<PreprintPage> {
        val sid = sidFor(source)
        if (sid == null) {
            return AppResult.Failure(AppError.Unexpected(IllegalArgumentException("no OpenAlex source id: $source")))
        }
        // [category] is a source-appropriate OpenAlex Field token ("fields/N") or null for the whole source.
        return when (val r = client.browse(sid, sinceIso, cursor ?: "*", category)) {
            is AppResult.Success -> {
                val hits = r.value.results.mapNotNull { it.toHit(source) }
                AppResult.Success(PreprintPage(hits, r.value.meta.nextCursor))
            }
            is AppResult.Failure -> AppResult.Failure(r.error)
        }
    }

    private fun OpenAlexWork.toHit(source: Source): PreprintHit? {
        val d = bareDoi() ?: return null
        return PreprintHit(
            origin = source,
            doi = d,
            title = title.orEmpty(),
            abstract = abstractText().orEmpty(),
            authors = authorNames(),
            publishedIso = publicationDate,
            // Host-gated at read time (PdfDownloader interceptor + magic-byte). chemRxiv's is cookie-walled →
            // degrades to open-in-browser; null when OpenAlex has no OA pdf url.
            oaPdfUrl = oaPdfUrl(),
        )
    }
}
