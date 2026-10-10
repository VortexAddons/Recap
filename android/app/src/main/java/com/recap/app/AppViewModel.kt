package com.recap.app

import android.app.Application
import android.content.Context
import android.content.IntentSender
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.recap.app.audio.WavRecorder
import com.recap.app.ble.LinkState
import com.recap.app.ble.Pairing
import com.recap.app.ble.RecapperLink
import com.recap.app.data.Api
import com.recap.app.data.Note
import com.recap.app.data.Prefs
import com.recap.app.data.SearchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class SetupStep { Backend, Connect, Test, Wifi, Done }
enum class TestState { Idle, Listening, Passed, Failed }
enum class WifiState { Idle, Connecting, Failed, Connected }
data class Net(val rssi: Int, val secure: Boolean, val ssid: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    private val api = Api(prefs)
    private val link = RecapperLink(app)

    var message by mutableStateOf<String?>(null)

    // ---- notes + search ----
    var notes by mutableStateOf<List<Note>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var query by mutableStateOf("")
    var searching by mutableStateOf(false); private set
    var searchResult by mutableStateOf<SearchResult?>(null); private set

    // ---- phone recording ----
    val recLevel = MutableStateFlow(0f)
    var recording by mutableStateOf(false); private set
    var processing by mutableStateOf(false); private set
    var recSeconds by mutableIntStateOf(0); private set
    private var recorder: WavRecorder? = null

    // ---- device ----
    var deviceMode by mutableStateOf("push"); private set
    var deviceConfigured by mutableStateOf(prefs.setupDone); private set
    val lastDeviceAt: Long? get() = notes.firstOrNull { it.fromDevice }?.endedAt

    // ---- setup flow ----
    var showSetup by mutableStateOf(false); private set
    var step by mutableStateOf(SetupStep.Backend); private set
    var checking by mutableStateOf(false); private set
    var pairing by mutableStateOf(false); private set
    var setupError by mutableStateOf<String?>(null)
    var level by mutableIntStateOf(0); private set
    var testState by mutableStateOf(TestState.Idle); private set
    var nets by mutableStateOf<List<Net>>(emptyList()); private set
    var scanning by mutableStateOf(false); private set
    var wifiState by mutableStateOf(WifiState.Idle); private set
    var wifiMessage by mutableStateOf(""); private set
    private var loud = 0
    private var connectingMac: String? = null

    init {
        viewModelScope.launch { link.events.collect { onEvent(it) } }
        viewModelScope.launch {
            link.state.collect { st ->
                if (!showSetup || !pairing) return@collect
                when (st) {
                    LinkState.Ready -> { pairing = false; link.send("HELLO"); testState = TestState.Idle; step = SetupStep.Test }
                    LinkState.Failed -> {
                        pairing = false; connectingMac = null
                        setupError = "Couldn't connect to Recapper. Make sure it's powered and in setup mode."
                    }
                    else -> Unit
                }
            }
        }
    }

    // ================= notes =================
    fun refresh() {
        if (prefs.backendUrl.isEmpty()) return
        viewModelScope.launch {
            loading = true
            try {
                notes = api.notes()
                deviceMode = api.getMode()
            } catch (e: Exception) {
                if (notes.isEmpty()) message = e.message
            } finally { loading = false }
        }
    }

    fun deleteNote(n: Note) {
        viewModelScope.launch {
            try { api.delete(n.id); notes = notes.filter { it.id != n.id } }
            catch (e: Exception) { message = e.message ?: "Couldn't delete" }
        }
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) { searchResult = null; return }
        viewModelScope.launch {
            searching = true
            try { searchResult = api.search(q) }
            catch (e: Exception) { message = e.message ?: "Search failed" }
            finally { searching = false }
        }
    }

    fun clearSearch() { query = ""; searchResult = null }

    // ================= settings =================
    fun saveBackend(url: String) { prefs.backendUrl = url; refresh() }
    suspend fun testBackend(url: String): Boolean { prefs.backendUrl = url; return api.ping() }

    // ================= phone recording =================
    fun toggleRecord() = if (recording) stopRecording() else startRecording()

    fun startRecording() {
        if (prefs.backendUrl.isEmpty()) { message = "Add your backend URL in Settings first."; return }
        val file = File(getApplication<Application>().cacheDir, "note.wav")
        recorder = WavRecorder(file, recLevel).also { it.start(viewModelScope) }
        recording = true
        recSeconds = 0
        viewModelScope.launch {
            while (recording) {
                delay(1000)
                if (!recording) break
                recSeconds++
                if (recSeconds >= WavRecorder.MAX_SECONDS) { stopRecording(); break }
            }
        }
    }

    fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        recording = false
        viewModelScope.launch {
            processing = true
            val file = r.stop()
            try {
                val note = api.transcribe(file, r.startedAt)
                message = if (note == null) "Didn't catch any speech." else "Saved: ${note.title}"
                refresh()
            } catch (e: Exception) {
                message = e.message ?: "Upload failed"
            } finally {
                processing = false
                file.delete()
            }
        }
    }

    // ================= device mode =================
    fun setMode(mode: String) {
        deviceMode = mode
        viewModelScope.launch {
            try { api.setMode(mode) } catch (e: Exception) { message = e.message ?: "Couldn't change mode" }
        }
    }

    fun forgetDevice() { prefs.setupDone = false; deviceConfigured = false }

    // ================= setup flow =================
    fun beginSetup() {
        setupError = null; pairing = false; connectingMac = null
        testState = TestState.Idle; wifiState = WifiState.Idle; wifiMessage = ""; nets = emptyList()
        step = SetupStep.Backend
        showSetup = true
    }

    fun cancelSetup() { link.close(); pairing = false; showSetup = false }

    fun checkBackend(url: String) {
        viewModelScope.launch {
            checking = true; setupError = null
            if (testBackend(url)) step = SetupStep.Connect
            else setupError = "Couldn't reach that address. Check the URL and that the Worker is deployed."
            checking = false
        }
    }

    fun startPairing(ctx: Context, launch: (IntentSender) -> Unit) {
        pairing = true; setupError = null
        Pairing.request(ctx, launch, ::onPaired, ::pairFailed)
    }

    fun onPaired(mac: String) {
        if (connectingMac == mac) return
        connectingMac = mac
        link.connect(mac)
    }

    fun pairFailed(msg: String) { pairing = false; setupError = msg }

    fun startTest() { loud = 0; level = 0; testState = TestState.Listening; link.send("TEST_START") }

    fun goWifi() {
        step = SetupStep.Wifi
        rescan()
    }

    fun rescan() { nets = emptyList(); scanning = true; link.send("SCAN") }

    fun connectWifi(ssid: String, pass: String) {
        wifiState = WifiState.Connecting
        wifiMessage = "Connecting to $ssid…"
        setupError = null
        link.send("WIFI", ssid, pass, prefs.backendUrl, prefs.apiKey)
    }

    fun finishSetup() {
        link.send("FINISH")
        viewModelScope.launch { delay(800); link.close() }
        prefs.setupDone = true
        deviceConfigured = true
        showSetup = false
        refresh()
    }

    private fun onEvent(msg: String) {
        val p = msg.split("|")
        when (p[0]) {
            "LEVEL" -> {
                level = p.getOrNull(1)?.toIntOrNull() ?: 0
                if (testState == TestState.Listening) {
                    if (level >= 40) loud++
                    if (loud >= 8) { testState = TestState.Passed; link.send("TEST_STOP") }
                }
            }
            "TEST_END" -> if (testState == TestState.Listening) testState = TestState.Failed
            "NET" -> {
                val f = msg.split("|", limit = 4)
                if (f.size == 4) nets = (nets + Net(f[1].toIntOrNull() ?: -100, f[2] == "1", f[3])).distinctBy { it.ssid }
            }
            "NETS_DONE" -> scanning = false
            "WIFI_CONNECTING" -> wifiState = WifiState.Connecting
            "WIFI_OK" -> wifiMessage = "Joined WiFi. Checking your backend…"
            "WIFI_FAIL" -> { wifiState = WifiState.Failed; wifiMessage = p.getOrElse(1) { "Couldn't join that network" } }
            "CLOUD_OK" -> { wifiState = WifiState.Connected; step = SetupStep.Done }
            "CLOUD_FAIL" -> {
                wifiState = WifiState.Failed
                wifiMessage = "WiFi works, but Recapper couldn't reach your backend (code ${p.getOrNull(1)}). Check the Worker URL."
            }
        }
    }
}
