package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Item 1: Races the top matching candidate streams in parallel using a lightweight HTTP Range (bytes=0-1) probe
 * and returns whichever candidate responds with HTTP 200/206 first.
 */
object FastStreamRacer {
    suspend fun raceTopCandidates(
        candidates: List<Stream>,
        maxConcurrent: Int = 3,
        timeoutMs: Long = 2_200L
    ): Stream? {
        if (candidates.isEmpty()) return null
        val directHttpCandidates = candidates
            .filter { !it.getStreamUrl().isNullOrBlank() && it.getStreamUrl()!!.startsWith("http", ignoreCase = true) }
            .take(maxConcurrent)

        if (directHttpCandidates.size <= 1) {
            return candidates.firstOrNull()
        }

        val winner = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val result = CompletableDeferred<Stream>()
                val jobs = directHttpCandidates.map { stream ->
                    launch(Dispatchers.IO) {
                        val url = stream.getStreamUrl() ?: return@launch
                        if (probeUrlAlive(url, timeoutMs.toInt())) {
                            result.complete(stream)
                        }
                    }
                }
                val won = runCatching { result.await() }.getOrNull()
                jobs.forEach { it.cancel() }
                won
            }
        }
        return winner ?: candidates.firstOrNull()
    }

    private suspend fun probeUrlAlive(urlStr: String, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val connObj = (URL(urlStr).openConnection() as? HttpURLConnection) ?: return@withContext false
            conn = connObj
            connObj.requestMethod = "GET"
            connObj.instanceFollowRedirects = true
            connObj.connectTimeout = timeoutMs.coerceAtMost(1_800)
            connObj.readTimeout = timeoutMs.coerceAtMost(1_800)
            connObj.setRequestProperty("Range", "bytes=0-1")
            val code = connObj.responseCode
            code in 200..299
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
