package dev.jdtech.jellyfin.offline.transfer

import dev.jdtech.jellyfin.offline.download.OfflineDownloadFailure
import dev.jdtech.jellyfin.offline.download.OfflineDownloadFailureKind
import dev.jdtech.jellyfin.offline.download.OfflineTransferRequest
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import timber.log.Timber

internal sealed interface RangeDownloadOutcome {
    data object Success : RangeDownloadOutcome

    data class Failure(val failure: OfflineDownloadFailure) : RangeDownloadOutcome
}

/**
 * Streams an HTTP body into a temp file, resuming via `Range: bytes=N-` when the file already
 * contains partial data. Handles three server replies:
 *  - 206 Partial Content: append from the existing offset (after validating `Content-Range`).
 *  - 200 OK: server ignored the Range header, truncate temp and write from byte 0.
 *  - 416 Range Not Satisfiable: truncate temp and re-fetch without `Range`.
 *
 * The integrity probe is invoked only when neither `expectedBytes` nor the response's
 * `Content-Length` / `Content-Range` total is available. In production the probe checks MP4
 * readability via `MediaMetadataRetriever`; tests can inject a fixed value.
 */
internal class OfflineRangeDownloader(
    private val okHttpClient: OkHttpClient,
    private val mediaIntegrityProbe: (File) -> Boolean = { false },
) {
    suspend fun download(
        tempFile: File,
        packageId: String,
        request: OfflineTransferRequest,
        expectedBytes: Long?,
        onBytesTransferred: suspend (Long) -> Unit = {},
    ): RangeDownloadOutcome {
        var startOffset = existingTempBytes(tempFile)
        repeat(MAX_RANGE_FALLBACK_ATTEMPTS) {
            val outcome =
                singleAttempt(
                    tempFile = tempFile,
                    request = request,
                    expectedBytes = expectedBytes,
                    startOffset = startOffset,
                    onBytesTransferred = onBytesTransferred,
                )
            when (outcome) {
                AttemptOutcome.Completed -> return RangeDownloadOutcome.Success
                is AttemptOutcome.Failure -> return RangeDownloadOutcome.Failure(outcome.failure)
                is AttemptOutcome.FullReset -> {
                    Timber.i(
                        "Range fallback for %s: %s. Re-downloading from byte 0.",
                        packageId,
                        outcome.reason,
                    )
                    truncateTempFile(tempFile)
                    startOffset = 0L
                }
            }
        }
        return RangeDownloadOutcome.Failure(
            OfflineDownloadFailure(
                OfflineDownloadFailureKind.StreamInterrupted,
                "Range fallback retries exhausted",
            )
        )
    }

    private suspend fun singleAttempt(
        tempFile: File,
        request: OfflineTransferRequest,
        expectedBytes: Long?,
        startOffset: Long,
        onBytesTransferred: suspend (Long) -> Unit,
    ): AttemptOutcome {
        val httpRequest = buildHttpRequest(request, startOffset)
        val call = okHttpClient.newCall(httpRequest)
        val cancellationHandle =
            currentCoroutineContext().job.invokeOnCompletion {
                if (it is CancellationException) call.cancel()
            }
        return try {
            call.execute().use { response ->
                evaluateResponse(
                    response = response,
                    tempFile = tempFile,
                    expectedBytes = expectedBytes,
                    startOffset = startOffset,
                    onBytesTransferred = onBytesTransferred,
                )
            }
        } finally {
            cancellationHandle.dispose()
        }
    }

    private suspend fun evaluateResponse(
        response: Response,
        tempFile: File,
        expectedBytes: Long?,
        startOffset: Long,
        onBytesTransferred: suspend (Long) -> Unit,
    ): AttemptOutcome =
        when (response.code) {
            HTTP_PARTIAL_CONTENT -> {
                val contentRange = ContentRange.parse(response.header("Content-Range"))
                when {
                    contentRange == null ->
                        AttemptOutcome.FullReset(
                            "HTTP 206 without parseable Content-Range header"
                        )
                    contentRange.start != startOffset ->
                        AttemptOutcome.FullReset(
                            "Content-Range start ${contentRange.start} != requested offset $startOffset"
                        )
                    expectedBytes != null &&
                        contentRange.total != null &&
                        contentRange.total != expectedBytes ->
                        AttemptOutcome.FullReset(
                            "Content-Range total ${contentRange.total} != expected $expectedBytes"
                        )
                    else -> {
                        val expectedTotal = expectedBytes ?: contentRange.total
                        writeAndVerify(
                            tempFile = tempFile,
                            body = response.body,
                            startOffset = startOffset,
                            expectedTotal = expectedTotal,
                            onBytesTransferred = onBytesTransferred,
                        )
                    }
                }
            }
            HTTP_OK -> {
                if (startOffset > 0L) {
                    Timber.i(
                        "Server returned HTTP 200 to Range request for %s — Range not supported, truncating temp and downloading from byte 0.",
                        tempFile.name,
                    )
                    truncateTempFile(tempFile)
                }
                val declared = response.body.contentLength().takeIf { it >= 0L }
                val expectedTotal = expectedBytes ?: declared
                writeAndVerify(
                    tempFile = tempFile,
                    body = response.body,
                    startOffset = 0L,
                    expectedTotal = expectedTotal,
                    onBytesTransferred = onBytesTransferred,
                )
            }
            HTTP_RANGE_NOT_SATISFIABLE ->
                AttemptOutcome.FullReset("HTTP 416 Range Not Satisfiable")
            else ->
                if (response.isSuccessful) {
                    AttemptOutcome.Failure(
                        OfflineDownloadFailure(
                            OfflineDownloadFailureKind.StreamInterrupted,
                            "Unexpected HTTP ${response.code}",
                        )
                    )
                } else {
                    AttemptOutcome.Failure(
                        OfflineDownloadFailure(
                            OfflineTransferFailureMapper.fromHttpStatus(response.code),
                            "HTTP ${response.code}",
                        )
                    )
                }
        }

    private suspend fun writeAndVerify(
        tempFile: File,
        body: ResponseBody,
        startOffset: Long,
        expectedTotal: Long?,
        onBytesTransferred: suspend (Long) -> Unit,
    ): AttemptOutcome {
        val deltaWritten =
            writeResponseBody(
                tempFile = tempFile,
                body = body,
                startOffset = startOffset,
                onBytesTransferred = onBytesTransferred,
            )
        val totalOnDisk = startOffset + deltaWritten
        val integrityVerified =
            if (expectedTotal != null) {
                totalOnDisk == expectedTotal
            } else {
                mediaIntegrityProbe(tempFile)
            }
        if (totalOnDisk == 0L || !integrityVerified) {
            return AttemptOutcome.Failure(
                OfflineDownloadFailure(OfflineDownloadFailureKind.IntegrityFailed)
            )
        }
        return AttemptOutcome.Completed
    }

    private suspend fun writeResponseBody(
        tempFile: File,
        body: ResponseBody,
        startOffset: Long,
        onBytesTransferred: suspend (Long) -> Unit,
    ): Long {
        val parent = tempFile.parentFile
        if (parent != null && !parent.isDirectory) {
            try {
                parent.mkdirs()
            } catch (e: SecurityException) {
                throw TransferStorageIOException(IOException("Failed to create $parent", e))
            }
        }
        return RandomAccessFile(tempFile, "rw").use { raf ->
            try {
                raf.setLength(startOffset)
                raf.seek(startOffset)
            } catch (e: IOException) {
                throw TransferStorageIOException(e)
            }
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var delta = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read =
                        try {
                            input.read(buffer)
                        } catch (e: IOException) {
                            throw TransferNetworkIOException(e)
                        }
                    if (read == -1) break
                    try {
                        raf.write(buffer, 0, read)
                    } catch (e: IOException) {
                        throw TransferStorageIOException(e)
                    }
                    delta += read
                    onBytesTransferred(startOffset + delta)
                }
                try {
                    raf.fd.sync()
                } catch (e: IOException) {
                    throw TransferStorageIOException(e)
                }
                delta
            }
        }
    }

    private fun buildHttpRequest(
        request: OfflineTransferRequest,
        startOffset: Long,
    ): Request {
        val builder =
            Request.Builder().url(request.url).headers(request.headers.toOkHttpHeaders())
        if (startOffset > 0L) {
            builder.addHeader("Range", "bytes=$startOffset-")
        }
        return builder.build()
    }

    private fun existingTempBytes(tempFile: File): Long =
        if (tempFile.isFile) tempFile.length().coerceAtLeast(0L) else 0L

    private fun truncateTempFile(tempFile: File) {
        if (!tempFile.exists()) return
        try {
            RandomAccessFile(tempFile, "rw").use { it.setLength(0L) }
        } catch (e: IOException) {
            runCatching { tempFile.delete() }
            Timber.w(e, "Failed to truncate %s before Range fallback; deleted instead", tempFile)
        }
    }

    private fun Map<String, String>.toOkHttpHeaders(): Headers =
        Headers.Builder().also { builder -> forEach { (name, value) -> builder.add(name, value) } }.build()

    private sealed interface AttemptOutcome {
        data object Completed : AttemptOutcome

        data class FullReset(val reason: String) : AttemptOutcome

        data class Failure(val failure: OfflineDownloadFailure) : AttemptOutcome
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 256 * 1024
        const val HTTP_OK = 200
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416

        // One Range attempt + at most one full-from-zero fallback. Beyond that we
        // surface the failure so WorkManager's retry policy takes over.
        const val MAX_RANGE_FALLBACK_ATTEMPTS = 2
    }
}

internal data class ContentRange(
    val start: Long,
    val end: Long,
    val total: Long?,
) {
    companion object {
        private val PATTERN = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)

        fun parse(headerValue: String?): ContentRange? {
            val match = PATTERN.matchEntire(headerValue?.trim() ?: return null) ?: return null
            val total = match.groupValues[3].takeUnless { it == "*" }?.toLong()
            return ContentRange(
                start = match.groupValues[1].toLong(),
                end = match.groupValues[2].toLong(),
                total = total,
            )
        }
    }
}

internal class TransferNetworkIOException(cause: IOException) : IOException(cause.message, cause)

internal class TransferStorageIOException(cause: IOException) : IOException(cause.message, cause)
