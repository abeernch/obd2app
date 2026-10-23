package com.abeernch.civicobd.core

import android.content.ContentValues

/** A recorded session: a live drive or a file imported from Car Scanner. */
data class RecordingRow(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val source: String,     // "Live" or "Car Scanner"
    val name: String,
    val tripId: Long?,
)

data class Channel(val id: Long, val name: String, val unit: String) {
    val label: String get() = if (unit.isEmpty()) name else "$name  $unit"
}

/** One channel's samples. Times in ms since epoch. */
class Series(val channel: Channel, val t: LongArray, val v: FloatArray) {
    val size: Int get() = t.size

    /** Index of the last sample at or before [time], or -1. */
    fun indexAt(time: Long): Int {
        var lo = 0
        var hi = t.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] <= time) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}

/** One sample waiting to be written. */
data class Sample(val tMs: Long, val name: String, val unit: String, val value: Double)

/** Reads and writes recordings in the same database as the trips. */
class Recordings(private val store: TripStore) {

    private val channelIds = HashMap<String, Long>()

    private fun channelId(name: String, unit: String): Long {
        val key = "$name|$unit"
        channelIds[key]?.let { return it }
        val db = store.writableDatabase
        db.rawQuery("SELECT id FROM channels WHERE name = ? AND unit = ?", arrayOf(name, unit)).use { c ->
            if (c.moveToFirst()) return c.getLong(0).also { channelIds[key] = it }
        }
        val id = db.insert("channels", null, ContentValues().apply {
            put("name", name)
            put("unit", unit)
        })
        if (id >= 0) {
            channelIds[key] = id
            return id
        }
        // Another part of the app created it at the same moment: read it back.
        db.rawQuery("SELECT id FROM channels WHERE name = ? AND unit = ?", arrayOf(name, unit)).use { c ->
            c.moveToFirst()
            return c.getLong(0).also { channelIds[key] = it }
        }
    }

    fun create(startMs: Long, source: String, name: String, tripId: Long?): Long =
        store.writableDatabase.insert("recordings", null, ContentValues().apply {
            put("start_ms", startMs)
            put("end_ms", startMs)
            put("source", source)
            put("name", name)
            if (tripId != null) put("trip_id", tripId)
        })

    fun setEnd(recId: Long, endMs: Long) {
        store.writableDatabase.update(
            "recordings", ContentValues().apply { put("end_ms", endMs) },
            "id = ?", arrayOf(recId.toString()),
        )
    }

    fun setTrip(recId: Long, tripId: Long) {
        store.writableDatabase.update(
            "recordings", ContentValues().apply { put("trip_id", tripId) },
            "id = ?", arrayOf(recId.toString()),
        )
    }

    /** Writes many samples in one transaction. */
    fun add(recId: Long, samples: List<Sample>) {
        if (samples.isEmpty()) return
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("INSERT INTO samples (rec_id, ch_id, t_ms, value) VALUES (?, ?, ?, ?)")
            for (s in samples) {
                stmt.bindLong(1, recId)
                stmt.bindLong(2, channelId(s.name, s.unit))
                stmt.bindLong(3, s.tMs)
                stmt.bindDouble(4, s.value)
                stmt.executeInsert()
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Deletes a recording, its samples and, if [withTrip], its trip. */
    fun delete(recId: Long, withTrip: Boolean) {
        val rec = get(recId)
        val db = store.writableDatabase
        db.delete("samples", "rec_id = ?", arrayOf(recId.toString()))
        db.delete("recordings", "id = ?", arrayOf(recId.toString()))
        if (withTrip && rec?.tripId != null) store.delete(rec.tripId)
    }

    fun get(recId: Long): RecordingRow? = list("WHERE id = ?", arrayOf(recId.toString())).firstOrNull()

    fun all(): List<RecordingRow> = list("", emptyArray())

    fun exists(startMs: Long, source: String): Boolean =
        list("WHERE start_ms = ? AND source = ?", arrayOf(startMs.toString(), source)).isNotEmpty()

    private fun list(where: String, args: Array<String>): List<RecordingRow> {
        val out = mutableListOf<RecordingRow>()
        store.readableDatabase.rawQuery(
            "SELECT id, start_ms, end_ms, source, name, trip_id FROM recordings $where ORDER BY start_ms DESC",
            args,
        ).use { c ->
            while (c.moveToNext()) {
                out += RecordingRow(
                    id = c.getLong(0), startMs = c.getLong(1), endMs = c.getLong(2),
                    source = c.getString(3) ?: "", name = c.getString(4) ?: "",
                    tripId = if (c.isNull(5)) null else c.getLong(5),
                )
            }
        }
        return out
    }

    /** Channels that have samples in this recording, in a stable order. */
    fun channels(recId: Long): List<Channel> {
        val out = mutableListOf<Channel>()
        store.readableDatabase.rawQuery(
            "SELECT c.id, c.name, c.unit FROM channels c WHERE c.id IN " +
                "(SELECT DISTINCT ch_id FROM samples WHERE rec_id = ?) ORDER BY c.name",
            arrayOf(recId.toString()),
        ).use { c ->
            while (c.moveToNext()) out += Channel(c.getLong(0), c.getString(1), c.getString(2) ?: "")
        }
        return out
    }

    fun series(recId: Long, channel: Channel): Series {
        val times = ArrayList<Long>()
        val values = ArrayList<Float>()
        store.readableDatabase.rawQuery(
            "SELECT t_ms, value FROM samples WHERE rec_id = ? AND ch_id = ? ORDER BY t_ms",
            arrayOf(recId.toString(), channel.id.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                times += c.getLong(0)
                values += c.getDouble(1).toFloat()
            }
        }
        return Series(channel, times.toLongArray(), values.toFloatArray())
    }
}
