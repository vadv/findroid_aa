package dev.jdtech.jellyfin.offline.transfer

import dev.jdtech.jellyfin.offline.download.OfflineDownloadFailureKind
import dev.jdtech.jellyfin.offline.download.OfflineTransferRequest
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineRangeDownloaderTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var downloader: OfflineRangeDownloader

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client =
            OkHttpClient.Builder()
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .connectTimeout(2, TimeUnit.SECONDS)
                .build()
        downloader = OfflineRangeDownloader(client) { _ -> false }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun resumes_from_offset_when_server_returns_206() = runBlocking {
        val payload = "0123456789ABCDEFGHIJ".toByteArray()
        val tempFile = tempFolder.newFile("video.part")
        tempFile.writeBytes(payload.copyOfRange(0, 7))
        val partial = payload.copyOfRange(7, payload.size)
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .addHeader("Content-Range", "bytes 7-${payload.size - 1}/${payload.size}")
                .body(Buffer().apply { write(partial) })
                .build()
        )

        val progress = mutableListOf<Long>()
        val outcome =
            downloader.download(
                tempFile = tempFile,
                packageId = "pkg",
                request = OfflineTransferRequest(url = server.url("/file").toString()),
                expectedBytes = payload.size.toLong(),
                onBytesTransferred = { progress += it },
            )

        assertEquals(RangeDownloadOutcome.Success, outcome)
        assertArrayEquals(payload, tempFile.readBytes())
        assertEquals(payload.size.toLong(), tempFile.length())
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("bytes=7-", request.headers["Range"])
        assertEquals(payload.size.toLong(), progress.last())
        assertTrue("Progress should report cumulative bytes >= offset", progress.all { it >= 7L })
    }

    @Test
    fun falls_back_to_full_download_when_server_returns_200_ignoring_range() = runBlocking {
        val payload = "0123456789ABCDEFGHIJ".toByteArray()
        val tempFile = tempFolder.newFile("video.part")
        tempFile.writeBytes(payload.copyOfRange(0, 7))
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Length", payload.size.toString())
                .body(Buffer().apply { write(payload) })
                .build()
        )

        val outcome =
            downloader.download(
                tempFile = tempFile,
                packageId = "pkg",
                request = OfflineTransferRequest(url = server.url("/file").toString()),
                expectedBytes = payload.size.toLong(),
            )

        assertEquals(RangeDownloadOutcome.Success, outcome)
        assertArrayEquals(payload, tempFile.readBytes())
        assertEquals(payload.size.toLong(), tempFile.length())
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("bytes=7-", request.headers["Range"])
    }

    @Test
    fun retries_without_range_when_server_returns_416() = runBlocking {
        val payload = "0123456789ABCDEFGHIJ".toByteArray()
        val tempFile = tempFolder.newFile("video.part")
        tempFile.writeBytes("garbage-padding-that-is-longer-than-server-file".toByteArray())
        server.enqueue(MockResponse.Builder().code(416).build())
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Length", payload.size.toString())
                .body(Buffer().apply { write(payload) })
                .build()
        )

        val outcome =
            downloader.download(
                tempFile = tempFile,
                packageId = "pkg",
                request = OfflineTransferRequest(url = server.url("/file").toString()),
                expectedBytes = payload.size.toLong(),
            )

        assertEquals(RangeDownloadOutcome.Success, outcome)
        assertArrayEquals(payload, tempFile.readBytes())
        assertEquals(payload.size.toLong(), tempFile.length())
        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertTrue(
            "First request should carry Range header",
            first.headers["Range"]?.startsWith("bytes=") == true,
        )
        assertNull(
            "Second (fallback) request must not carry Range header",
            second.headers["Range"],
        )
    }

    @Test
    fun retries_without_range_when_content_range_total_mismatches_expected() = runBlocking {
        val payload = "0123456789ABCDEFGHIJ".toByteArray()
        val tempFile = tempFolder.newFile("video.part")
        tempFile.writeBytes(payload.copyOfRange(0, 7))
        // Server says total=999 but client expects payload.size — must trigger fallback.
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .addHeader("Content-Range", "bytes 7-${payload.size - 1}/999")
                .body(Buffer().apply { write(payload.copyOfRange(7, payload.size)) })
                .build()
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Length", payload.size.toString())
                .body(Buffer().apply { write(payload) })
                .build()
        )

        val outcome =
            downloader.download(
                tempFile = tempFile,
                packageId = "pkg",
                request = OfflineTransferRequest(url = server.url("/file").toString()),
                expectedBytes = payload.size.toLong(),
            )

        assertEquals(RangeDownloadOutcome.Success, outcome)
        assertArrayEquals(payload, tempFile.readBytes())
        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("bytes=7-", first.headers["Range"])
        assertNull(second.headers["Range"])
    }

    @Test
    fun starts_from_zero_when_temp_file_is_empty_and_omits_range_header() = runBlocking {
        val payload = "0123456789ABCDEFGHIJ".toByteArray()
        val tempFile = tempFolder.newFile("video.part")
        // Empty file -> no Range header should be sent.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Length", payload.size.toString())
                .body(Buffer().apply { write(payload) })
                .build()
        )

        val outcome =
            downloader.download(
                tempFile = tempFile,
                packageId = "pkg",
                request = OfflineTransferRequest(url = server.url("/file").toString()),
                expectedBytes = payload.size.toLong(),
            )

        assertEquals(RangeDownloadOutcome.Success, outcome)
        assertArrayEquals(payload, tempFile.readBytes())
        val first: RecordedRequest = server.takeRequest()
        assertNull(first.headers["Range"])
    }

    @Test
    fun reports_failure_for_terminal_http_status() = runBlocking {
        val tempFile = tempFolder.newFile("video.part")
        server.enqueue(MockResponse.Builder().code(404).body("not found").build())

        val outcome =
            downloader.download(
                tempFile = tempFile,
                packageId = "pkg",
                request = OfflineTransferRequest(url = server.url("/file").toString()),
                expectedBytes = 99L,
            )

        val failure = (outcome as RangeDownloadOutcome.Failure).failure
        assertEquals(OfflineDownloadFailureKind.SourceMissingOrChanged, failure.kind)
    }

    @Test
    fun integrity_check_fails_when_bytes_do_not_match_expected() = runBlocking {
        val tempFile = tempFolder.newFile("video.part")
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Length", "5")
                .body(Buffer().apply { write("hello".toByteArray()) })
                .build()
        )

        val outcome =
            downloader.download(
                tempFile = tempFile,
                packageId = "pkg",
                request = OfflineTransferRequest(url = server.url("/file").toString()),
                expectedBytes = 99L,
            )

        val failure = (outcome as RangeDownloadOutcome.Failure).failure
        assertEquals(OfflineDownloadFailureKind.IntegrityFailed, failure.kind)
    }

    @Test
    fun reads_existing_tail_bytes_correctly_when_resuming() = runBlocking {
        val payload = ByteArray(4096) { it.toByte() }
        val tempFile = tempFolder.newFile("video.part")
        val resumeAt = 1024
        tempFile.writeBytes(payload.copyOfRange(0, resumeAt))
        val partial = payload.copyOfRange(resumeAt, payload.size)
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .addHeader("Content-Range", "bytes $resumeAt-${payload.size - 1}/${payload.size}")
                .body(Buffer().apply { write(partial) })
                .build()
        )

        val outcome =
            downloader.download(
                tempFile = tempFile,
                packageId = "pkg",
                request = OfflineTransferRequest(url = server.url("/file").toString()),
                expectedBytes = payload.size.toLong(),
            )

        assertEquals(RangeDownloadOutcome.Success, outcome)
        assertArrayEquals(payload, tempFile.readBytes())
        assertSameContent(tempFile, payload)
    }

    private fun assertSameContent(file: File, expected: ByteArray) {
        val actual = file.readBytes()
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                throw AssertionError("Byte mismatch at index $i: expected ${expected[i]}, was ${actual[i]}")
            }
        }
    }
}
