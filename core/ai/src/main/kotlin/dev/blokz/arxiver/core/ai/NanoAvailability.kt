package dev.blokz.arxiver.core.ai

/**
 * Reports whether system Gemini Nano is usable (ML Kit GenAI `checkStatus()`).
 * P1.2b ships [StubNanoAvailability] (always UNAVAILABLE); the real ML Kit-backed
 * implementation lands in P1.2c. Keeping it behind this seam lets P1.2b avoid the
 * ML Kit dependency while the tiering already accounts for Nano.
 */
interface NanoAvailability {
    suspend fun status(): NanoStatus
}

/** Until P1.2c wires ML Kit, Nano is reported unavailable everywhere. */
class StubNanoAvailability : NanoAvailability {
    override suspend fun status(): NanoStatus = NanoStatus.UNAVAILABLE
}
