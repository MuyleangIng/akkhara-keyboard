package com.akkhara.keyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Clipboard history for the keyboard's Clipboard panel.
 *
 * Privacy (matches the offline promise):
 *  - stored ONLY on this phone (app-private SharedPreferences), never synced or sent anywhere;
 *    the app has no internet permission at all
 *  - the IME skips clips marked sensitive (Android 13+) and anything copied while a password
 *    field is focused (see AkkharaIME.onClip)
 *  - unpinned items are deleted after [TTL_MS] (1 hour)
 *  - items that look like OTP codes or card / account numbers are shown hidden until tapped
 */
class ClipStore(context: Context) {
    data class Clip(val text: String, val time: Long, val pinned: Boolean)

    private val prefs = context.getSharedPreferences("akkhara_clips", Context.MODE_PRIVATE)

    fun all(now: Long = System.currentTimeMillis()): List<Clip> {
        val list = load().filter { it.pinned || now - it.time < TTL_MS }
        return list.sortedByDescending { it.time }
    }

    fun add(text: String, now: Long = System.currentTimeMillis()) {
        val t = text.take(MAX_CHARS)
        if (t.isBlank()) return
        val old = load().filter { it.text != t || it.pinned }
        val pinnedSame = old.any { it.text == t && it.pinned }
        val list = (if (pinnedSame) old.map { if (it.text == t) it.copy(time = now) else it } else listOf(Clip(t, now, false)) + old)
            .filter { it.pinned || now - it.time < TTL_MS }
            .take(MAX_ITEMS)
        save(list)
    }

    fun togglePin(text: String) = save(load().map { if (it.text == text) it.copy(pinned = !it.pinned) else it })

    fun remove(text: String) = save(load().filter { it.text != text })

    fun clearUnpinned() = save(load().filter { it.pinned })

    private fun load(): List<Clip> = try {
        val a = JSONArray(prefs.getString(KEY, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Clip(o.getString("t"), o.getLong("at"), o.optBoolean("pin"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(list: List<Clip>) {
        val a = JSONArray()
        for (c in list) a.put(JSONObject().put("t", c.text).put("at", c.time).put("pin", c.pinned))
        prefs.edit().putString(KEY, a.toString()).apply()
    }

    companion object {
        private const val KEY = "clips"
        const val TTL_MS = 60 * 60 * 1000L     // unpinned items live 1 hour
        private const val MAX_ITEMS = 30
        private const val MAX_CHARS = 4000

        /** OTP codes, card / account numbers: hide the preview until tapped. */
        fun looksPrivate(t: String): Boolean {
            val s = t.trim()
            val digits = s.count { it.isDigit() }
            return Regex("^\\d{4,8}$").matches(s) || (digits >= 10 && digits * 10 >= s.length * 6)
        }
    }
}
