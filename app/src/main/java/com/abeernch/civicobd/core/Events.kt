package com.abeernch.civicobd.core

import android.content.ContentValues

/** Something that happened to the car: an engine start with its voltages, a fault code, codes cleared. */
data class CarEvent(val id: Long, val timeMs: Long, val type: String, val a: Double?, val b: Double?, val text: String?)

class Events(private val store: TripStore) {

    fun add(type: String, a: Double?, b: Double?, text: String?, timeMs: Long = System.currentTimeMillis()) {
        store.writableDatabase.insert("events", null, ContentValues().apply {
            put("time_ms", timeMs)
            put("type", type)
            a?.let { put("a", it) }
            b?.let { put("b", it) }
            text?.let { put("text", it) }
        })
    }

    fun recent(type: String, limit: Int = 30): List<CarEvent> {
        val out = mutableListOf<CarEvent>()
        store.readableDatabase.rawQuery(
            "SELECT id, time_ms, type, a, b, text FROM events WHERE type = ? ORDER BY time_ms DESC LIMIT $limit",
            arrayOf(type),
        ).use { c ->
            while (c.moveToNext()) {
                out += CarEvent(
                    c.getLong(0), c.getLong(1), c.getString(2),
                    if (c.isNull(3)) null else c.getDouble(3),
                    if (c.isNull(4)) null else c.getDouble(4),
                    c.getString(5),
                )
            }
        }
        return out
    }
}
