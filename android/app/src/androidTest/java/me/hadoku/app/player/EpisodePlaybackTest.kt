package me.hadoku.app.player

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import me.hadoku.app.Device
import me.hadoku.app.Fixture
import me.hadoku.app.library.Downloads
import me.hadoku.app.library.Episode
import me.hadoku.app.library.EpisodeInfo
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player spike's go/no-go, as a test: watchparty's MSE-flavoured fMP4 plus
 * per-language audio files play as-is, both languages switch, subtitles render,
 * seek lands, and playback keeps pace with the wall clock without A/V drift.
 *
 * Drift shows in the video renderer: Media3 slaves video to the audio clock
 * and drops frames that fall behind it, so once video lags, EVERY frame is
 * late and drops come in long consecutive runs. A CPU-starved emulator (it
 * decodes in software, and hokon doubles as the CI backstop) drops scattered
 * single frames instead. So the drift gate is the longest consecutive run;
 * the total only has to stay low enough that the picture is still watchable.
 *
 * By default it plays the synthetic fixture bundled in the test APK. With
 * `-e source <dir>` it plays a REAL episode instead (`pnpm test:player-real`):
 * [dir] is on the device, readable by the shell, and holds the episode's
 * files with its index.json and tracks.json. The test copies it into the
 * Library itself and records it with the same parser a download uses, because
 * the app cannot read files the shell pushed into its dir, and because the
 * record is where the player gets the episode's length. `-e playSeconds <n>`
 * sets how long the drift check runs.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class EpisodePlaybackTest {
    private val args = InstrumentationRegistry.getArguments()
    private val source = args.getString("source")
    private val mediaId = source?.let { "real/${it.trimEnd('/').substringAfterLast('/')}" } ?: Fixture.ID
    /** index.json's duration for a real episode; null for the fixture, which has no index. */
    private var recordedDurationMs: Long? = null
    private val playSeconds = args.getString("playSeconds")?.toLong() ?: 8
    private lateinit var files: EpisodeFiles
    private lateinit var scenario: ActivityScenario<PlayerActivity>
    private var error: PlaybackException? = null

    @Before
    fun launch() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = PlayerActivity.episodeDir(target, mediaId)
        if (source == null) Fixture.copyTo(dir) else recordedDurationMs = importEpisode(source, dir)
        files = EpisodeFiles.from(dir)
        scenario = ActivityScenario.launch(PlayerActivity.intent(target, mediaId))
        Device.waitUntil("the service playing $mediaId", 10_000) {
            var current: String? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync { current = PlaybackService.player?.currentMediaItem?.mediaId }
            current == mediaId
        }
        onPlayer {
            it.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) { error = e }
            })
        }
        waitFor("ready") { it.playbackState == Player.STATE_READY }
    }

    @After
    fun close() {
        scenario.close()
        // A real episode is hundreds of MB, copied in per test.
        if (source != null) Downloads.store(InstrumentationRegistry.getInstrumentation().targetContext).delete(mediaId)
    }

    /** Copies [from] (a shell-readable dir on the device) into [dir] as the app, and records it as a finished download. */
    private fun importEpisode(from: String, dir: File): Long {
        val root = from.trimEnd('/')
        dir.deleteRecursively()
        val paths = Device.shell("find $root -type f").lines().filter { it.isNotBlank() }
        require(paths.isNotEmpty()) { "nothing under $root on the device" }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (path in paths) {
            val to = File(dir, path.removePrefix("$root/")).also { it.parentFile!!.mkdirs() }
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cat $path"))
                .use { input -> to.outputStream().use { input.copyTo(it) } }
        }
        val info = EpisodeInfo.parse(mediaId, File(dir, "index.json").readText(), File(dir, "tracks.json").readText())
        val wanted = listOf(info.video) + info.audio.map { it.file } + info.subtitles.map { it.file }
        Downloads.store(InstrumentationRegistry.getInstrumentation().targetContext).put(
            Episode(
                mediaId = mediaId,
                title = mediaId.substringAfterLast('/'),
                series = "real",
                token = info.token,
                durationMs = info.durationMs,
                addedAt = System.currentTimeMillis(),
                files = wanted.map {
                    val file = File(dir, it.path)
                    require(file.isFile) { "index.json names ${it.path}, which was not pushed" }
                    Episode.FileState(it.path, file.length(), downloadId = -1, done = true)
                },
            )
        )
        require(info.durationMs > 0) { "index.json states no duration_ms" }
        return info.durationMs
    }

    @Test
    fun exposesExactlyTheEpisodesTracks() {
        val groups = onPlayer { it.currentTracks.groups.map { g -> g.type to (g.getTrackFormat(0).language ?: "und") } }
        Log.i(TAG, "tracks $groups")
        assertEquals("video groups in $groups", 1, groups.count { it.first == C.TRACK_TYPE_VIDEO })
        assertEquals(
            "audio languages",
            files.audio.map { Util.normalizeLanguageCode(it.language)!! }.toSet(),
            groups.filter { it.first == C.TRACK_TYPE_AUDIO }.map { it.second }.toSet(),
        )
        // Exactly the side-loaded VTTs: a text group from anywhere else (the
        // chapter track inside each audio file, say) would be a phantom entry
        // in the subtitle menu.
        assertEquals(
            "text languages",
            files.subtitles.map { Util.normalizeLanguageCode(it.language)!! }.sorted(),
            groups.filter { it.first == C.TRACK_TYPE_TEXT }.map { it.second }.sorted(),
        )
    }

    @Test
    fun switchesBetweenEveryAudioLanguage() {
        for (lang in files.audio.map { Util.normalizeLanguageCode(it.language)!! }) {
            onPlayer {
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(lang).build()
            }
            waitFor("audio $lang selected") { selectedAudioLanguage(it) == lang }
            val before = onPlayer { it.currentPosition }
            waitFor("playback advancing on $lang") { it.currentPosition > before + 1_000 }
        }
    }

    @Test
    fun rendersEverySubtitleLanguage() {
        for (sub in files.subtitles) {
            val lang = Util.normalizeLanguageCode(sub.language)!!
            val cueAt = firstCueMs(sub.file)
            onPlayer {
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                    .setPreferredTextLanguage(lang).build()
                it.seekTo(cueAt + 500)
            }
            waitFor("a $lang cue on screen") { selectedTextLanguage(it) == lang && it.currentCues.cues.isNotEmpty() }
            Log.i(TAG, "cue $lang at ${cueAt}ms: ${onPlayer { it.currentCues.cues.first().text }}")
            screenshot("subtitle-$lang")
        }
    }

    @Test
    fun seeksAndKeepsPaceWithoutDroppingFrames() {
        // Not a given: the video file states no length. A real episode's comes from
        // its record, at once; the 20-second fixture's is measured once it is read whole.
        waitFor("the episode's length") { it.duration != C.TIME_UNSET }
        val duration = onPlayer { it.duration }
        recordedDurationMs?.let { assertEquals("the length index.json records", it, duration) }
        val target = duration * 6 / 10
        onPlayer { it.seekTo(target) }
        // currentPosition reads the seek target straight away (ExoPlayer masks
        // it), so "landed" means playing again from there, not a reading of it.
        waitFor("playing after seek") { it.isPlaying && it.currentPosition > target + 200 }
        val landed = onPlayer { it.currentPosition }
        assertTrue("seek to $target resumed at $landed", landed - target < 2_500)

        // Why the clock ran slow, when it does: waiting on the file (rebuffers)
        // points at the source; audio underruns with no rebuffer point at a
        // starved device, which is what a loaded host does to the emulator.
        var rebuffers = 0
        var underruns = 0
        val watch = object : AnalyticsListener {
            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                if (state == Player.STATE_BUFFERING) rebuffers++
            }

            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                underruns++
            }
        }
        onPlayer { it.addAnalyticsListener(watch) }
        val (pos0, wall0) = onPlayer { it.currentPosition } to SystemClock.elapsedRealtime()
        val seconds = minOf(playSeconds, (duration - pos0) / 1_000 - 2)
        SystemClock.sleep(seconds * 1_000)
        val (pos1, wall1) = onPlayer { it.currentPosition } to SystemClock.elapsedRealtime()
        onPlayer { it.removeAnalyticsListener(watch) }
        val counters = onPlayer { it.videoDecoderCounters!!.also { c -> c.ensureUpdated() } }
        val rendered = counters.renderedOutputBufferCount
        val dropped = counters.droppedBufferCount
        Log.i(TAG, "played ${pos1 - pos0} ms in ${wall1 - wall0} ms; video rendered=$rendered dropped=$dropped maxConsecutiveDropped=${counters.maxConsecutiveDroppedBufferCount}; rebuffers=$rebuffers audioUnderruns=$underruns")

        assertTrue("media clock ran ${pos1 - pos0} ms in ${wall1 - wall0} ms ($rebuffers rebuffers, $underruns audio underruns)", abs((pos1 - pos0) - (wall1 - wall0)) < 750)
        val run = counters.maxConsecutiveDroppedBufferCount
        assertTrue("$run consecutive video frames dropped: video fell behind the audio clock", run <= MAX_CONSECUTIVE_DROPS)
        assertTrue("dropped $dropped of ${rendered + dropped} video frames", dropped * 5 <= rendered + dropped)
    }

    private fun selectedAudioLanguage(p: ExoPlayer) = selectedLanguage(p, C.TRACK_TYPE_AUDIO)

    private fun selectedTextLanguage(p: ExoPlayer) = selectedLanguage(p, C.TRACK_TYPE_TEXT)

    private fun selectedLanguage(p: ExoPlayer, type: Int): String? =
        p.currentTracks.groups.firstOrNull { it.type == type && it.isSelected }?.getTrackFormat(0)?.language

    /**
     * Evidence for what only a human can judge (subtitle placement, say):
     * `adb pull /sdcard/Android/data/me.hadoku.app/files/test-screenshots`.
     */
    private fun screenshot(name: String) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(target.getExternalFilesDir("test-screenshots"), mediaId).apply { mkdirs() }
        SystemClock.sleep(300)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: throw AssertionError("screenshot $name failed")
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The service's own ExoPlayer, not the activity's controller: decoder counters and exact positions. */
    private fun <T> onPlayer(block: (ExoPlayer) -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching { block(PlaybackService.player!!) } }
        return result!!.getOrThrow()
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, condition: (ExoPlayer) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!onPlayer(condition)) {
            error?.let { throw AssertionError("player error while waiting for $what", it) }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            SystemClock.sleep(50)
        }
    }

    private fun firstCueMs(vtt: File): Long {
        val (h, m, s, ms) = CUE_START.find(vtt.readText())!!.destructured
        return ((h.ifEmpty { "0" }.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1_000 + ms.toLong()
    }

    private companion object {
        const val TAG = "HadokuPlayerTest"
        /** Half a second at 24 fps. */
        const val MAX_CONSECUTIVE_DROPS = 12
        val CUE_START = Regex("""(?m)^(?:(\d+):)?(\d{2}):(\d{2})\.(\d{3}) -->""")
    }
}
