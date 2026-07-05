package dev.blokz.arxiver.core.model

/**
 * Provenance of a paper's *identity* — the source whose native id keys the record (SPEC-P-SOURCES §2).
 * DISTINCT from [PaperSource] (the acquisition-path enum stored in `papers.source`, which `EmbeddingDao`
 * filters on `!= 'S2_STUB'`): [Source] is the identity origin stored in the NEW `papers.origin` column.
 *
 * [wire] is the stable lowercase storage/prefix token: it IS the `papers.origin` value, the reserved
 * [PaperRef.fromStorageId] prefix, and byte-matches the SQL default `'arxiv'`. **Never derive it from
 * `name.lowercase()` at a call site — read [wire].** (kotlinx serializes an enum by `name` = UPPERCASE;
 * only [wire] ever touches the DB / a storage id prefix — do not confuse the two.)
 *
 * [displayName] is the brand-cased human label ("chemRxiv", not "chemrxiv") for citations, badges, and
 * metadata rows. It is a proper noun (not localized), so pure-JVM surfaces ([Citation]) read it directly;
 * Android chrome around it ("Source: %s") still comes from `strings.xml`.
 */
enum class Source(val wire: String, val displayName: String) {
    ARXIV("arxiv", "arXiv"),
    CHEMRXIV("chemrxiv", "chemRxiv"),
    BIORXIV("biorxiv", "bioRxiv"),
    MEDRXIV("medrxiv", "medRxiv"),
    S2("s2", "Semantic Scholar"),
    ;

    companion object {
        /** Reserved non-arXiv prefixes → [Source] (ARXIV is the un-prefixed default, never a prefix). */
        internal val BY_PREFIX: Map<String, Source> =
            entries.filter { it != ARXIV }.associateBy { it.wire }
    }
}
