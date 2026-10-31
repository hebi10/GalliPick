package com.hebi.gallipick

import android.content.Context
import com.hebi.gallipick.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("filters", Context.MODE_PRIVATE)
    fun loadFilter(): MediaFilter = runCatching {
        decode(JSONObject(prefs.getString("default_filter", "{}") ?: "{}"))
    }.getOrDefault(MediaFilter())

    fun saveFilter(filter: MediaFilter) {
        prefs.edit().putString("default_filter", encode(filter).toString()).apply()
    }

    fun loadPresets(): List<FilterPreset> = runCatching {
        val array = JSONArray(prefs.getString("presets", "[]") ?: "[]")
        (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            FilterPreset(entry.getString("id"), entry.getString("name"), decode(entry.getJSONObject("filter")))
        }
    }.getOrDefault(emptyList())

    fun savePresets(presets: List<FilterPreset>) {
        val array = JSONArray()
        presets.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("filter", encode(it.filter))) }
        prefs.edit().putString("presets", array.toString()).apply()
    }

    fun newPreset(name: String, filter: MediaFilter) = FilterPreset(UUID.randomUUID().toString(), name.trim(), filter)

    private fun encode(f: MediaFilter) = JSONObject().apply {
        put("kind", f.kind?.name); put("query", f.query); put("folder", f.folder); put("mimeType", f.mimeType)
        put("minSizeBytes", f.minSizeBytes); put("maxSizeBytes", f.maxSizeBytes)
        put("minDurationMs", f.minDurationMs); put("maxDurationMs", f.maxDurationMs)
        put("dateField", f.dateField.name); put("fromDateMs", f.fromDateMs); put("toDateMs", f.toDateMs)
        put("minPixels", f.minPixels); put("orientation", f.orientation?.name)
        put("favoritesOnly", f.favoritesOnly); put("protectedOnly", f.protectedOnly)
        put("sortField", f.sortField.name); put("sortDirection", f.sortDirection.name)
    }

    private fun decode(j: JSONObject) = MediaFilter(
        kind = j.optString("kind").takeIf { it.isNotBlank() }?.let { enumValueOf<MediaKind>(it) },
        query = j.optString("query"), folder = j.optString("folder"), mimeType = j.optString("mimeType"),
        minSizeBytes = j.longOrNull("minSizeBytes"), maxSizeBytes = j.longOrNull("maxSizeBytes"),
        minDurationMs = j.longOrNull("minDurationMs"), maxDurationMs = j.longOrNull("maxDurationMs"),
        dateField = enumValueOf(j.optString("dateField", "CAPTURED")),
        fromDateMs = j.longOrNull("fromDateMs"), toDateMs = j.longOrNull("toDateMs"),
        minPixels = j.longOrNull("minPixels"),
        orientation = j.optString("orientation").takeIf { it.isNotBlank() }?.let { enumValueOf<Orientation>(it) },
        favoritesOnly = j.optBoolean("favoritesOnly"), protectedOnly = j.optBoolean("protectedOnly"),
        sortField = enumValueOf(j.optString("sortField", "CAPTURED")),
        sortDirection = enumValueOf(j.optString("sortDirection", "DESCENDING")),
    )

    private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
}
