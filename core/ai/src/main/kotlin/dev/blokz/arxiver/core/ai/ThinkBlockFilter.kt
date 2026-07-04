package dev.blokz.arxiver.core.ai

/**
 * Strips a leading Qwen3 `<think>…</think>` reasoning block from a streamed reply (PA.6 follow-up).
 *
 * Qwen3 is a hybrid thinking model: without a switch it prefixes answers with a chain-of-thought
 * block, which rendered verbatim to users (observed on-device 2026-07-03, K20 — the "verbose
 * thinking-out-loud style" K10 noted). [QwenEngine] disables thinking with the documented
 * `/no_think` soft switch, which still emits an *empty* `<think>\n\n</think>` pair — this filter
 * removes whatever think block arrives, empty or full, across arbitrary chunk boundaries.
 *
 * One instance per stream (stateful). Behavior: buffers while the stream could still be opening a
 * think block, swallows through `</think>`, then passes everything through untouched. A reply that
 * never opens a think block is passed through from the first confirmed non-think character.
 */
internal class ThinkBlockFilter {
    private enum class Mode { PROBING, IN_THINK, PASS }

    private var mode = Mode.PROBING
    private val buffer = StringBuilder()

    /** Returns the user-visible portion of [delta] ("" while inside/probing a think block). */
    fun filter(delta: String): String =
        when (mode) {
            Mode.PASS -> delta
            Mode.IN_THINK -> {
                buffer.append(delta)
                drainAfterClose()
            }
            Mode.PROBING -> {
                buffer.append(delta)
                val trimmed = buffer.toString().trimStart()
                when {
                    trimmed.startsWith(OPEN) -> {
                        mode = Mode.IN_THINK
                        drainAfterClose()
                    }
                    // Could still become "<think>" (e.g. "<th" so far) — keep buffering.
                    trimmed.isEmpty() || OPEN.startsWith(trimmed) -> ""
                    else -> {
                        mode = Mode.PASS
                        val text = buffer.toString()
                        buffer.clear()
                        text
                    }
                }
            }
        }

    private fun drainAfterClose(): String {
        val text = buffer.toString()
        val close = text.indexOf(CLOSE)
        if (close < 0) return ""
        mode = Mode.PASS
        buffer.clear()
        return text.substring(close + CLOSE.length).trimStart()
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
