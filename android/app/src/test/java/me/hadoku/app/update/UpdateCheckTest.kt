package me.hadoku.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name).readText()

    @Test
    fun parsesTheRealLatestRelease() {
        // v0.1.7's releases/latest as GitHub served it on 2026-11-16 (author and uploader trimmed).
        val release = UpdateCheck.Release.parse(resource("releases-latest-v0.1.7.json"))!!
        assertEquals("0.1.7", release.version)
        assertEquals("https://github.com/WolffM/hadoku-mobile/releases/download/v0.1.7/hadoku-v0.1.7.apk", release.apkUrl)
        assertEquals("https://github.com/WolffM/hadoku-mobile/releases/tag/v0.1.7", release.pageUrl)
    }

    @Test
    fun refusesWhatIsNotAPlainReleaseWithAnApk() {
        val apk = """[{"name":"hadoku-v1.0.0.apk","browser_download_url":"https://x/a.apk"}]"""
        assertNull(UpdateCheck.Release.parse("""{"tag_name":"v1.0.0","assets":[]}"""))
        assertNull(UpdateCheck.Release.parse("""{"tag_name":"v1.0.0","prerelease":true,"assets":$apk}"""))
        assertNull(UpdateCheck.Release.parse("""{"tag_name":"nightly","assets":$apk}"""))
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(UpdateCheck.isNewer("0.1.8", "0.1.7"))
        assertTrue(UpdateCheck.isNewer("0.1.10", "0.1.9"))
        assertTrue(UpdateCheck.isNewer("1.0.0", "0.99.99"))
        assertFalse(UpdateCheck.isNewer("0.1.7", "0.1.7"))
        assertFalse(UpdateCheck.isNewer("0.1.6", "0.1.7"))
        assertFalse(UpdateCheck.isNewer("0.1.7", "0.0.0-dev"))
    }
}
