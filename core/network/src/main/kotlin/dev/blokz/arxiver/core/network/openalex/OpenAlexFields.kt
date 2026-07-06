package dev.blokz.arxiver.core.network.openalex

/**
 * The 26 top-level OpenAlex **Fields** (`GET /fields`), the category vocabulary for OpenAlex-served follows
 * (P-Feeds PF.3). [token] is the filter-ready value stored in `follows.value` and appended to a browse as
 * `primary_topic.field.id:<token>`; [displayName] is OpenAlex's own byte-exact label (live-captured 2026-07-06).
 *
 * This is a COMPILE-TIME constant on purpose (the OpenAlex metering red line): a picker populates its Field
 * list from here — never a live `/fields` fetch (a browse/filter req costs a credit). The ids are non-obvious
 * (Chemistry=16, NOT 23 which is Environmental Science) and a wrong id fails SILENTLY (HTTP 200, count=0), so
 * the id→name table is pinned by a structural test. See `.claude/memory/openalex-api-contract.md`.
 */
data class OpenAlexField(val token: String, val displayName: String)

/** Build the filter-ready token for OpenAlex Field id [n] (e.g. 16 → `"fields/16"`). */
private fun field(
    n: Int,
    displayName: String,
) = OpenAlexField("fields/$n", displayName)

/** All 26 OpenAlex Fields, id-ascending. A picker sorts by [OpenAlexField.displayName] for display. */
val OPENALEX_FIELDS: List<OpenAlexField> =
    listOf(
        field(11, "Agricultural and Biological Sciences"),
        field(12, "Arts and Humanities"),
        field(13, "Biochemistry, Genetics and Molecular Biology"),
        field(14, "Business, Management and Accounting"),
        field(15, "Chemical Engineering"),
        field(16, "Chemistry"),
        field(17, "Computer Science"),
        field(18, "Decision Sciences"),
        field(19, "Earth and Planetary Sciences"),
        field(20, "Economics, Econometrics and Finance"),
        field(21, "Energy"),
        field(22, "Engineering"),
        field(23, "Environmental Science"),
        field(24, "Immunology and Microbiology"),
        field(25, "Materials Science"),
        field(26, "Mathematics"),
        field(27, "Medicine"),
        field(28, "Neuroscience"),
        field(29, "Nursing"),
        field(30, "Pharmacology, Toxicology and Pharmaceutics"),
        field(31, "Physics and Astronomy"),
        field(32, "Psychology"),
        field(33, "Social Sciences"),
        field(34, "Veterinary"),
        field(35, "Dentistry"),
        field(36, "Health Professions"),
    )
