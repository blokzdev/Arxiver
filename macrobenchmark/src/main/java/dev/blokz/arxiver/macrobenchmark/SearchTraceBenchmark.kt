package dev.blokz.arxiver.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.blokz.arxiver.core.search.SearchTrace
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The mandatory hybrid-search trace suite (D2) — without it the trace sections never fire and D2 is never captured.
 * Reads all three sections; `vector_topk_scan` fires once per chunk so it is summed. The end-to-end `hybrid_search`
 * number INCLUDES `embedQuery`'s JNI cost — the true PRD/F5.4 latency.
 *
 * REQUIRES the BGE model provisioned on the device: the `semantic_active` gate makes a mis-provisioned run FAIL
 * LOUDLY (below) instead of silently measuring keyword-only with a zero-length `vector_topk_scan` (Finding #1).
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class SearchTraceBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun hybridSearch() =
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics =
                listOf(
                    TraceSectionMetric(SearchTrace.HYBRID_SEARCH, TraceSectionMetric.Mode.Sum),
                    TraceSectionMetric(SearchTrace.HYBRID_FUSE, TraceSectionMetric.Mode.Sum),
                    TraceSectionMetric(SearchTrace.VECTOR_TOPK_SCAN, TraceSectionMetric.Mode.Sum),
                ),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = BENCHMARK_ITERATIONS,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                device.wait(Until.hasObject(By.textContains(SEEDED_ANCHOR)), SEED_TIMEOUT_MS)
                device.findObject(By.res(NAV_EXPLORE)).click()
                device.wait(Until.hasObject(By.res(SEARCH_SCREEN)), UI_TIMEOUT_MS)
            },
        ) {
            device.findObject(By.res(SEARCH_FIELD)).text = "transformer attention"
            check(device.wait(Until.hasObject(By.res(SEMANTIC_ACTIVE)), UI_TIMEOUT_MS)) {
                "BGE model not ready: the semantic leg was skipped, so vector_topk_scan never fired. Provision the " +
                    "model on the device before running D2 (P-Prove Finding #1)."
            }
        }
}
