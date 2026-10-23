package `in`.geekofia.ripster.core.download

import `in`.geekofia.ripster.core.source.FileContainer
import `in`.geekofia.ripster.core.source.MediaError
import `in`.geekofia.ripster.core.source.NetworkProblem
import `in`.geekofia.ripster.core.source.SiteOutcome
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
    fun roundTripsAThumbnailUrl() {
        val job = testJob(id = "t").copy(thumbnailUrl = "https://cdn.example/thumb.jpg?token=abc")
        assertEquals(listOf(job), roundTrip(listOf(job)))
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
    fun readsOlderQueueVersions() {
        // Re-label the current record with fewer fields, as each older version stored them:
        // q2 dropped the thumbnail (last field); q1 also dropped the file container and output MIME.
        val job = testJob(id = "old", state = DownloadState.Completed).copy(output = testOutput)
        val (_, record) = DownloadJobCodec.encode(listOf(job)).split('\u001E')
        val fields = record.split('\u001F')

        fun older(version: String, keep: Int) =
            version + "\u001E" + fields.take(keep).joinToString("\u001F")

        val fromV2 = DownloadJobCodec.decode(older("q2", 25)).single()
        assertEquals(null, fromV2.thumbnailUrl)
        assertEquals(job, fromV2)

        val fromV1 = DownloadJobCodec.decode(older("q1", 23)).single()
        assertEquals(null, fromV1.file)
        assertEquals(null, fromV1.thumbnailUrl)
        assertEquals("video/mp4", fromV1.output?.mimeType)
        assertEquals(job, fromV1)

        assertEquals(emptyList(), DownloadJobCodec.decode(older("q9", 26)), "an unknown version is ignored")
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
            MediaError.Site("Pornhub", SiteOutcome.MetadataOnly, "No openly provided source, \"quoted\"."),
            MediaError.Site("Pornhub", SiteOutcome.Restricted, ""),
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
            MediaError.DownloadedDataMissing,
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
