package `in`.geekofia.ripster.core.source

/** A public HLS test stream for development. Availability isn't guaranteed; nothing about it is assumed. */
data class SampleSource(
    val title: String,
    val url: String,
    /** What the sample exercises. */
    val purpose: String,
)

/**
 * The only place sample URLs are defined. Shown in debug builds only, and always labelled as
 * samples. They're public test streams: their availability, contents and formats may change, and
 * they grant no redistribution rights.
 */
object SampleSources {
    val BigBuckBunny = SampleSource(
        title = "Big Buck Bunny",
        url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        purpose = "Basic end-to-end download and MP4 export",
    )

    val AppleBipBop = SampleSource(
        title = "Apple BipBop (fMP4)",
        url = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
        purpose = "Adaptive master playlist, fragmented MP4, separate audio",
    )

    val TearsOfSteel = SampleSource(
        title = "Tears of Steel",
        url = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
        purpose = "Longer download: progress, cancellation, recovery",
    )

    val all: List<SampleSource> = listOf(BigBuckBunny, AppleBipBop, TearsOfSteel)

    fun find(url: String): SampleSource? = all.firstOrNull { it.url == url.trim() }
}
