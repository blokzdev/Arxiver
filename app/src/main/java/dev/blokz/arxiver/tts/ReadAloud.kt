package dev.blokz.arxiver.tts

import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow seam over [TextToSpeechManager] (P-Share PS.2) so the Ask ViewModel stays DAO/engine-free
 * and unit-testable (mirrors `RelationGraphSource`/`CollectionGraphSource`). [speakingId] is the key
 * of the answer currently being read aloud (or null) — it drives the UI play/stop toggle.
 */
interface ReadAloud {
    val speakingId: StateFlow<String?>

    /** Toggle read-aloud for [id]: stop if it's already reading, else speak [text] (the spoken form). */
    fun toggle(
        id: String,
        text: String,
    )

    fun stop()
}
