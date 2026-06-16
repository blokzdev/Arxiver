package dev.blokz.arxiver.ui.feedback

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Thin holder that hands the injected singleton [FeedbackController] to the app shell, which is a
 * plain composable with no other ViewModel.
 */
@HiltViewModel
class FeedbackViewModel
    @Inject
    constructor(
        val controller: FeedbackController,
    ) : ViewModel()
