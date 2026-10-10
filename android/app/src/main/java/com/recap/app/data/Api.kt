package com.recap.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class Note(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val source: String,
    val title: String,
    val summary: String,
    val text: String,
) {
    val isDaily get() = source == "device-daily"
    val fromDevice get() = source.startsWith("device")
}

data class Hit(val noteId: String, val ts: Long, val text: String, val title: String, val score: Double)
data class SearchResult(val answer: String?, val hits: List<Hit>)

private fun JSONObject.toNote() = Note(
    getString("id"), getLong("startedAt"), getLong("endedAt"), getString("source"),
    optString("title"), optString("summary"), optString("text"),
)

class Api(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    private fun builder(path: String): Request.Builder {
        require(prefs.backendUrl.isNotEmpty()) { "Add your backend URL in Settings first." }
        return Request.Builder()
            .url(prefs.backendUrl + path)
            .header("Authorization", "Bearer ${prefs.apiKey}")
    }

    private suspend fun call(r: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(body).getString("error") }.getOrNull()
                throw IOException(msg ?: "Server error ${resp.code}")
            }
            JSONObject(body)
        }
    }

    suspend fun ping(): Boolean =
        runCatching { call(builder("/api/ping").build()).optBoolean("ok") }.getOrDefault(false)

    suspend fun notes(): List<Note> {
        val arr = call(builder("/api/notes?limit=200").build()).getJSONArray("notes")
        return List(arr.length()) { arr.getJSONObject(it).toNote() }
    }

    suspend fun delete(id: String) {
        call(builder("/api/notes/$id").delete().build())
    }

    /** Returns null when the server heard no speech. */
    suspend fun transcribe(wav: File, startedAt: Long): Note? {
        val req = builder("/api/transcribe?source=phone")
            .header("X-Recorded-At", startedAt.toString())
            .post(wav.asRequestBody("audio/wav".toMediaType()))
            .build()
        val res = call(req)
        return if (res.optBoolean("skipped")) null else res.getJSONObject("note").toNote()
    }

    suspend fun search(q: String): SearchResult {
        val body = JSONObject()
            .put("q", q)
            .put("ask", true)
            .put("tzMin", TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)
            .toString().toRequestBody(jsonType)
        val res = call(builder("/api/search").post(body).build())
        val arr = res.getJSONArray("hits")
        val hits = List(arr.length()) {
            val h = arr.getJSONObject(it)
            Hit(h.getString("noteId"), h.getLong("ts"), h.getString("text"), h.optString("title"), h.getDouble("score"))
        }
        return SearchResult(if (res.isNull("answer")) null else res.getString("answer"), hits)
    }

    suspend fun getMode(): String = call(builder("/api/config").build()).optString("mode", "push")

    suspend fun setMode(mode: String) {
        val body = JSONObject().put("mode", mode).toString().toRequestBody(jsonType)
        call(builder("/api/config").put(body).build())
    }
}
