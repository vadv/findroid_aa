package dev.jdtech.jellyfin.car

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import timber.log.Timber

/**
 * Retries a network call up to N attempts with exponential backoff, treating only
 * transport-level / transient failures (network IO, timeouts, 5xx, 408, 429) as retryable.
 * Auth / forbidden / not-found and other 4xx errors short-circuit immediately.
 *
 * Callers receive a [Status] so they can keep cached UI state on exhausted retries instead
 * of replacing it with an error overlay.
 */
internal object FindroidCarNetworkRetry {
    sealed interface Status<out T> {
        data class Success<T>(val value: T, val attempts: Int) : Status<T>

        data class Failure(
            val failure: Throwable,
            val attempts: Int,
            val terminal: Boolean,
        ) : Status<Nothing>
    }

    suspend fun <T> attempt(
        label: String,
        block: suspend () -> T,
    ): Status<T> =
        attempt(
            label = label,
            backoffMs = DEFAULT_BACKOFFS_MS,
            maxAttempts = DEFAULT_MAX_ATTEMPTS,
            sleep = { delay(it) },
            block = block,
        )

    internal suspend fun <T> attempt(
        label: String,
        backoffMs: LongArray,
        maxAttempts: Int,
        sleep: suspend (Long) -> Unit,
        block: suspend () -> T,
    ): Status<T> {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1 (was $maxAttempts)" }
        var lastError: Throwable? = null
        var terminal = false
        var attemptIndex = 0
        while (attemptIndex < maxAttempts) {
            if (attemptIndex > 0) {
                val waitMs = backoffMs.getOrElse(attemptIndex - 1) { backoffMs.last() }
                Timber.i(
                    "FindroidCarAA retry attempt %d/%d for %s (after %dms backoff)",
                    attemptIndex + 1,
                    maxAttempts,
                    label,
                    waitMs,
                )
                if (waitMs > 0L) sleep(waitMs)
            }
            attemptIndex++
            try {
                return Status.Success(block(), attempts = attemptIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                if (!isRetryable(e)) {
                    Timber.w(
                        e,
                        "FindroidCarAA non-retryable error on attempt %d for %s",
                        attemptIndex,
                        label,
                    )
                    terminal = true
                    break
                }
                Timber.w(
                    e,
                    "FindroidCarAA attempt %d failed for %s (retryable)",
                    attemptIndex,
                    label,
                )
            }
        }
        val finalError =
            lastError
                ?: IOException("FindroidCarAA: $label failed without an underlying exception")
        return Status.Failure(failure = finalError, attempts = attemptIndex, terminal = terminal)
    }

    fun friendlyMessage(throwable: Throwable): String {
        val status = httpStatusCode(throwable)
        if (status != null) {
            return when (status) {
                401, 403 -> "Сессия истекла, нужно войти заново"
                404, 410 -> "Запись не найдена на сервере"
                in 500..599 -> "Сервер вернул ошибку"
                in 400..499 -> "Сервер отклонил запрос"
                else -> "Сервер вернул неожиданный ответ"
            }
        }
        return when (throwable) {
            is UnknownHostException -> "Не могу найти сервер"
            is SocketTimeoutException, is TimeoutException -> "Сервер не отвечает"
            is ConnectException, is NoRouteToHostException -> "Нет соединения с сервером"
            is SSLException, is SecureConnectionException -> "Сбой защищённого соединения"
            is IOException -> "Нет соединения с сервером"
            else -> "Что-то пошло не так"
        }
    }

    fun staleCacheMessage(throwable: Throwable): String =
        "${friendlyMessage(throwable)} — показываю кэш"

    fun isRetryable(throwable: Throwable): Boolean {
        if (throwable is CancellationException) return false
        val status = httpStatusCode(throwable)
        if (status != null) {
            return when (status) {
                408, 429 -> true
                in 500..599 -> true
                else -> false
            }
        }
        return when (throwable) {
            is UnknownHostException,
            is NoRouteToHostException,
            is SocketTimeoutException,
            is ConnectException,
            is TimeoutException,
            is SSLException,
            is SecureConnectionException -> true
            is IOException -> true
            else -> false
        }
    }

    private fun httpStatusCode(throwable: Throwable): Int? {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 8) {
            if (current is InvalidStatusException) return current.status
            current = current.cause
            depth++
        }
        return null
    }

    private val DEFAULT_BACKOFFS_MS = longArrayOf(500L, 1_500L, 4_000L)
    private const val DEFAULT_MAX_ATTEMPTS = 4
}
