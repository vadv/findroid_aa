package dev.jdtech.jellyfin.car

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FindroidCarNetworkRetryTest {
    @Test
    fun success_on_first_attempt_runs_block_once_and_records_one_attempt() = runBlocking {
        val sleeps = mutableListOf<Long>()
        var calls = 0

        val status =
            FindroidCarNetworkRetry.attempt(
                label = "load-things",
                backoffMs = longArrayOf(10L, 30L, 90L),
                maxAttempts = 4,
                sleep = { sleeps += it },
            ) {
                calls++
                "ok"
            }

        val success = status as FindroidCarNetworkRetry.Status.Success<String>
        assertEquals("ok", success.value)
        assertEquals(1, success.attempts)
        assertEquals(1, calls)
        assertTrue("no sleep when first attempt succeeds", sleeps.isEmpty())
    }

    @Test
    fun retries_until_success_on_io_exception() = runBlocking {
        val sleeps = mutableListOf<Long>()
        var calls = 0

        val status =
            FindroidCarNetworkRetry.attempt(
                label = "fetch",
                backoffMs = longArrayOf(10L, 30L, 90L),
                maxAttempts = 4,
                sleep = { sleeps += it },
            ) {
                calls++
                if (calls < 3) throw IOException("boom $calls") else "settled"
            }

        val success = status as FindroidCarNetworkRetry.Status.Success<String>
        assertEquals("settled", success.value)
        assertEquals(3, success.attempts)
        assertEquals(3, calls)
        assertEquals(listOf(10L, 30L), sleeps)
    }

    @Test
    fun exhausts_retries_and_reports_last_throwable_as_non_terminal() = runBlocking {
        val errors = listOf(
            SocketTimeoutException("t1"),
            UnknownHostException("dns-2"),
            IOException("io-3"),
        )
        val sleeps = mutableListOf<Long>()
        var calls = 0

        val status =
            FindroidCarNetworkRetry.attempt(
                label = "exhaust",
                backoffMs = longArrayOf(10L, 30L, 90L),
                maxAttempts = errors.size,
                sleep = { sleeps += it },
            ) {
                val next = errors[calls]
                calls++
                throw next
            }

        val failure = status as FindroidCarNetworkRetry.Status.Failure
        assertEquals(errors.size, failure.attempts)
        assertSame(errors.last(), failure.failure)
        assertFalse("retryable failures must not be reported as terminal", failure.terminal)
        // Backoffs only happen BEFORE attempts 2 and 3.
        assertEquals(listOf(10L, 30L), sleeps)
    }

    @Test
    fun http_401_short_circuits_without_retry_and_is_marked_terminal() = runBlocking {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val authError = InvalidStatusException(401, RuntimeException("auth"))

        val status =
            FindroidCarNetworkRetry.attempt(
                label = "auth-call",
                backoffMs = longArrayOf(10L, 30L, 90L),
                maxAttempts = 4,
                sleep = { sleeps += it },
            ) {
                calls++
                throw authError
            }

        val failure = status as FindroidCarNetworkRetry.Status.Failure
        assertEquals(1, failure.attempts)
        assertSame(authError, failure.failure)
        assertTrue("401 must be terminal", failure.terminal)
        assertEquals(1, calls)
        assertTrue("no sleep on terminal first-attempt failure", sleeps.isEmpty())
    }

    @Test
    fun http_404_short_circuits_without_retry_and_is_marked_terminal() = runBlocking {
        val notFound = InvalidStatusException(404, RuntimeException("missing"))
        val sleeps = mutableListOf<Long>()
        var calls = 0

        val status =
            FindroidCarNetworkRetry.attempt(
                label = "not-found",
                backoffMs = longArrayOf(10L, 30L, 90L),
                maxAttempts = 4,
                sleep = { sleeps += it },
            ) {
                calls++
                throw notFound
            }

        val failure = status as FindroidCarNetworkRetry.Status.Failure
        assertEquals(1, failure.attempts)
        assertTrue(failure.terminal)
        assertEquals(1, calls)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun http_5xx_is_retryable_and_progresses_to_success() = runBlocking {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val transientServer = InvalidStatusException(503, RuntimeException("unavail"))

        val status =
            FindroidCarNetworkRetry.attempt(
                label = "flaky",
                backoffMs = longArrayOf(5L, 7L, 9L),
                maxAttempts = 3,
                sleep = { sleeps += it },
            ) {
                calls++
                if (calls == 1) throw transientServer else 42
            }

        val success = status as FindroidCarNetworkRetry.Status.Success<Int>
        assertEquals(42, success.value)
        assertEquals(2, success.attempts)
        assertEquals(listOf(5L), sleeps)
    }

    @Test
    fun http_429_rate_limited_is_retryable() {
        assertTrue(
            FindroidCarNetworkRetry.isRetryable(
                InvalidStatusException(429, RuntimeException("rate-limited"))
            )
        )
    }

    @Test
    fun http_408_timeout_is_retryable() {
        assertTrue(
            FindroidCarNetworkRetry.isRetryable(
                InvalidStatusException(408, RuntimeException("server timed out"))
            )
        )
    }

    @Test
    fun http_400_is_not_retryable() {
        assertFalse(
            FindroidCarNetworkRetry.isRetryable(
                InvalidStatusException(400, RuntimeException("bad request"))
            )
        )
    }

    @Test
    fun nested_invalid_status_in_cause_chain_is_unwrapped_for_status_check() = runBlocking {
        val inner = InvalidStatusException(401, RuntimeException("auth"))
        val wrapper = RuntimeException("api wrapper", inner)

        val sleeps = mutableListOf<Long>()
        var calls = 0
        val status =
            FindroidCarNetworkRetry.attempt(
                label = "wrapped",
                backoffMs = longArrayOf(10L, 30L),
                maxAttempts = 3,
                sleep = { sleeps += it },
            ) {
                calls++
                throw wrapper
            }

        val failure = status as FindroidCarNetworkRetry.Status.Failure
        assertTrue("401 wrapped in cause chain still terminal", failure.terminal)
        assertEquals(1, calls)
    }

    @Test
    fun friendly_message_maps_io_classes_to_russian_text() {
        assertEquals("Не могу найти сервер", FindroidCarNetworkRetry.friendlyMessage(UnknownHostException("dns")))
        assertEquals("Сервер не отвечает", FindroidCarNetworkRetry.friendlyMessage(SocketTimeoutException("t")))
        assertEquals("Нет соединения с сервером", FindroidCarNetworkRetry.friendlyMessage(IOException("io")))
        assertEquals(
            "Сервер вернул ошибку",
            FindroidCarNetworkRetry.friendlyMessage(InvalidStatusException(503, null)),
        )
        assertEquals(
            "Сессия истекла, нужно войти заново",
            FindroidCarNetworkRetry.friendlyMessage(InvalidStatusException(401, null)),
        )
        assertEquals(
            "Что-то пошло не так",
            FindroidCarNetworkRetry.friendlyMessage(IllegalStateException("oops")),
        )
    }

    @Test
    fun stale_cache_message_appends_kashe_marker() {
        val msg = FindroidCarNetworkRetry.staleCacheMessage(IOException("offline"))
        assertNotNull(msg)
        assertTrue(msg.endsWith("показываю кэш"))
    }
}
