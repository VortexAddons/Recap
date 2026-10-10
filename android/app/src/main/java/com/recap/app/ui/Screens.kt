package com.recap.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.recap.app.AppViewModel
import com.recap.app.data.Hit
import com.recap.app.data.Note
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- shared
@Composable
fun VoiceOrb(level: Int, active: Boolean, ok: Boolean = false, modifier: Modifier = Modifier) {
    val l by animateFloatAsState(if (active) level / 100f else 0f, tween(120), label = "level")
    val color = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 0.96f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "b",
    )
    Box(modifier.size(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(color.copy(alpha = 0.12f), r * (0.55f + 0.45f * l) * breathe)
            drawCircle(color.copy(alpha = 0.22f), r * (0.45f + 0.30f * l))
        }
        Surface(Modifier.size(104.dp), shape = CircleShape, color = color) {
            Box(contentAlignment = Alignment.Center) {
                Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.Mic, null, Modifier.size(44.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- notes
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(vm: AppViewModel, openSettings: () -> Unit) {
    LaunchedEffect(Unit) { while (true) { vm.refresh(); delay(30_000) } }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                title = { Text("Recap") },
                scrollBehavior = scroll,
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Rounded.Refresh, "Refresh") }
                    IconButton(onClick = openSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ask about anything you've said…") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (vm.query.isNotEmpty()) IconButton(onClick = vm::clearSearch) { Icon(Icons.Rounded.Close, "Clear") }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                )
            }
            if (vm.searching || vm.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

            val result = vm.searchResult
            if (result != null) {
                result.answer?.let { a -> item { AnswerCard(a) } }
                if (result.hits.isEmpty()) item { Hint("Nothing matched. Try different words.") }
                items(result.hits) { HitCard(it) }
            } else if (vm.prefs.backendUrl.isEmpty()) {
                item { Hint("Open Settings (top right) and paste your backend URL to get started.") }
            } else if (vm.notes.isEmpty() && !vm.loading) {
                item { Hint("No notes yet. Record one from the Record tab, or hold the button on your Recapper.") }
            } else {
                vm.notes.groupBy { Fmt.dayKey(it.startedAt) }.forEach { (day, list) ->
                    item(key = "h$day") {
                        Text(Fmt.day(day), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                    }
                    items(list, key = { it.id }) { NoteCard(it) { vm.deleteNote(it) } }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp))
}

@Composable
private fun AnswerCard(answer: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(answer, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun HitCard(h: Hit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${Fmt.day(Fmt.dayKey(h.ts))} · ${Fmt.time(h.ts)} · ${h.title}",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(h.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NoteCard(n: Note, onDelete: () -> Unit) {
    var open by rememberSaveable(n.id) { mutableStateOf(false) }
    ElevatedCard(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (n.fromDevice) Icons.Rounded.Sensors else Icons.Rounded.Mic, null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (n.isDaily) "Daily recording" else n.title.ifBlank { "Untitled note" },
                        style = MaterialTheme.typography.titleMedium)
                    val range = if (n.isDaily) "${Fmt.time(n.startedAt)} – ${Fmt.time(n.endedAt)}" else Fmt.time(n.startedAt)
                    Text(range + if (n.fromDevice) " · Recapper" else " · Phone",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (open) IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete note") }
            }
            if (!open && n.summary.isNotBlank()) {
                Text(n.summary, maxLines = 2, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            AnimatedVisibility(open) {
                Text(n.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- record
@Composable
fun RecordScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val level by vm.recLevel.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording() else vm.message = "Microphone permission is needed to record."
    }
    fun onTap() {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (vm.recording || granted) vm.toggleRecord() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(
            when {
                vm.processing -> "Transcribing…"
                vm.recording -> Fmt.clock(vm.recSeconds)
                else -> "Ready when you are"
            },
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(24.dp))
        Box(contentAlignment = Alignment.Center) {
            VoiceOrb(level = (level * 100).toInt(), active = vm.recording)
            if (vm.processing) CircularProgressIndicator(Modifier.size(150.dp), strokeWidth = 3.dp)
            FilledIconButton(
                onClick = { onTap() },
                enabled = !vm.processing,
                modifier = Modifier.size(104.dp).alpha(if (vm.processing) 0f else 1f),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (vm.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (vm.recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Icon(if (vm.recording) Icons.Rounded.Stop else Icons.Rounded.Mic, if (vm.recording) "Stop" else "Record", Modifier.size(44.dp)) }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (vm.recording) "Tap to stop and transcribe" else "Tap to record with your phone's microphone",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- device
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(vm: AppViewModel) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { LargeTopAppBar(title = { Text("Recapper") }, scrollBehavior = scroll) },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Sensors, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Column {
                            Text(if (vm.deviceConfigured) "Recapper is set up" else "Connect to Recapper",
                                style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (!vm.deviceConfigured) "Add the ESP32 pocket recorder to Recap."
                                else vm.lastDeviceAt?.let { "Last heard from it ${Fmt.ago(it)}" } ?: "No recordings from it yet",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (vm.deviceConfigured) {
                        FilledTonalButton(onClick = vm::beginSetup, modifier = Modifier.fillMaxWidth()) { Text("Set up again") }
                        Text("To re-pair, hold the Recapper's button while plugging it in until it enters setup mode (about 3 seconds).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Button(onClick = vm::beginSetup, modifier = Modifier.fillMaxWidth()) { Text("Set up Recapper") }
                    }
                }
            }

            if (vm.deviceConfigured) {
                Text("Mode", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = vm.deviceMode == "push", onClick = { vm.setMode("push") },
                        shape = SegmentedButtonDefaults.itemShape(0, 2), label = { Text("Push-to-talk") })
                    SegmentedButton(selected = vm.deviceMode == "daily", onClick = { vm.setMode("daily") },
                        shape = SegmentedButtonDefaults.itemShape(1, 2), label = { Text("Daily") })
                }
                Text(
                    if (vm.deviceMode == "daily")
                        "Recapper listens all day and keeps only the parts where someone is speaking. Clips are transcribed and grouped into daily recordings. Changes reach the device within about 30 seconds."
                    else
                        "Hold the button on Recapper to record, release to send. Notes appear here a few seconds later.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                TextButton(onClick = vm::forgetDevice) { Text("Forget this device in the app") }
            }
        }
    }
}
