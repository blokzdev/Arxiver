package dev.blokz.arxiver.core.network.arxiv

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class ArxivRateLimiterTest {
    @Test
    fun `first acquire is immediate`() =
        runTest {
            val limiter = ArxivRateLimiter(nowMs = { currentTime })
            limiter.acquire()
            assertEquals(0, currentTime)
        }

    @Test
    fun `second acquire waits out the spacing`() =
        runTest {
            val limiter = ArxivRateLimiter(nowMs = { currentTime })
            limiter.acquire()
            limiter.acquire()
            assertEquals(3_000, currentTime)
        }

    @Test
    fun `concurrent acquires are serialized 3s apart`() =
        runTest {
            val limiter = ArxivRateLimiter(nowMs = { currentTime })
            val grantTimes = mutableListOf<Long>()
            repeat(3) {
                launch {
                    limiter.acquire()
                    grantTimes += currentTime
                }
            }
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(0L, 3_000L, 6_000L), grantTimes)
        }

    @Test
    fun `no wait when spacing already elapsed`() =
        runTest {
            val limiter = ArxivRateLimiter(nowMs = { currentTime })
            limiter.acquire()
            testScheduler.advanceTimeBy(10_000)
            val before = currentTime
            limiter.acquire()
            assertEquals(before, currentTime)
        }
}
