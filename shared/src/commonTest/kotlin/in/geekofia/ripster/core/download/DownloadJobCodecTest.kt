package `in`.geekofia.ripster.core.download

import `in`.geekofia.ripster.core.source.FileContainer
import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.NetworkProblem
import `in`.geekofia.ripster.core.source.UrlProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadJobCodecTest {
    private fun roundTrip(jobs: List<DownloadJob>) = DownloadJobCodec.decode(DownloadJobCodec.encode(jobs))

    @Test
    fun roundTripsEveryField() {
        val completed = testJob(id = "a", state = DownloadState.Completed).copy(
            progress = 1f,
            bytesDownloaded = 123_456,
            output = testOutput,
            attempt = 3,
            isSample = true,
        )
        val failed = testJob(id = "b", state = DownloadState.Failed, variantUrl = null)
            .copy(error = MediaError.HttpStatus(403, signedUrl = true), durationSeconds = null)
        assertEquals(listOf(completed, failed), roundTrip(listOf(completed, failed)))
    }

    @Test
    fun roundTripsDirectFileJobs() {
        val mkv = testJob(id = "f", state = DownloadState.Completed, variantUrl = null).copy(
            file = FileContainer.Matroska,
            output = testOutput.copy(displayName = "clip.mkv", mimeType = "video/x-matroska"),
        )
        assertEquals(listOf(mkv), roundTrip(listOf(mkv)))
    }

    @Test
    fun readsQueuesSavedBeforeDirectFiles() {
        // A version 1 record: 23 fields, no file container or output MIME type.
        val job = testJob(id = "old", state = DownloadState.Completed).copy(output = testOutput)
        val v2 = DownloadJobCodec.encode(listOf(job))
        val v1 = "q1" + v2.removePrefix("q2").split('\u001F').dropLast(2).joinToString("\u001F")
        val decoded = DownloadJobCodec.decode(v1).single()
        assertEquals(null, decoded.file)
        assertEquals("video/mp4", decoded.output?.mimeType)
        assertEquals(job, decoded)
    }

    @Test
    fun survivesAwkwardText() {
        val awkward = testJob().copy(
            title = "Back\\slash \u001E record \u001F field, \"quotes\" & ünïcødé 🎬",
            sourceUrl = "https://cdn.example/a.m3u8?x=\\r\\f&y=1",
        )
        assertEquals(listOf(awkward), roundTrip(listOf(awkward)))
    }

    @Test
    fun roundTripsEveryErrorType() {
        val errors = listOf(
            MediaError.InvalidUrl(UrlProblem.Insecure),
            MediaError.WebPage,
            MediaError.NoMediaOnPage,
            MediaError.UnsupportedPageMedia,
            MediaError.NotHls("text/plain"),
            MediaError.NotHls(null),
            MediaError.LiveStream,
            MediaError.DrmProtected,
            MediaError.SampleAesEncrypted,
            MediaError.HttpStatus(404),
            MediaError.Network(NetworkProblem.Timeout),
            MediaError.Parse("bad line"),
            MediaError.InsufficientStorage(10, 5),
            MediaError.InsufficientStorage(null, null),
            MediaError.OutputUnavailable("gone"),
            MediaError.ExportUnsupported("ac-3"),
            MediaError.ExportFailed("io"),
            MediaError.Unknown("?"),
        )
        errors.forEach { error ->
            assertEquals(error, DownloadJobCodec.decodeError(DownloadJobCodec.encodeError(error)))
        }
    }

    @Test
    fun unknownErrorsDecodeAsUnknownInsteadOfDroppingTheJob() {
        assertTrue(DownloadJobCodec.decodeError("from-the-future") is MediaError.Unknown)
        assertTrue(DownloadJobCodec.decodeError("http\u001Dnot-a-number") is MediaError.Unknown)
    }

    @Test
    fun corruptDataLosesOnlyTheBrokenJob() {
        val good = testJob(id = "good")
        val encoded = DownloadJobCodec.encode(listOf(good)) + "\u001Ebroken\u001Frecord"
        assertEquals(listOf(good), DownloadJobCodec.decode(encoded))
        assertEquals(emptyList(), DownloadJobCodec.decode(null))
        assertEquals(emptyList(), DownloadJobCodec.decode("garbage"))
        assertEquals(emptyList(), DownloadJobCodec.decode("q0\u001Eold-version"))
    }

    @Test
    fun anUnknownStateDropsTheJob() {
        val encoded = DownloadJobCodec.encode(listOf(testJob())).replace("Queued", "Teleporting")
        assertEquals(emptyList(), DownloadJobCodec.decode(encoded))
    }
}
