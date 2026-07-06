package dev.blokz.arxiver.core.model

/**
 * Classify a Semantic Scholar hit into a first-class [Source] by its **venue**, not its DOI.
 *
 * The `10.1101/` DOI prefix (and the newer `10.64898/`) is shared across bioRxiv AND medRxiv, so the DOI
 * cannot discriminate the two; S2's `venue` string (`"bioRxiv"` / `"medRxiv"`) is the authoritative
 * signal (live-confirmed against the S2 Graph API, 2026-07-05). Returns null for any venue we don't model
 * as a first-class source — that hit stays read-only (external-open only, never imported).
 *
 * Contains-match, case-insensitive, and **medRxiv is checked first**: a naive `contains("biorxiv")` test
 * ordered first would never be reached for medRxiv, but the reverse is safe because "medrxiv" does not
 * contain "biorxiv". See [Source] for the enum and its wire tokens.
 */
fun s2OriginFromVenue(venue: String?): Source? =
    when {
        venue == null -> null
        venue.contains("medrxiv", ignoreCase = true) -> Source.MEDRXIV
        venue.contains("biorxiv", ignoreCase = true) -> Source.BIORXIV
        else -> null
    }
