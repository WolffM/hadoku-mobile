package me.hadoku.app

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.hadoku.app.library.LibraryActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The native parts of the shell: the way into the Library, and what a launch with no network opens. */
@RunWith(AndroidJUnit4::class)
class ShellTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun theLibraryLinkOpensTheLibraryAndItsBackArrowReturnsToTheSite() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Device.waitUntil("the launcher loaded", 30_000) {
                var url: String? = null
                scenario.onActivity { url = it.bridge.webView.url }
                url?.startsWith("https://hadoku.me/") == true
            }
            // What watchparty's lobby link does: a plain navigation to the link.
            scenario.onActivity { it.bridge.webView.evaluateJavascript("location.href = '${LibraryActivity.LINK}'", null) }
            Device.waitForResumed(LibraryActivity::class.java)
            compose.onNodeWithContentDescription("Back").performClick()
            Device.waitForResumed(MainActivity::class.java)
        }
    }

    @Test
    fun withNoNetworkTheAppOpensOnTheLibrary() {
        Device.airplane(true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Device.waitForResumed(LibraryActivity::class.java)
            }
        } finally {
            Device.airplane(false)
        }
    }
}
