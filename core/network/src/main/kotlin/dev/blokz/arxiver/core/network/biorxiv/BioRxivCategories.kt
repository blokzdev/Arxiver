package dev.blokz.arxiver.core.network.biorxiv

/**
 * The native bioRxiv/medRxiv subject categories, the category vocabulary for bio/med follows (P-Feeds PF.3).
 * Each value is the lowercase, space-form string `api.biorxiv.org` returns in a work's `category` field and
 * accepts in `?category=` (the server normalizes space↔underscore, so the space form is sent verbatim and
 * URL-encoded by OkHttp — live-verified 2026-07-06). Stored in `follows.value`; a picker title-cases for display.
 *
 * COMPILE-TIME constants (there is no categories endpoint): [BIORXIV_CATEGORIES] is bioRxiv's canonical 27;
 * [MEDRXIV_CATEGORIES] is the set observed live across the medRxiv feed. A fixture-backed drift guard pins
 * them against a recorded feed sample so a server-side rename is caught at CI, not by a silently-empty follow.
 */
val BIORXIV_CATEGORIES: List<String> =
    listOf(
        "animal behavior and cognition",
        "biochemistry",
        "bioengineering",
        "bioinformatics",
        "biophysics",
        "cancer biology",
        "cell biology",
        "clinical trials",
        "developmental biology",
        "ecology",
        "epidemiology",
        "evolutionary biology",
        "genetics",
        "genomics",
        "immunology",
        "microbiology",
        "molecular biology",
        "neuroscience",
        "paleontology",
        "pathology",
        "pharmacology and toxicology",
        "physiology",
        "plant biology",
        "scientific communication and education",
        "synthetic biology",
        "systems biology",
        "zoology",
    )

val MEDRXIV_CATEGORIES: List<String> =
    listOf(
        "addiction medicine",
        "allergy and immunology",
        "anesthesia",
        "cardiovascular medicine",
        "dentistry and oral medicine",
        "dermatology",
        "emergency medicine",
        "endocrinology",
        "epidemiology",
        "gastroenterology",
        "genetic and genomic medicine",
        "geriatric medicine",
        "health economics",
        "health informatics",
        "health policy",
        "health systems and quality improvement",
        "hematology",
        "hiv aids",
        "infectious diseases",
        "intensive care and critical care medicine",
        "medical education",
        "nephrology",
        "neurology",
        "nursing",
        "nutrition",
        "obstetrics and gynecology",
        "occupational and environmental health",
        "oncology",
        "ophthalmology",
        "orthopedics",
        "otolaryngology",
        "pain medicine",
        "pathology",
        "pediatrics",
        "pharmacology and therapeutics",
        "primary care research",
        "psychiatry and clinical psychology",
        "public and global health",
        "radiology and imaging",
        "rehabilitation medicine and physical therapy",
        "respiratory medicine",
        "rheumatology",
        "sexual and reproductive health",
        "sports medicine",
        "surgery",
        "urology",
    )
