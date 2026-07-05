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
 */
enum class Source(val wire: String) {
    ARXIV("arxiv"),
    CHEMRXIV("chemrxiv"),
    BIORXIV("biorxiv"),
    MEDRXIV("medrxiv"),
    S2("s2"),
    ;

    companion object {
        /** Reserved non-arXiv prefixes → [Source] (ARXIV is the un-prefixed default, never a prefix). */
        internal val BY_PREFIX: Map<String, Source> =
            entries.filter { it != ARXIV }.associateBy { it.wire }
    }
}
