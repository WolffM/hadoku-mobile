package me.hadoku.app.library

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.hadoku.app.AppBar
import me.hadoku.app.Hadoku
import me.hadoku.app.HadokuTheme
import me.hadoku.app.Tab
import me.hadoku.app.player.PlayerActivity
import org.json.JSONException

/**
 * The native Library: episodes saved for offline, and the watchparty catalog
 * to save them from. It sits above the shell's MainActivity, so the Hadoku
 * tab (and Back from the Library's first screen) is simply finish().
 * Everything here works without a network except Browse and Download.
 */
class LibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        setContent {
            HadokuTheme {
                Library(
                    onHadoku = ::finish,
                    onPlay = { startActivity(PlayerActivity.intent(this, it)) },
                )
            }
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data class Browse(val prefix: String, val name: String) : Screen
    data class Pick(val entry: CatalogPage.Entry) : Screen
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Library(onHadoku: () -> Unit, onPlay: (String) -> Unit) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val screen = stack.last()
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (screen) {
                            Screen.Home -> "Library"
                            is Screen.Browse -> screen.name
                            is Screen.Pick -> screen.entry.title
                        }
                    )
                },
                navigationIcon = {
                    if (stack.size > 1) {
                        IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
        bottomBar = { AppBar(Tab.LIBRARY) { onHadoku() } },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (screen) {
                Screen.Home -> Home(
                    onBrowse = { stack.add(Screen.Browse(Watchparty.ROOT, "Watchparty")) },
                    onPlay = onPlay,
                )
                is Screen.Browse -> Browse(
                    prefix = screen.prefix,
                    onDir = { stack.add(Screen.Browse(it.path, it.name)) },
                    onEpisode = { stack.add(Screen.Pick(it)) },
                    onSignIn = onHadoku,
                )
                is Screen.Pick -> Pick(
                    entry = screen.entry,
                    onStarted = { stack.removeRange(1, stack.size) },
                    onSignIn = onHadoku,
                )
            }
        }
    }
}

