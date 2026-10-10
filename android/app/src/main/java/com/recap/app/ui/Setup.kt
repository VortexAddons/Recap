package com.recap.app.ui

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.recap.app.AppViewModel
import com.recap.app.SetupStep
import com.recap.app.TestState
import com.recap.app.WifiState
import com.recap.app.ble.Pairing
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: AppViewModel) {
    val steps = SetupStep.values()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set up Recapper") },
                navigationIcon = { IconButton(onClick = vm::cancelSetup) { Icon(Icons.Rounded.Close, "Close setup") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 24.dp).fillMaxSize()) {
            LinearProgressIndicator(
                progress = { (vm.step.ordinal + 1) / steps.size.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            AnimatedContent(vm.step, label = "step") { s ->
                when (s) {
                    SetupStep.Backend -> BackendStep(vm)
                    SetupStep.Connect -> ConnectStep(vm)
                    SetupStep.Test -> TestStep(vm)
                    SetupStep.Wifi -> WifiStep(vm)
                    SetupStep.Done -> DoneStep(vm)
                }
            }
        }
    }
}

@Composable
private fun StepColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun ErrorText(text: String?) {
    if (text != null) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

// ---- 1. backend ----
@Composable
private fun BackendStep(vm: AppViewModel) {
    var url by remember { mutableStateOf(vm.prefs.backendUrl) }
    StepColumn {
        Text("Your Recap backend", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth())
        Text(
            "Recapper sends audio to your own Cloudflare Worker for transcription. Paste its address (it ends in workers.dev).",
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Backend URL") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        ErrorText(vm.setupError)
        Button(onClick = { vm.checkBackend(url) }, enabled = url.isNotBlank() && !vm.checking, modifier = Modifier.fillMaxWidth()) {
            if (vm.checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Check and continue")
        }
    }
}

// ---- 2. pair (Android Companion Device Manager) ----
@Composable
private fun ConnectStep(vm: AppViewModel) {
    val ctx = LocalContext.current
    val sheet = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val mac = Pairing.macFromResult(ctx, res.data)
            if (mac != null) vm.onPaired(mac) else vm.pairFailed("Pairing finished but the device wasn't reported. Try again.")
        } else vm.pairFailed("Pairing was cancelled.")
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startPairing(ctx) { sender -> sheet.launch(IntentSenderRequest.Builder(sender).build()) }
        else vm.setupError = "Bluetooth permission is needed to talk to Recapper."
    }
    StepColumn {
        Text("Find your Recapper", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth())
        Text(
            "Plug in your Recapper and keep it close. A fresh device starts in setup mode by itself. If it was set up before, hold its button while plugging it in for about 3 seconds.",
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(vm.setupError)
        Button(onClick = { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }, enabled = !vm.pairing, modifier = Modifier.fillMaxWidth()) {
            if (vm.pairing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else Text("Find my Recapper")
        }
        if (vm.pairing) Text("Looking for Recapper and connecting…", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- 3. mic test ----
@Composable
private fun TestStep(vm: AppViewModel) {
    LaunchedEffect(vm.testState) {
        if (vm.testState == TestState.Passed) { delay(1400); vm.goWifi() }
    }
    StepColumn {
        VoiceOrb(level = vm.level, active = vm.testState == TestState.Listening, ok = vm.testState == TestState.Passed)
        Text(
            when (vm.testState) {
                TestState.Idle -> "Let's check the microphone"
                TestState.Listening -> "Listening… keep talking"
                TestState.Passed -> "Got it. Your mic works."
                TestState.Failed -> "I didn't hear enough"
            },
            style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
        )
        Text(
            when (vm.testState) {
                TestState.Idle -> "Tap Start, then say \"Hey Recap, testing one two three\" at a normal volume."
                TestState.Listening -> "Say \"Hey Recap, testing one two three\"."
                TestState.Passed -> "Next, connect Recapper to your WiFi."
                TestState.Failed -> "Move closer to the microphone, speak a little louder and try again."
            },
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (vm.testState == TestState.Idle || vm.testState == TestState.Failed) {
            Button(onClick = vm::startTest, modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.testState == TestState.Idle) "Start" else "Try again")
            }
        }
    }
}

// ---- 4. wifi ----
@Composable
private fun WifiStep(vm: AppViewModel) {
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    val busy = vm.wifiState == WifiState.Connecting

    StepColumn {
        Text("Pick your WiFi", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth())
        Text("Recapper needs a 2.4 GHz network. These are the ones it can see.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        if (vm.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth()) {
            vm.nets.forEach { n ->
                ListItem(
                    headlineContent = { Text(n.ssid) },
                    supportingContent = { Text(if (n.rssi > -60) "Strong signal" else if (n.rssi > -75) "Good signal" else "Weak signal") },
                    leadingContent = { Icon(if (n.secure) Icons.Rounded.Lock else Icons.Rounded.Wifi, null) },
                    modifier = Modifier.clickable(enabled = !busy) { ssid = n.ssid; manual = false },
                )
            }
        }
        Row2 {
            TextButton(onClick = vm::rescan, enabled = !vm.scanning && !busy) { Text("Scan again") }
            TextButton(onClick = { manual = true; ssid = "" }, enabled = !busy) { Text("Other network") }
        }
        if (manual) {
            OutlinedTextField(value = ssid, onValueChange = { ssid = it }, label = { Text("Network name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (ssid.isNotEmpty() || manual) {
            OutlinedTextField(
                value = pass, onValueChange = { pass = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (ssid.isNotEmpty() && !manual) "Password for $ssid" else "Password") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(onClick = { vm.connectWifi(ssid, pass) }, enabled = ssid.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Connect")
            }
        }
        if (vm.wifiMessage.isNotEmpty()) {
            Text(vm.wifiMessage, style = MaterialTheme.typography.bodyMedium,
                color = if (vm.wifiState == WifiState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Row2(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { content() }
}

// ---- 5. done ----
@Composable
private fun DoneStep(vm: AppViewModel) {
    StepColumn {
        VoiceOrb(level = 0, active = false, ok = true)
        Text("Recapper is ready", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Hold the button to record and release to send. Switch to Daily mode any time in the Recapper tab to capture your whole day.",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = vm::finishSetup, modifier = Modifier.fillMaxWidth()) { Text("Finish") }
    }
}
