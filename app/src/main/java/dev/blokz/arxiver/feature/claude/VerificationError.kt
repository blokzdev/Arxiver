package dev.blokz.arxiver.feature.claude

import dev.blokz.arxiver.data.DispatchRepository
import dev.blokz.arxiver.data.DispatchSubmission

/**
 * SPEC-CLAUDE-BRIDGE §8.2 error taxonomy — each class carries a distinct
 * likely cause and actionable fix in the wizard's troubleshooting UI.
 */
sealed interface VerificationError {
    /** 401/403 — token mistyped, revoked, or from another routine. */
    data object BadToken : VerificationError

    /** 404 — trigger URL wrong or truncated. */
    data object WrongUrl : VerificationError

    /** 400 — likely a claude.ai page URL pasted instead of the trigger URL. */
    data object BadRequest : VerificationError

    /** Other permanent rejection. */
    data class Rejected(val httpCode: Int?) : VerificationError

    /** 5xx — Claude-side; the ping stays queued and auto-retries. */
    data class ServerError(val httpCode: Int) : VerificationError

    /** No connectivity — ping queued, auto-sends when back online. */
    data object Offline : VerificationError

    /** Stored token undecryptable (e.g. after a backup restore). */
    data object TokenUnavailable : VerificationError

    companion object {
        /** Null for non-error outcomes ([DispatchSubmission.Sent]). */
        fun from(submission: DispatchSubmission): VerificationError? =
            when (submission) {
                is DispatchSubmission.Sent -> null
                is DispatchSubmission.AuthRejected -> BadToken
                is DispatchSubmission.Queued ->
                    submission.httpCode?.let(::ServerError) ?: Offline
                is DispatchSubmission.Failed ->
                    when {
                        submission.reason == DispatchRepository.REASON_TOKEN_UNAVAILABLE -> TokenUnavailable
                        submission.httpCode == 404 -> WrongUrl
                        submission.httpCode == 400 -> BadRequest
                        else -> Rejected(submission.httpCode)
                    }
                is DispatchSubmission.PayloadTooLarge -> Rejected(null)
            }
    }
}