@Composable
private fun Home(onBrowse: () -> Unit, onPlay: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<Downloads.Row>?>(null) }
    var storage by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<Episode?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                withContext(Dispatchers.IO) {
                    rows = Downloads.refresh(context)
                    storage = "Library ${size(context, Downloads.store(context).bytesUsed())} · " +
                        "${size(context, Downloads.freeBytes(context))} free on this phone"
                }
                delay(1_000)
            }
        }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(storage, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("storage"))
                Spacer(Modifier.height(12.dp))
                Button(onClick = onBrowse) { Text("Browse watchparty") }
            }
            HorizontalDivider()
        }
        val list = rows
        if (list != null && list.isEmpty()) {
            item {
                Text(
                    "Nothing saved yet. Browse watchparty and download an episode to watch it with no connection.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        items(list.orEmpty(), key = { it.episode.mediaId }) { row ->
            EpisodeRow(
                row,
                onPlay = { onPlay(row.episode.mediaId) },
                onRetry = { scope.launch(Dispatchers.IO) { Downloads.retry(context, row.episode.mediaId) } },
                onDelete = { confirmDelete = row.episode },
            )
            HorizontalDivider()
        }
    }
    confirmDelete?.let { episode ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${episode.title}?") },
            text = { Text("Its files are removed from this phone. You can download it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { Downloads.delete(context, episode.mediaId) }
                        rows = rows?.filterNot { it.episode.mediaId == episode.mediaId }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EpisodeRow(row: Downloads.Row, onPlay: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val e = row.episode
    val status = when {
        e.failed -> e.problem ?: e.files.first { it.failure != null }.failure!!
        e.ready -> "Ready · ${size(context, row.bytesTotal)}" +
            if (e.positionMs > 0) " · resume at ${DateUtils.formatElapsedTime(e.positionMs / 1000)}" else ""
        row.waitingForNetwork -> "Waiting for network · ${size(context, row.bytesDone)} of ${size(context, row.bytesTotal)}"
        else -> "Downloading · ${size(context, row.bytesDone)} of ${size(context, row.bytesTotal)}"
    }
    ListItem(
        modifier = Modifier.testTag("episode:${e.mediaId}").let { if (e.ready) it.clickable(onClick = onPlay) else it },
        overlineContent = { Text(e.series) },
        headlineContent = { Text(e.title) },
        supportingContent = {
            Column {
                Text(status, color = if (e.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!e.ready && !e.failed && row.bytesTotal > 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (row.bytesDone.toFloat() / row.bytesTotal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        trailingContent = {
            Row {
                when {
                    e.ready -> IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play") }
                    e.failed && e.problem == null -> IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = "Retry") }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            }
        },
    )
}

/** A load in progress, done, or failed with something the viewer can act on. */
private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data object SignedOut : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
}

private suspend fun <T> load(block: () -> T): Load<T> = withContext(Dispatchers.IO) {
    try {
        Load.Done(block())
    } catch (e: Hadoku.SignedOut) {
        Load.SignedOut
    } catch (e: IOException) {
        Load.Failed("Couldn't reach hadoku.me (${e.message}).")
    } catch (e: JSONException) {
        Load.Failed("hadoku.me sent something unexpected (${e.message}).")
    }
}

@Composable
private fun <T> Loaded(state: Load<T>, onRetry: () -> Unit, onSignIn: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Load.SignedOut -> Message("Sign in to hadoku.me on the Hadoku tab to browse and download.", "Open Hadoku", onSignIn)
        is Load.Failed -> Message(state.message, "Try again", onRetry)
        is Load.Done -> content(state.value)
    }
}

@Composable
private fun Message(text: String, action: String, onAction: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun Browse(
    prefix: String,
    onDir: (CatalogPage.Dir) -> Unit,
    onEpisode: (CatalogPage.Entry) -> Unit,
    onSignIn: () -> Unit,
) {
    val context = LocalContext.current
    var attempt by remember { mutableStateOf(0) }
    var state by remember(prefix) { mutableStateOf<Load<Pair<CatalogPage, Set<String>>>>(Load.Loading) }
    LaunchedEffect(prefix, attempt) {
        state = Load.Loading
        state = load { Watchparty.catalog(context, prefix) to Downloads.store(context).all().map { it.mediaId }.toSet() }
    }
    Loaded(state, onRetry = { attempt++ }, onSignIn = onSignIn) { (page, saved) ->
        LazyColumn {
            items(page.dirs, key = { "d:" + it.path }) { dir ->
                ListItem(
                    modifier = Modifier.clickable { onDir(dir) },
                    headlineContent = { Text(dir.name) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                )
            }
            items(page.episodes, key = { "e:" + it.mediaId }) { entry ->
                ListItem(
                    modifier = Modifier.clickable { onEpisode(entry) },
                    headlineContent = { Text(entry.title) },
                    trailingContent = {
                        if (entry.mediaId in saved) Icon(Icons.Filled.CheckCircle, contentDescription = "In your Library")
                    },
                )
            }
            if (page.dirs.isEmpty() && page.episodes.isEmpty()) {
                item { Text("Nothing here.", Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun Pick(entry: CatalogPage.Entry, onStarted: () -> Unit, onSignIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableStateOf(0) }
    var state by remember(entry) { mutableStateOf<Load<EpisodeInfo>>(Load.Loading) }
    var startError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entry, attempt) {
        state = Load.Loading
        state = load { Watchparty.episode(context, entry.mediaId) }
    }
    Loaded(state, onRetry = { attempt++ }, onSignIn = onSignIn) { info ->
        // All audio and all subtitles by default: subtitles are a few KB each,
        // and one extra language costs far less than finding it missing offline.
        val audio = remember(info) { mutableStateListOf(*info.audio.toTypedArray()) }
        val subs = remember(info) { mutableStateListOf(*info.subtitles.toTypedArray()) }
        val saved = remember(info) { Downloads.store(context).get(info.mediaId) != null }
        val free = remember(info) { Downloads.freeBytes(context) }
        val need = info.video.bytes + audio.sumOf { it.file.bytes } + subs.sumOf { it.file.bytes }
        val fits = need + Downloads.RESERVE_BYTES <= free
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Text(
                    "${seriesOf(info.mediaId)} · ${DateUtils.formatElapsedTime(info.durationMs / 1000)} · video ${size(context, info.video.bytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text("Audio", style = MaterialTheme.typography.titleMedium)
            }
            items(info.audio, key = { "a:" + it.file.path }) { a ->
                Choice("${a.label} · ${size(context, a.file.bytes)}", a in audio) { if (it) audio.add(a) else audio.remove(a) }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Text("Subtitles", style = MaterialTheme.typography.titleMedium)
                if (info.subtitles.isEmpty()) Text("None on the server.")
            }
            items(info.subtitles, key = { "s:" + it.file.path }) { s ->
                Choice(languageLabel(s.lang), s in subs) { if (it) subs.add(s) else subs.remove(s) }
            }
            item {
                Spacer(Modifier.height(16.dp))
                when {
                    saved -> Text("Already in your Library.")
                    !fits -> Text(
                        "Needs ${size(context, need)}, and only ${size(context, free)} is free " +
                            "(the Library leaves ${size(context, Downloads.RESERVE_BYTES)} spare).",
                        color = MaterialTheme.colorScheme.error,
                    )
                    audio.isEmpty() -> Text("Choose at least one audio language.")
                }
                startError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = !saved && fits && audio.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            startError = null
                            val started = load {
                                Downloads.start(context, info, entry.title, seriesOf(info.mediaId), audio.toList(), subs.toList())
                            }
                            when (started) {
                                is Load.Done -> onStarted()
                                Load.SignedOut -> onSignIn()
                                is Load.Failed -> startError = started.message
                                Load.Loading -> Unit
                            }
                        }
                    },
                ) { Text("Download · ${size(context, need)}") }
            }
        }
    }
}

@Composable
private fun Choice(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

private fun size(context: Context, bytes: Long) = Formatter.formatShortFileSize(context, bytes)

/** The folder an episode sits in is its series (or season) name. */
private fun seriesOf(mediaId: String) = mediaId.substringBeforeLast('/').substringAfterLast('/')

/** "fre" → "French": Media3 maps ISO 639-2 codes, bibliographic ones too, to the two-letter tags Locale names. */
@OptIn(UnstableApi::class)
private fun languageLabel(code: String): String {
    val tag = Util.normalizeLanguageCode(code) ?: code
    return Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
}
