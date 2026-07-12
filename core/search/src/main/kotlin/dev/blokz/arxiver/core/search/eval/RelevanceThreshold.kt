package dev.blokz.arxiver.core.search.eval

/**
 * The single source of truth for the "Likely relevant" raw-score cut (P5.3; centralised in P-Ambient PA.1a).
 *
 * `inbox_items.score` persists the RAW Rocchio blend; a fitted per-user Platt calibration is **monotone**, so it
 * only translates the p=0.5 point ("more likely relevant than not") into a raw-score cut — it never reorders. An
 * uncalibrated (below-floor) profile keeps EXACTLY the legacy [LEGACY_CUT]. Every surface that draws the section —
 * the Today feed, the debug ranker-health card, and the ambient digest/widget — resolves the cut HERE, so they can
 * never drift apart ("digest says 5, Today shows 3").
 */
object RelevanceThreshold {
    /** The pre-P5.3 hardcoded cut — what an uncalibrated (below-floor) profile keeps, exactly. */
    const val LEGACY_CUT = 0.55

    /**
     * The raw-score cut for a calibration of ([calibrationA], [calibrationB]): the calibrated p=0.5 point when
     * BOTH are present, else [LEGACY_CUT]. `a > 0` is the fitter's monotonicity invariant, so the inverse is safe.
     */
    fun cut(
        calibrationA: Double?,
        calibrationB: Double?,
    ): Double =
        if (calibrationA != null && calibrationB != null) {
            PlattMap(calibrationA, calibrationB).scoreFor(0.5)
        } else {
            LEGACY_CUT
        }
}
