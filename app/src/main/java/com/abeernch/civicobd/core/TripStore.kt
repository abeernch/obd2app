package com.abeernch.civicobd.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One drive, from engine start to engine off. */
data class TripRow(
    val id: Long = -1,
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Double = 0.0,
    val fuelL: Double = 0.0,
    val cost: Double = 0.0,
    val driveS: Double = 0.0,   // seconds moving
    val idleS: Double = 0.0,    // seconds standing with the engine running
    val maxSpeed: Double = 0.0,
)

/** Totals over any set of trips. */
data class Summary(
    val trips: Int = 0,
    val distanceKm: Double = 0.0,
    val fuelL: Double = 0.0,
    val cost: Double = 0.0,
    val driveS: Double = 0.0,
    val idleS: Double = 0.0,
) {
    val totalS: Double get() = driveS + idleS
    val kmPerL: Double? get() = if (fuelL > 0.01 && distanceKm > 0.05) distanceKm / fuelL else null
    val avgSpeed: Double? get() = if (totalS > 10) distanceKm / totalS * 3600 else null

    companion object {
        fun of(rows: List<TripRow>) = Summary(
            trips = rows.size,
            distanceKm = rows.sumOf { it.distanceKm },
            fuelL = rows.sumOf { it.fuelL },
            cost = rows.sumOf { it.cost },
            driveS = rows.sumOf { it.driveS },
            idleS = rows.sumOf { it.idleS },
        )
    }
}

/** Local SQLite database of trips. Nothing leaves the phone. */
class TripStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "trips.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE trips (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER, end_ms INTEGER, " +
                "distance_km REAL, fuel_l REAL, cost REAL, drive_s REAL, idle_s REAL, max_speed REAL)"
        )
        db.execSQL("CREATE INDEX trips_start ON trips(start_ms)")
        createRecordingTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createRecordingTables(db)
    }

    /** Version 2: time series recordings, live or imported. See Recordings.kt. */
    private fun createRecordingTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE recordings (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER, " +
                "end_ms INTEGER, source TEXT, name TEXT, trip_id INTEGER)"
        )
        db.execSQL("CREATE TABLE channels (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, unit TEXT)")
        db.execSQL("CREATE UNIQUE INDEX channels_key ON channels(name, unit)")
        db.execSQL("CREATE TABLE samples (rec_id INTEGER, ch_id INTEGER, t_ms INTEGER, value REAL)")
        db.execSQL("CREATE INDEX samples_series ON samples(rec_id, ch_id, t_ms)")
    }

    private fun values(t: TripRow) = ContentValues().apply {
        put("start_ms", t.startMs)
        put("end_ms", t.endMs)
        put("distance_km", t.distanceKm)
        put("fuel_l", t.fuelL)
        put("cost", t.cost)
        put("drive_s", t.driveS)
        put("idle_s", t.idleS)
        put("max_speed", t.maxSpeed)
    }

    fun insert(t: TripRow): Long = writableDatabase.insert("trips", null, values(t))

    fun update(t: TripRow) {
        writableDatabase.update("trips", values(t), "id = ?", arrayOf(t.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("trips", "id = ?", arrayOf(id.toString()))
    }

    /** Trips that started in [fromMs, toMs), newest first. */
    fun between(fromMs: Long, toMs: Long): List<TripRow> {
        val list = mutableListOf<TripRow>()
        readableDatabase.rawQuery(
            "SELECT id, start_ms, end_ms, distance_km, fuel_l, cost, drive_s, idle_s, max_speed " +
                "FROM trips WHERE start_ms >= ? AND start_ms < ? ORDER BY start_ms DESC",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    TripRow(
                        id = c.getLong(0), startMs = c.getLong(1), endMs = c.getLong(2),
                        distanceKm = c.getDouble(3), fuelL = c.getDouble(4), cost = c.getDouble(5),
                        driveS = c.getDouble(6), idleS = c.getDouble(7), maxSpeed = c.getDouble(8),
                    )
                )
            }
        }
        return list
    }

    fun summary(period: Period): Summary {
        val (from, to) = period.range()
        return Summary.of(between(from, to))
    }

    companion object {
        @Volatile
        private var instance: TripStore? = null

        fun get(context: Context): TripStore =
            instance ?: synchronized(this) {
                instance ?: TripStore(context.applicationContext).also { instance = it }
            }

        /** Closes the database so a backup can replace its file. The app restarts right after. */
        fun closeForRestore() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}
