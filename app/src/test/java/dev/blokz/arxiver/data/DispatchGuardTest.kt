package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.claude.RoutineAction
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The empty-dispatch guard (PS.2, folding in the PS.1-deferred paperless-envelope case). Pure logic, so
 * it is verified without standing up the DispatchRepository's Room + TokenVault graph; the wiring in
 * `dispatch()` returning [DispatchSubmission.NothingToDispatch] is compile- and build-gate-covered.
 *
 * `isEmptyNonPingDispatch` args are (requestedPaperCount, builtPaperCount, action).
 */
class DispatchGuardTest {
    @Test
    fun `a non-empty selection that builds zero papers is suppressed for a non-PING action`() {
        // e.g. an all-non-arXiv (bio/med/chemRxiv) selection dropped by the arXiv-only payload chokepoint.
        assertTrue(isEmptyNonPingDispatch(2, 0, RoutineAction.DIGEST))
        assertTrue(isEmptyNonPingDispatch(5, 0, RoutineAction.DEEP_DIVE))
    }

    @Test
    fun `a selection that keeps at least one paper dispatches`() {
        assertFalse(isEmptyNonPingDispatch(3, 1, RoutineAction.DIGEST))
    }

    @Test
    fun `an empty request is not suppressed - nothing was dropped`() {
        // 0 requested → 0 built is a legitimately empty dispatch, not an all-filtered-out one.
        assertFalse(isEmptyNonPingDispatch(0, 0, RoutineAction.CUSTOM))
    }

    @Test
    fun `PING is never suppressed even with zero papers`() {
        assertFalse(isEmptyNonPingDispatch(0, 0, RoutineAction.PING))
        assertFalse(isEmptyNonPingDispatch(2, 0, RoutineAction.PING))
    }
}
