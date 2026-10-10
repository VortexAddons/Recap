package com.recap.app.data

import android.content.Context
import java.security.SecureRandom

/** Local settings. The API key is generated once and shared with the Recapper device during setup. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("recap", Context.MODE_PRIVATE)

    var backendUrl: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", normalize(v)).apply()

    var setupDone: Boolean
        get() = sp.getBoolean("setup", false)
        set(v) = sp.edit().putBoolean("setup", v).apply()

    val apiKey: String
        get() = sp.getString("key", null) ?: run {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            bytes.joinToString("") { "%02x".format(it) }.also { sp.edit().putString("key", it).apply() }
        }

    companion object {
        fun normalize(u: String): String {
            var s = u.trim().trimEnd('/')
            if (s.isNotEmpty() && !s.startsWith("http")) s = "https://$s"
            return s
        }
    }
}
