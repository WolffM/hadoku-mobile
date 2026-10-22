package me.hadoku.app

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.hadoku.app.library.LibraryActivity

enum class Tab(val label: String, val icon: ImageVector) {
    HADOKU("Hadoku", Icons.Filled.Home),
    LIBRARY("Library", Icons.AutoMirrored.Filled.List),
}

@Composable
fun HadokuTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * The app's two places: the live site, and the native Library that works
 * with no network. A compact bar rather than Material's 80 dp NavigationBar,
 * because under the WebView every dp is taken from the page. It hides while
 * the keyboard is up, so a form on the site keeps its room.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppBar(selected: Tab, onSelect: (Tab) -> Unit) {
    if (WindowInsets.isImeVisible) return
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(56.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Tab.entries.forEach { tab ->
                val tint = if (tab == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .selectable(selected = tab == selected, role = Role.Tab) { if (tab != selected) onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(tab.icon, contentDescription = null, tint = tint)
                    Text(tab.label, color = tint, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

object ShellBar {
    /**
     * Puts the bar under Capacitor's own layout (its CoordinatorLayout and
     * WebView stay exactly as Capacitor built them, so the bridge, insets
     * and Back are untouched). SystemBars' native insets pad the decor view,
     * so the bar lands above the gesture area with nothing more to do.
     */
    @JvmStatic
    fun install(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val page = content.getChildAt(0)
        content.removeView(page)
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(
            ComposeView(activity).apply {
                setContent { HadokuTheme { AppBar(Tab.HADOKU) { openLibrary(activity) } } }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        content.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Tabs switch without a transition, as tabs do. */
    @JvmStatic
    fun openLibrary(activity: Activity) {
        activity.startActivity(
            Intent(activity, LibraryActivity::class.java),
            ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle(),
        )
    }
}
