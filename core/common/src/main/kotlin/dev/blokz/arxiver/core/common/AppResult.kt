package dev.blokz.arxiver.core.common

/**
 * Result type carried across layer boundaries (ARCHITECTURE §4):
 * repositories return this instead of throwing.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>

    data class Failure(val error: AppError) : AppResult<Nothing>
}

sealed interface AppError {
    /** No connectivity or request could not reach the server. */
    data object Offline : AppError

    /** Upstream throttling or our own rate-limit queue timed out. */
    data object RateLimited : AppError

    /** Upstream returned an unexpected response. */
    data class Upstream(val httpCode: Int?, val message: String? = null) : AppError

    /** Local persistence problem. */
    data class Storage(val message: String? = null) : AppError

    data class Unexpected(val cause: Throwable? = null) : AppError
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> =
    when (this) {
        is AppResult.Success -> AppResult.Success(transform(value))
        is AppResult.Failure -> this
    }

inline fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) block(value)
    return this
}

inline fun <T> AppResult<T>.onFailure(block: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) block(error)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value
