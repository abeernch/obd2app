package com.abeernch.civicobd.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything the app knows, in one zip: the trip and recording database plus all settings.
 * Used before reinstalling, when changing phones, or as a routine copy to Drive.
 */
object Backup {

    private const val DB_ENTRY = "trips.db"
    private const val PREFS_ENTRY = "settings.json"
    private const val PREFS_NAME = "settings"

    fun create(context: Context, dir: File): File {
        val store = TripStore.get(context)
        // Fold the write-ahead log into the main file so one file holds everything.
        store.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        val db = context.getDatabasePath("trips.db")

        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val out = File(dir, "civic-backup_$stamp.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(DB_ENTRY))
            db.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(PREFS_ENTRY))
            zip.write(prefsToJson(context).toString(2).toByteArray())
            zip.closeEntry()
        }
        return out
    }

    /** Replaces all data with the backup. The caller restarts the app afterwards. */
    fun restore(context: Context, input: InputStream) {
        var dbBytes: ByteArray? = null
        var prefs: JSONObject? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    DB_ENTRY -> dbBytes = zip.readBytes()
                    PREFS_ENTRY -> prefs = JSONObject(String(zip.readBytes()))
                }
            }
        }
        val bytes = dbBytes ?: throw IllegalArgumentException("This file is not a Civic OBD backup")
        require(bytes.size > 100 && String(bytes, 0, 15) == "SQLite format 3") { "The backup's database is damaged" }

        TripStore.closeForRestore()
        val db = context.getDatabasePath("trips.db")
        File(db.path + "-wal").delete()
        File(db.path + "-shm").delete()
        File(db.path + "-journal").delete()
        db.writeBytes(bytes)
        prefs?.let { jsonToPrefs(context, it) }
    }

    private fun prefsToJson(context: Context): JSONObject {
        val json = JSONObject()
        for ((key, value) in context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all) {
            val typed = JSONObject()
            when (value) {
                is Boolean -> typed.put("type", "bool").put("value", value)
                is Float -> typed.put("type", "float").put("value", value.toDouble())
                is Int -> typed.put("type", "int").put("value", value)
                is Long -> typed.put("type", "long").put("value", value)
                is String -> typed.put("type", "string").put("value", value)
                else -> continue
            }
            json.put(key, typed)
        }
        return json
    }

    private fun jsonToPrefs(context: Context, json: JSONObject) {
        val edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear()
        for (key in json.keys()) {
            val typed = json.getJSONObject(key)
            when (typed.getString("type")) {
                "bool" -> edit.putBoolean(key, typed.getBoolean("value"))
                "float" -> edit.putFloat(key, typed.getDouble("value").toFloat())
                "int" -> edit.putInt(key, typed.getInt("value"))
                "long" -> edit.putLong(key, typed.getLong("value"))
                "string" -> edit.putString(key, typed.getString("value"))
            }
        }
        edit.commit()
    }
}
