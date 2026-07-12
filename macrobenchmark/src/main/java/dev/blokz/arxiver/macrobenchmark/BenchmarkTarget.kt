package dev.blokz.arxiver.macrobenchmark

// Shared UiAutomator selectors + timings for the P-Prove PP.3b suites.

/** The app under test — no `applicationIdSuffix` on any variant, so this is stable across benchmark variants. */
const val TARGET_PACKAGE = "dev.blokz.arxiver"

// Compose testTags surfaced as resource-ids (testTagsAsResourceId=true at the app root ⇒ By.res(...) resolves them).
const val TODAY_SCREEN = "today_screen"
const val SEARCH_SCREEN = "search_screen"
const val SEARCH_FIELD = "search_field"
const val SEMANTIC_ACTIVE = "semantic_active"
const val NAV_EXPLORE = "nav_explore"

/** The seeded-corpus content anchor (TestCorpusSeeder titles) — a content-ready gate, not just `today_screen`. */
const val SEEDED_ANCHOR = "Seeded Paper"

const val UI_TIMEOUT_MS = 5_000L
const val SEED_TIMEOUT_MS = 30_000L
const val BENCHMARK_ITERATIONS = 10
