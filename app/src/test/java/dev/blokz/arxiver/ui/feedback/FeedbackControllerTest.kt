package dev.blokz.arxiver.ui.feedback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackControllerTest {
    @Test
    fun `plain message gets the short window, action-bearing the long one`() {
        assertEquals(FeedbackDuration.SHORT_MS, FeedbackMessage("hi").durationMillis)
        assertEquals(
            FeedbackDuration.LONG_MS,
            FeedbackMessage("hi", primary = FeedbackAction("Undo") {}).durationMillis,
        )
        assertEquals(
            FeedbackDuration.LONG_MS,
            FeedbackMessage("hi", secondary = FeedbackAction("Add") {}).durationMillis,
        )
    }

    @Test
    fun `messages reach an active collector in order`() =
        runTest {
            val controller = FeedbackController()
            val received = mutableListOf<String>()
            val job = launch { controller.messages.collect { received += it.text } }
            runCurrent()

            controller.show(FeedbackMessage("a"))
            runCurrent() // collector consumes "a" before the next emit
            controller.show(FeedbackMessage("b"))
            runCurrent()
            job.cancel()

            assertEquals(listOf("a", "b"), received)
        }

    @Test
    fun `a backlog behind a slow collector drops the oldest, not the newest`() =
        runTest {
            val controller = FeedbackController()
            val received = mutableListOf<String>()
            val job =
                launch {
                    controller.messages.collect {
                        received += it.text
                        delay(1_000) // slow consumer keeps the buffer under pressure
                    }
                }
            runCurrent()

            controller.show(FeedbackMessage("a")) // handed to the collector, which then suspends
            runCurrent()
            controller.show(FeedbackMessage("b")) // buffered (capacity 1)
            controller.show(FeedbackMessage("c")) // overflow → drops "b", keeps "c"
            advanceUntilIdle()
            job.cancel()

            assertEquals(listOf("a", "c"), received)
        }
}
