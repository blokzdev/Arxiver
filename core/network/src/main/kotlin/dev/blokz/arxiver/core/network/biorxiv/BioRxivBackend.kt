package dev.blokz.arxiver.core.network.biorxiv

import dev.blokz.arxiver.core.common.AppError
import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.network.PreprintBackend
import dev.blokz.arxiver.core.network.PreprintHit
import dev.blokz.arxiver.core.network.PreprintPage
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [PreprintBackend] for bioRxiv/medRxiv over the native `api.biorxiv.org` [client] (P-Feeds PF.2). Browses a
 * server's postings in `[sinceIso, today]` with server-side `?category=`, offset-cursor paginated. Unlike
 * OpenAlex, bio/med expose the paper's **version** here, so the deterministic PDF url
 * (`www.{server}.org/content/{doi}v{n}.full.pdf`, host-allowlisted) can be synthesized — a follow paper is
 * readable in-app, not just metadata.
 */
class BioRxivBackend(
    private val client: BioRxivApiClient,
    private val today: () -> String = { LocalDate.now(ZoneOffset.UTC).toString() },
) : PreprintBackend {
    override suspend fun browse(
        source: Source,
        category: String?,
        sinceIso: String,
        cursor: String?,
    ): AppResult<PreprintPage> {
        val server =
            when (source) {
                Source.BIORXIV -> BioRxivApiClient.SERVER_BIORXIV
                Source.MEDRXIV -> BioRxivApiClient.SERVER_MEDRXIV
                else ->
                    return AppResult.Failure(AppError.Unexpected(IllegalArgumentException("not bio/medRxiv: $source")))
            }
        val offset = cursor?.toIntOrNull() ?: 0
        return when (val r = client.details(server, sinceIso, today(), offset, category)) {
            is AppResult.Success -> {
                val msg = r.value.messages.firstOrNull()
                val total = msg?.total?.toIntOrNull() ?: 0
                val count = msg?.count ?: r.value.collection.size
                val hits = r.value.collection.mapNotNull { it.toHit(source) }
                val nextOffset = offset + count
                val nextCursor = if (count > 0 && nextOffset < total) nextOffset.toString() else null
                AppResult.Success(PreprintPage(hits, nextCursor))
            }
            is AppResult.Failure -> AppResult.Failure(r.error)
        }
    }

    private fun BioRxivItem.toHit(source: Source): PreprintHit? {
        val d = doi?.takeIf { it.isNotBlank() } ?: return null
        val host = if (source == Source.MEDRXIV) "www.medrxiv.org" else "www.biorxiv.org"
        val pdf = version?.takeIf { it.isNotBlank() }?.let { "https://$host/content/${d}v$it.full.pdf" }
        return PreprintHit(
            origin = source,
            // bio/med always publish a DOI, so identity and DOI coincide here.
            nativeId = d,
            doi = d,
            landingUrl = "https://$host/content/$d",
            title = title.orEmpty(),
            abstract = abstract.orEmpty(),
            authors = authorList(),
            publishedIso = date,
            oaPdfUrl = pdf,
            version = version,
            // The server's own native category ("neuroscience") — already returned, never threaded until PE.0.
            fieldName = category?.takeIf { it.isNotBlank() },
        )
    }
}
