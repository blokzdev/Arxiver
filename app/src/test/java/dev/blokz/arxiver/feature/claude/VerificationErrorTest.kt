package dev.blokz.arxiver.feature.claude

import dev.blokz.arxiver.data.DispatchRepository
import dev.blokz.arxiver.data.DispatchSubmission
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** SPEC-CLAUDE-BRIDGE §8.2: every submission shape maps to one error class. */
class VerificationErrorTest {
    private fun from(submission: DispatchSubmission) = VerificationError.from(submission)

    @Test
    fun `sent is not an error`() {
        assertNull(from(DispatchSubmission.Sent(1)))
    }

    @Test
    fun `auth rejection is a bad token`() {
        assertEquals(VerificationError.BadToken, from(DispatchSubmission.AuthRejected(1)))
    }

    @Test
    fun `404 is a wrong url and 400 a bad request`() {
        assertEquals(VerificationError.WrongUrl, from(DispatchSubmission.Failed(1, "HTTP 404", 404)))
        assertEquals(VerificationError.BadRequest, from(DispatchSubmission.Failed(1, "HTTP 400", 400)))
    }

    @Test
    fun `other 4xx and codeless failures are plain rejections`() {
        assertEquals(VerificationError.Rejected(422), from(DispatchSubmission.Failed(1, "HTTP 422", 422)))
        assertEquals(VerificationError.Rejected(null), from(DispatchSubmission.Failed(1, "routine deleted")))
    }

    @Test
    fun `queued with a code is server trouble, without one offline`() {
        assertEquals(VerificationError.ServerError(500), from(DispatchSubmission.Queued(1, 500)))
        assertEquals(VerificationError.Offline, from(DispatchSubmission.Queued(1, null)))
    }

    @Test
    fun `vault miss maps to token-unavailable, not a generic rejection`() {
        assertEquals(
            VerificationError.TokenUnavailable,
            from(DispatchSubmission.Failed(1, DispatchRepository.REASON_TOKEN_UNAVAILABLE)),
        )
    }
}
