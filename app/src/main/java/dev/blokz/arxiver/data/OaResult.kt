package dev.blokz.arxiver.data

import dev.blokz.arxiver.core.common.AppError

/**
 * Outcome of the P-OA open-access resolver ([PaperRepository.resolveOaFulltext]). Three-way by design so the UI
 * can tell a genuine "no free version exists" ([None]) apart from a transient network failure ([Error]) — the
 * former is a calm terminal state, the latter a retryable one. [Found.versionOfRecord] false means the paper's
 * OWN open PDF (label it "free", never "published").
 */
sealed interface OaResult {
    data class Found(
        val pdfUrl: String,
        val journalName: String?,
        val versionOfRecord: Boolean,
    ) : OaResult

    data object None : OaResult

    data class Error(val cause: AppError) : OaResult
}
