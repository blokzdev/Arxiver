package dev.blokz.arxiver.core.model

/**
 * Canonical arXiv identifier, always stored without version (SPEC-DATA §1).
 *
 * Handles both modern ids (`2403.01234`) and legacy ids (`math/0211159`,
 * `math.GT/0309136`), and parses them out of abs/pdf URLs and versioned forms.
 */
@JvmInline
value class ArxivId(val value: String) {
    override fun toString(): String = value

    fun absUrl(version: Int? = null): String = "https://arxiv.org/abs/$value" + (version?.let { "v$it" } ?: "")

    fun pdfUrl(version: Int? = null): String = "https://arxiv.org/pdf/$value" + (version?.let { "v$it" } ?: "")

    companion object {
        private val MODERN = Regex("""\d{4}\.\d{4,5}""")
        private val LEGACY = Regex("""[a-z-]+(\.[A-Z]{2})?/\d{7}""")
        private val VERSION_SUFFIX = Regex("""v(\d+)$""")
        private val URL_PREFIX = Regex("""^https?://(?:www\.)?arxiv\.org/(?:abs|pdf)/""")

        /**
         * Parses an arXiv id from a raw id, a versioned id, or an arxiv.org URL.
         * Returns the id and the version (null when unversioned).
         */
        fun parse(raw: String): Pair<ArxivId, Int?>? {
            var s = raw.trim().removePrefix("arXiv:")
            s = URL_PREFIX.replace(s, "")
            s = s.removeSuffix(".pdf").trimEnd('/')

            val version = VERSION_SUFFIX.find(s)?.groupValues?.get(1)?.toIntOrNull()
            val bare = VERSION_SUFFIX.replace(s, "")

            return if (MODERN.matches(bare) || LEGACY.matches(bare)) {
                ArxivId(bare) to version
            } else {
                null
            }
        }
    }
}
