package me.hadoku.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** The synthetic episodes bundled in the test APK (scripts/make-fixture-episode.sh). */
object Fixture {
    const val ID = "fixture-s01e01"

    /** 150 s: more than the player reads ahead, as every real episode is. */
    const val LONG = "fixture-long"
    const val LONG_DURATION_MS = 150_024L

    /** Copies the fixture [id]'s files into [dir], replacing whatever was there. */
    fun copyTo(dir: File, id: String = ID) {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun copy(path: String, to: File) {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) {
                to.parentFile!!.mkdirs()
                assets.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
            } else children.forEach { copy("$path/$it", File(to, it)) }
        }
        dir.deleteRecursively()
        copy("episodes/$id", dir)
    }
}
