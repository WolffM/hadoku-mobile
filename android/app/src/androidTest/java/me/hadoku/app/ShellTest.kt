package me.hadoku.app

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.hadoku.app.library.LibraryActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The native parts of the shell: the bar, and what a launch with no network opens. */
@RunWith(AndroidJUnit4::class)
class ShellTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun theBarSwitchesBetweenTheSiteAndTheLibrary() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Library").performClick()
            Device.waitForResumed(LibraryActivity::class.java)
            compose.onNodeWithText("Hadoku").performClick()
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
