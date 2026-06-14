package dev.blokz.arxiver.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppResultTest {
    @Test
    fun `map transforms a success value`() {
        val result: AppResult<Int> = AppResult.Success(21)
        assertEquals(AppResult.Success(42), result.map { it * 2 })
    }

    @Test
    fun `map leaves a failure untouched and does not run the transform`() {
        val failure: AppResult<Int> = AppResult.Failure(AppError.Offline)
        var ran = false
        val mapped =
            failure.map {
                ran = true
                it * 2
            }
        assertEquals(failure, mapped)
        assertTrue(!ran, "transform must not run on failure")
    }

    @Test
    fun `onSuccess runs only for success and returns the same instance`() {
        val success: AppResult<String> = AppResult.Success("ok")
        var seen: String? = null
        val returned = success.onSuccess { seen = it }
        assertEquals("ok", seen)
        assertSame(success, returned)

        var ranOnFailure = false
        AppResult.Failure(AppError.RateLimited).onSuccess { ranOnFailure = true }
        assertTrue(!ranOnFailure)
    }

    @Test
    fun `onFailure runs only for failure with the carried error`() {
        var seen: AppError? = null
        AppResult.Failure(AppError.Storage("disk full")).onFailure { seen = it }
        assertEquals(AppError.Storage("disk full"), seen)

        var ranOnSuccess = false
        AppResult.Success(1).onFailure { ranOnSuccess = true }
        assertTrue(!ranOnSuccess)
    }

    @Test
    fun `getOrNull unwraps success and nulls failure`() {
        assertEquals(7, AppResult.Success(7).getOrNull())
        assertNull(AppResult.Failure(AppError.Offline).getOrNull())
    }

    @Test
    fun `error data types compare by value`() {
        assertEquals(AppError.Offline, AppError.Offline)
        assertEquals(AppError.Upstream(503, "busy"), AppError.Upstream(503, "busy"))
        assertTrue(AppError.Upstream(500) != AppError.Upstream(404))
        assertEquals(AppError.Storage(), AppError.Storage(null))
    }
}
