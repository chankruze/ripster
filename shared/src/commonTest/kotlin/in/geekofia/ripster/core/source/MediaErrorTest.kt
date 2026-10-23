package `in`.geekofia.ripster.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaErrorTest {
    @Test
    fun httpStatusCategories() {
        assertEquals(ErrorCategory.Unauthorized, MediaError.HttpStatus(401).category)
        assertEquals(ErrorCategory.Unauthorized, MediaError.HttpStatus(403).category)
        assertEquals(ErrorCategory.Expired, MediaError.HttpStatus(403, signedUrl = true).category)
        assertEquals(ErrorCategory.Expired, MediaError.HttpStatus(410).category)
        assertEquals(ErrorCategory.Unavailable, MediaError.HttpStatus(404).category)
        assertEquals(ErrorCategory.Unavailable, MediaError.HttpStatus(503).category)
    }

    @Test
    fun retryIsOfferedOnlyWhenItCanHelp() {
        assertFalse(MediaError.InvalidUrl(UrlProblem.Empty).isRetryable)
        assertFalse(MediaError.WebPage.isRetryable)
        assertFalse(MediaError.DrmProtected.isRetryable)
        assertFalse(MediaError.LiveStream.isRetryable)
        assertFalse(MediaError.ExportUnsupported("ac-3").isRetryable)
        assertTrue(MediaError.Network(NetworkProblem.Offline).isRetryable)
        assertTrue(MediaError.HttpStatus(503).isRetryable)
        assertTrue(MediaError.HttpStatus(403, signedUrl = true).isRetryable, "retry re-inspects, so a fresh link can work")
        assertTrue(MediaError.InsufficientStorage(null, null).isRetryable)
        assertTrue(MediaError.ExportFailed("io").isRetryable)
    }

    @Test
    fun everyErrorHasAMessage() {
        val errors = UrlProblem.entries.map(MediaError::InvalidUrl) +
            NetworkProblem.entries.map(MediaError::Network) +
            listOf(401, 403, 404, 410, 429, 500, 418).map { MediaError.HttpStatus(it) } +
            listOf(
                MediaError.WebPage, MediaError.NotHls(null), MediaError.LiveStream, MediaError.DrmProtected,
                MediaError.SampleAesEncrypted, MediaError.Parse("x"), MediaError.InsufficientStorage(2_000_000_000, 1_048_576),
                MediaError.OutputUnavailable("x"), MediaError.ExportUnsupported("x"), MediaError.ExportFailed("x"),
                MediaError.Unknown("x"),
            )
        errors.forEach { error ->
            val message = error.message()
            assertTrue(message.title.isNotBlank() && message.detail.isNotBlank(), "$error")
        }
        assertEquals("Needs about 1.9 GB, but only 1.0 MB is free.", MediaError.InsufficientStorage(2_000_000_000, 1_048_576).message().detail)
    }

    @Test
    fun formatsBytes() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("12 KB", formatBytes(12 * 1024 + 100))
        assertEquals("340.0 MB", formatBytes(340L * 1024 * 1024))
        assertEquals("1.5 GB", formatBytes(1536L * 1024 * 1024))
    }
}
