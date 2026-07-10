package dev.blokz.arxiver.core.network

import dev.blokz.arxiver.core.model.PdfAccess
import dev.blokz.arxiver.core.model.Source
import dev.blokz.arxiver.core.model.pdfAccess
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural red-line guard (P-Explorer PE.0): `Source.pdfAccess()` lives in `:core:model` (which cannot see the
 * egress allowlist), so this test pins the two together — an [PdfAccess.IN_APP] source whose PDF host is NOT
 * allowlisted would silently fail closed at read time, and a host quietly dropped from [AllowedHosts] must break
 * the build here rather than in a user's hands.
 *
 * It also pins the hosts we deliberately REFUSE to allowlist (P-Explorer PE.2h probe, 2026-07-10), so a future
 * "just add the host" change has to confront the reason it was rejected.
 */
class PdfAccessAllowlistTest {
    /** The host each in-app-readable source actually serves its OA PDF from (probe-verified). */
    private val inAppPdfHosts =
        mapOf(
            Source.ARXIV to "arxiv.org",
            Source.BIORXIV to "www.biorxiv.org",
            Source.MEDRXIV to "www.medrxiv.org",
        )

    @Test
    fun `every IN_APP source's pdf host is egress-allowlisted`() {
        Source.entries.filter { it.pdfAccess() == PdfAccess.IN_APP }.forEach { source ->
            val host =
                inAppPdfHosts[source]
                    ?: error("$source is IN_APP but this test names no PDF host for it — add one, or fix the tier")
            assertTrue(
                AllowedHosts.isAllowed(host),
                "$source is IN_APP but its PDF host '$host' is not allowlisted — the fetch would fail closed",
            )
        }
    }

    @Test
    fun `the in-app tier is exactly arXiv plus bioRxiv and medRxiv`() {
        assertEquals(
            setOf(Source.ARXIV, Source.BIORXIV, Source.MEDRXIV),
            Source.entries.filter { it.pdfAccess() == PdfAccess.IN_APP }.toSet(),
            "widening the in-app tier is an egress decision — it needs a host probe + Co-Founder approval",
        )
    }

    @Test
    fun `hosts rejected by the PE_2h probe stay un-allowlisted`() {
        // storage.googleapis.com is the load-bearing one: it is multi-tenant GCS whose bucket lives in the URL
        // PATH, so exact-host allowlisting cannot scope it to OSF — granting it would open egress to any bucket.
        listOf(
            "storage.googleapis.com",
            "files.osf.io",
            "osf.io",
            // SSRN's ToS bans automated queries; Preprints.org hard-blocks non-browser clients at the edge.
            "papers.ssrn.com",
            "www.preprints.org",
        ).forEach { host ->
            assertTrue(!AllowedHosts.isAllowed(host), "'$host' must stay un-allowlisted (see PE.2h probe verdict)")
        }
    }
}
