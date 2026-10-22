package me.hadoku.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** The synthetic episode bundled in the test APK (scripts/make-fixture-episode.sh). */
object Fixture {
    const val ID = "fixture-s01e01"

    /** Copies the fixture's files into [dir], replacing whatever was there. */
    fun copyTo(dir: File) {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun copy(path: String, to: File) {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) {
                to.parentFile!!.mkdirs()
                assets.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
            } else children.forEach { copy("$path/$it", File(to, it)) }
        }
        dir.deleteRecursively()
        copy("episodes/$ID", dir)
    }
}
