package dev.blokz.arxiver.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A thin lifecycle-owning wrapper over Android [TextToSpeech] for the read-aloud feature (P-Share
 * PS.2). Fully on-device (the system TTS engine works offline) — **no network, no AI key, no
 * telemetry**. Speaks the plain string the caller hands it (the pure [SpeakableText] extractor turns
 * an answer into that string); this class owns only the engine + the speaking state.
 *
 * `[speakingId]` is the key of the answer currently being read (or null) — it drives the UI play/stop
 * toggle. Init is async (TTS calls back on a worker), so a [speak] that arrives before the engine is
 * ready is queued and fired on init; if the engine or a voice is unavailable the request is dropped
 * cleanly (the UI shows "read-aloud unavailable"), never a crash. Long answers are chunked by
 * sentence under the engine's input cap and queued, with only the final chunk clearing [speakingId].
 */
@Singleton
class TextToSpeechManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ReadAloud {
        private var tts: TextToSpeech? = null
        private var ready = false
        private var pending: Pair<String, String>? = null

        private val _speakingId = MutableStateFlow<String?>(null)
        override val speakingId: StateFlow<String?> = _speakingId.asStateFlow()

        /** Toggle: if [id] is already reading, stop; otherwise speak [text] (the pre-extracted spoken form). */
        override fun toggle(
            id: String,
            text: String,
        ) {
            if (_speakingId.value == id) stop() else speak(id, text)
        }

        fun speak(
            id: String,
            text: String,
        ) {
            if (text.isBlank()) return
            val engine = tts
            if (engine != null && ready) {
                enqueue(engine, id, text)
                return
            }
            // First use (or a still-initialising engine): queue the request and (lazily) init.
            pending = id to text
            if (engine == null) {
                tts =
                    TextToSpeech(context) { status ->
                        ready = status == TextToSpeech.SUCCESS && configureLanguage()
                        val queued = pending
                        pending = null
                        if (ready && queued != null) {
                            enqueue(tts!!, queued.first, queued.second)
                        } else {
                            _speakingId.value = null
                        }
                    }
            }
        }

        override fun stop() {
            tts?.stop()
            _speakingId.value = null
        }

        /** Release the engine (call from the owning ViewModel's `onCleared`). */
        fun shutdown() {
            tts?.stop()
            tts?.shutdown()
            tts = null
            ready = false
            _speakingId.value = null
        }

        private fun configureLanguage(): Boolean {
            val engine = tts ?: return false
            val result = engine.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                val fallback = engine.setLanguage(Locale.ENGLISH)
                if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                    return false
                }
            }
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        // Only the final chunk carries the bare id; earlier chunks are id#n.
                        if (utteranceId != null && _speakingId.value == utteranceId) _speakingId.value = null
                    }

                    @Deprecated("Deprecated in Java", ReplaceWith("Unit"))
                    override fun onError(utteranceId: String?) {
                        if (utteranceId != null && utteranceId.substringBefore('#') == _speakingId.value) {
                            _speakingId.value = null
                        }
                    }
                },
            )
            return true
        }

        private fun enqueue(
            engine: TextToSpeech,
            id: String,
            text: String,
        ) {
            _speakingId.value = id
            val cap = (TextToSpeech.getMaxSpeechInputLength() - 1).coerceAtLeast(200)
            val chunks = chunk(text, cap)
            chunks.forEachIndexed { i, piece ->
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                // The LAST chunk gets the bare id so its onDone clears the speaking state.
                val utteranceId = if (i == chunks.lastIndex) id else "$id#$i"
                engine.speak(piece, mode, null, utteranceId)
            }
        }

        companion object {
            /** Split [text] into chunks ≤ [cap] chars, preferring sentence then whitespace boundaries. Pure. */
            internal fun chunk(
                text: String,
                cap: Int,
            ): List<String> {
                if (text.length <= cap) return listOf(text)
                val out = ArrayList<String>()
                var rest = text
                while (rest.length > cap) {
                    val window = rest.substring(0, cap)
                    val sentence = window.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
                    val space = window.lastIndexOf(' ')
                    val cut =
                        when {
                            sentence >= cap / 2 -> sentence + 1
                            space >= cap / 2 -> space + 1
                            else -> cap
                        }
                    out.add(rest.substring(0, cut).trim())
                    rest = rest.substring(cut)
                }
                if (rest.isNotBlank()) out.add(rest.trim())
                return out.filter { it.isNotBlank() }
            }
        }
    }
