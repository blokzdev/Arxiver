package dev.blokz.arxiver.sync

import dev.blokz.arxiver.core.database.entity.RelevanceModelEntity
import dev.blokz.arxiver.core.search.eval.PlattMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The P5.5 downgrade-hysteresis state machine as a pure function — no Room, no coroutines. Exhaustively
 * covers every (previous × fresh) transition: fresh always applies now; one null keeps once; a second null
 * downgrades; never-fitted stays null.
 */
class ResolveCalibrationWriteTest {
    private fun row(
        a: Double?,
        b: Double?,
        streak: Int,
        fittedAt: Long = 100L,
    ) = RelevanceModelEntity(
        id = 0,
        embeddingModel = "bge",
        calibrationA = a,
        calibrationB = b,
        shrinkageLambda = 0.0,
        labelPositives = 60,
        labelNegatives = 40,
        fittedAt = fittedAt,
        consecutiveNullFits = streak,
    )

    @Test
    fun `a fresh fit always applies immediately and resets the streak`() {
        // null -> fitted (first ever)
        resolveCalibrationWrite(previous = null, fresh = PlattMap(2.0, -1.0), now = 500L).let {
            assertEquals(2.0, it.a)
            assertEquals(-1.0, it.b)
            assertEquals(0, it.consecutiveNullFits)
            assertEquals(500L, it.fittedAt)
        }
        // kept (streak 1) -> fitted again: recovery applies immediately, streak clears
        resolveCalibrationWrite(row(2.0, -1.0, streak = 1), PlattMap(3.0, -0.5), now = 500L).let {
            assertEquals(3.0, it.a)
            assertEquals(0, it.consecutiveNullFits)
            assertEquals(500L, it.fittedAt)
        }
    }

    @Test
    fun `a single null fit keeps the previous calibration for exactly one pass`() {
        val write = resolveCalibrationWrite(row(2.0, -1.0, streak = 0, fittedAt = 100L), fresh = null, now = 500L)
        assertEquals(2.0, write.a, "kept a")
        assertEquals(-1.0, write.b, "kept b")
        assertEquals(1, write.consecutiveNullFits, "streak advances to 1")
        assertEquals(100L, write.fittedAt, "fittedAt retains the kept a/b's vintage, not `now`")
    }

    @Test
    fun `a second consecutive null fit downgrades to the legacy regime`() {
        val write = resolveCalibrationWrite(row(2.0, -1.0, streak = 1), fresh = null, now = 500L)
        assertNull(write.a)
        assertNull(write.b)
        assertEquals(0, write.consecutiveNullFits)
        assertEquals(500L, write.fittedAt, "a downgrade stamps `now`")
    }

    @Test
    fun `a never-fitted profile stays null`() {
        resolveCalibrationWrite(previous = null, fresh = null, now = 500L).let {
            assertNull(it.a)
            assertNull(it.b)
            assertEquals(0, it.consecutiveNullFits)
        }
        // already-downgraded row (a null, streak 0) with another null stays null
        resolveCalibrationWrite(row(null, null, streak = 0), fresh = null, now = 500L).let {
            assertNull(it.a)
            assertEquals(0, it.consecutiveNullFits)
        }
    }

    @Test
    fun `a half-populated previous row is not kept`() {
        // (a!=null, b==null) is unreachable in production but must never be kept as a usable pair.
        val write = resolveCalibrationWrite(row(2.0, null, streak = 0), fresh = null, now = 500L)
        assertNull(write.a, "a lone slope is not a usable calibration — downgrade rather than keep half")
        assertEquals(0, write.consecutiveNullFits)
    }
}
