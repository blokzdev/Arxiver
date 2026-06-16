package dev.blokz.arxiver.ui.feedback

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Auto-dismiss windows. Action-bearing messages linger longer so the action is reachable. */
object FeedbackDuration {
    const val SHORT_MS = 4_000L
    const val LONG_MS = 8_000L
}

/** A tappable button on a feedback message. The lambda runs on the main thread when tapped. */
data class FeedbackAction(
    val label: String,
    val onPerform: () -> Unit,
)

/**
 * One transient message for the app-level feedback host. Actions are lambdas resolved at the emit
 * site, so "Undo" can call a ViewModel method and "Add to collection" can open a sheet — the
 * controller stays logic-free.
 */
data class FeedbackMessage(
    val text: String,
    val primary: FeedbackAction? = null,
    val secondary: FeedbackAction? = null,
    val durationMillis: Long =
        if (primary != null || secondary != null) FeedbackDuration.LONG_MS else FeedbackDuration.SHORT_MS,
)

/**
 * App-wide feedback bus. Any ViewModel or composable injects/reads this and calls [show]; the single
 * [FeedbackHost] mounted at the app shell renders one message at a time. A `@Singleton` (not a
 * `CompositionLocal`) so ViewModels can reach it too, matching the repo's seam idiom
 * (`AiKeyStore`, `OnDeviceModelController`).
 */
@Singleton
class FeedbackController
    @Inject
    constructor() {
        private val _messages =
            MutableSharedFlow<FeedbackMessage>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val messages: SharedFlow<FeedbackMessage> = _messages.asSharedFlow()

        /** Fire-and-forget; never suspends. A backlogged message drops the oldest, not the newest. */
        fun show(message: FeedbackMessage) {
            _messages.tryEmit(message)
        }
    }

/**
 * Lets composables reach the controller without threading it through every screen. Defaults to a
 * throwaway instance so previews/tests render without a provider; the real singleton is provided at
 * the app shell.
 */
val LocalFeedbackController = staticCompositionLocalOf { FeedbackController() }
