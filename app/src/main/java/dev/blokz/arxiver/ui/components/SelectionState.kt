package dev.blokz.arxiver.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Ephemeral multi-select state for a paper list, keyed by `paper.id.value` (the same stable keys the
 * lists already use). Lives in the UI, not the ViewModel: it's a view concern, survives rotation via
 * [rememberSelectionState], and dies with the screen.
 */
@Stable
class SelectionState(initial: Set<String> = emptySet()) {
    var selected by mutableStateOf(initial)
        private set

    /** Selection mode is "on" whenever anything is selected. */
    val isActive: Boolean get() = selected.isNotEmpty()
    val count: Int get() = selected.size

    fun contains(id: String): Boolean = id in selected

    fun toggle(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun clear() {
        selected = emptySet()
    }

    companion object {
        val Saver =
            listSaver<SelectionState, String>(
                save = { it.selected.toList() },
                restore = { SelectionState(it.toSet()) },
            )
    }
}

@Composable
fun rememberSelectionState(): SelectionState = rememberSaveable(saver = SelectionState.Saver) { SelectionState() }
