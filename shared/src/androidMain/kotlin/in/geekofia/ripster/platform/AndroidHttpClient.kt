package `in`.geekofia.ripster.platform

import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.MediaException
import `in`.geekofia.ripster.core.source.NetworkProblem
import `in`.geekofia.ripster.core.source.SourceUrl
import `in`.geekofia.ripster.core.source.UrlCheck
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible

/**
 * Manifest fetching on `HttpURLConnection` (no extra dependency). Redirects are followed by hand
 * so every hop is re-validated: https only, no credentials, at most [MAX_REDIRECTS]. TLS uses the
 * platform defaults; certificate checks are never relaxed. Nothing here logs URLs or headers.
 */
internal class AndroidHttpClient(private val ioDispatcher: CoroutineDispatcher) : HttpClient {

    override suspend fun get(url: String, maxBytes: Int): HttpResponse = runInterruptible(ioDispatcher) {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val connection = open(current)
            try {
                val code = connection.responseCode
                if (code in 300..399 && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                    val location = connection.getHeaderField("Location")
                        ?: return@runInterruptible HttpResponse(current, code, null, ByteArray(0))
                    current = when (val next = SourceUrl.check(SourceUrl.resolve(current, location))) {
                        is UrlCheck.Valid -> next.url
                        is UrlCheck.Invalid -> throw MediaException(MediaError.Network(NetworkProblem.InsecureRedirect))
                    }
                    return@repeat
                }
                val body = if (code in 200..299) connection.inputStream.use { it.readLimited(maxBytes) } else ByteArray(0)
                return@runInterruptible HttpResponse(current, code, connection.contentType, body)
            } catch (e: IOException) {
                throw MediaException(MediaError.Network(e.toNetworkProblem()), e)
            } finally {
                connection.disconnect()
            }
        }
        throw MediaException(MediaError.Network(NetworkProblem.TooManyRedirects))
    }

    private fun open(url: String): HttpsURLConnection {
        val connection = try {
            URL(url).openConnection()
        } catch (e: IOException) {
            throw MediaException(MediaError.Network(e.toNetworkProblem()), e)
        }
        // SourceUrl.check already requires https; this guards against a non-TLS handler anyway.
        if (connection !is HttpsURLConnection) throw MediaException(MediaError.Network(NetworkProblem.InsecureRedirect))
        return connection.apply {
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.apple.mpegurl, application/x-mpegurl, */*;q=0.5")
        }
    }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > maxBytes) throw MediaException(MediaError.Network(NetworkProblem.ResponseTooLarge))
            out.write(buffer, 0, read)
        }
    }

    companion object {
        const val MAX_REDIRECTS = 5
        const val TIMEOUT_MS = 15_000
        const val USER_AGENT = "Ripster/1.0 (Android)"
    }
}

internal fun Throwable.toNetworkProblem(): NetworkProblem = when (this) {
    is UnknownHostException, is ConnectException, is NoRouteToHostException -> NetworkProblem.Offline
    is SocketTimeoutException -> NetworkProblem.Timeout
    is SSLException -> NetworkProblem.Tls
    else -> NetworkProblem.Other
}
