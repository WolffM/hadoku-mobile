package me.hadoku.app

import android.app.Activity
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/** The device under test, as the shell and the activity lifecycle see it. */
object Device {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).let { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }

    /** Airplane mode, and waits until the app's own view of the network agrees. */
    fun airplane(on: Boolean) {
        shell("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}")
        waitUntil("online=${!on}", 30_000) { Hadoku.online(instrumentation.targetContext) == !on }
    }

    fun resumed(): Activity? {
        var activity: Activity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        return activity
    }

    fun <T : Activity> waitForResumed(type: Class<T>, timeoutMs: Long = 15_000): T {
        waitUntil("${type.simpleName} resumed", timeoutMs) { type.isInstance(resumed()) }
        return type.cast(resumed())!!
    }

    fun waitUntil(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
            SystemClock.sleep(100)
        }
    }
}
