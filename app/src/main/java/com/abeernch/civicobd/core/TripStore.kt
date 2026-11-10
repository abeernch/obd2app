package com.abeernch.civicobd.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One drive, from engine start to engine off, or one added by hand that the app did not record. */
data class TripRow(
    val id: Long = -1,
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Double = 0.0,
    val fuelL: Double = 0.0,
    val cost: Double = 0.0,     // what its fuel cost, PKR
    val driveS: Double = 0.0,   // seconds moving
    val idleS: Double = 0.0,    // seconds standing with the engine running
    val maxSpeed: Double = 0.0,
    val manual: Boolean = false,        // added by hand: its figures were entered, not measured
    val fuelEstimated: Boolean = false, // added by hand with its fuel worked out from recent economy
    val note: String? = null,           // added by hand: who drove, or why it was not recorded
    val odoStart: Double? = null,       // odometer readings entered with a drive added by hand
    val odoEnd: Double? = null,
    val shared: Double = 0.0,           // carpooling share: what passengers paid towards it, PKR
    val passengers: Int = 0,
) {
    /** What the drive cost after the carpooling share. Below zero when the share came to more than the fuel. */
    val netCost: Double get() = cost - shared
}

/**
 * Totals over any set of trips. Distance, fuel, cost and time count every drive; economy and average
 * speed come from recorded drives only, so a drive added by hand (its fuel an estimate) never moves them.
 */
data class Summary(
    val trips: Int = 0,
    val distanceKm: Double = 0.0,
    val fuelL: Double = 0.0,
    val cost: Double = 0.0,
    val driveS: Double = 0.0,
    val idleS: Double = 0.0,
    val shared: Double = 0.0,   // carpooling share, PKR
    val handAdded: Int = 0,     // drives added by hand among them
    val measuredKm: Double = distanceKm,
    val measuredFuelL: Double = fuelL,
    val measuredS: Double = driveS + idleS,
) {
    val totalS: Double get() = driveS + idleS
    val netCost: Double get() = cost - shared
    val kmPerL: Double? get() = if (measuredFuelL > 0.01 && measuredKm > 0.05) measuredKm / measuredFuelL else null
    val avgSpeed: Double? get() = if (measuredS > 10) measuredKm / measuredS * 3600 else null

    companion object {
        fun of(rows: List<TripRow>): Summary {
            val recorded = rows.filter { !it.manual }
            return Summary(
                trips = rows.size,
                distanceKm = rows.sumOf { it.distanceKm },
                fuelL = rows.sumOf { it.fuelL },
                cost = rows.sumOf { it.cost },
                driveS = rows.sumOf { it.driveS },
                idleS = rows.sumOf { it.idleS },
                shared = rows.sumOf { it.shared },
                handAdded = rows.size - recorded.size,
                measuredKm = recorded.sumOf { it.distanceKm },
                measuredFuelL = recorded.sumOf { it.fuelL },
                measuredS = recorded.sumOf { it.driveS + it.idleS },
            )
        }
    }
}

/** Local SQLite database of trips. Nothing leaves the phone. */
class TripStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "trips.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE trips (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER, end_ms INTEGER, " +
                "distance_km REAL, fuel_l REAL, cost REAL, drive_s REAL, idle_s REAL, max_speed REAL)"
        )
        db.execSQL("CREATE INDEX trips_start ON trips(start_ms)")
        createRecordingTables(db)
        createFuelAndEventTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createRecordingTables(db)
        if (oldVersion < 3) createFuelAndEventTables(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Columns added since version 3 without changing the version, so an older build still opens this
        // database (it reads and writes only the columns it knows, and leaves these alone).
        if (!db.isReadOnly) {
            addColumnIfMissing(db, "fillups", "factor_before", "REAL")
            addColumnIfMissing(db, "trips", "manual", "INTEGER")
            addColumnIfMissing(db, "trips", "fuel_est", "INTEGER")
            addColumnIfMissing(db, "trips", "note", "TEXT")
            addColumnIfMissing(db, "trips", "odo_start", "REAL")
            addColumnIfMissing(db, "trips", "odo_end", "REAL")
            addColumnIfMissing(db, "trips", "shared", "REAL")
            addColumnIfMissing(db, "trips", "passengers", "INTEGER")
        }
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, type: String) {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            while (c.moveToNext()) if (c.getString(1) == column) return
        }
        db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
    }

    /** Version 3: fill-ups (see Fuel.kt) and car events such as engine starts and fault codes (see Events.kt). */
    private fun createFuelAndEventTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE fillups (id INTEGER PRIMARY KEY AUTOINCREMENT, time_ms INTEGER, litres REAL, " +
                "price REAL, full INTEGER, odometer REAL, app_litres REAL, factor_after REAL)"
        )
        db.execSQL(
            "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, time_ms INTEGER, type TEXT, " +
                "a REAL, b REAL, text TEXT)"
        )
        db.execSQL("CREATE INDEX events_type ON events(type, time_ms)")
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

    /** The figures a drive is recorded with: all that [update] ever writes. */
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

    /** Everything, the details entered by hand and the carpooling share included. */
    private fun allValues(t: TripRow) = values(t).apply {
        put("manual", if (t.manual) 1 else 0)
        put("fuel_est", if (t.fuelEstimated) 1 else 0)
        if (t.note != null) put("note", t.note) else putNull("note")
        if (t.odoStart != null) put("odo_start", t.odoStart) else putNull("odo_start")
        if (t.odoEnd != null) put("odo_end", t.odoEnd) else putNull("odo_end")
        put("shared", t.shared)
        put("passengers", t.passengers)
    }

    fun insert(t: TripRow): Long = writableDatabase.insert("trips", null, allValues(t))

    /**
     * Writes a drive's recorded figures. The service saves the drive in progress this way every few
     * seconds, and merging writes the joined figures, so the carpooling share and anything entered by
     * hand are never overwritten by them.
     */
    fun update(t: TripRow) {
        writableDatabase.update("trips", values(t), "id = ?", arrayOf(t.id.toString()))
    }

    /** Saves a corrected drive added by hand, everything included. */
    fun updateManual(t: TripRow) {
        require(t.manual) { "Only drives added by hand are corrected this way" }
        writableDatabase.update("trips", allValues(t), "id = ?", arrayOf(t.id.toString()))
    }

    /** A drive's carpooling share, PKR, and how many passengers paid it. Zero clears it. */
    fun setShare(id: Long, shared: Double, passengers: Int) {
        val v = ContentValues().apply {
            put("shared", shared.coerceAtLeast(0.0))
            put("passengers", passengers.coerceAtLeast(0))
        }
        writableDatabase.update("trips", v, "id = ?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("trips", "id = ?", arrayOf(id.toString()))
    }

    /** Trips that started in [fromMs, toMs), newest first. */
    fun byId(id: Long): TripRow? = between(0, Long.MAX_VALUE).firstOrNull { it.id == id }

    fun between(fromMs: Long, toMs: Long): List<TripRow> {
        val list = mutableListOf<TripRow>()
        readableDatabase.rawQuery(
            "SELECT id, start_ms, end_ms, distance_km, fuel_l, cost, drive_s, idle_s, max_speed, " +
                "COALESCE(manual, 0), COALESCE(fuel_est, 0), note, odo_start, odo_end, " +
                "COALESCE(shared, 0), COALESCE(passengers, 0) " +
                "FROM trips WHERE start_ms >= ? AND start_ms < ? ORDER BY start_ms DESC",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    TripRow(
                        id = c.getLong(0), startMs = c.getLong(1), endMs = c.getLong(2),
                        distanceKm = c.getDouble(3), fuelL = c.getDouble(4), cost = c.getDouble(5),
                        driveS = c.getDouble(6), idleS = c.getDouble(7), maxSpeed = c.getDouble(8),
                        manual = c.getInt(9) == 1, fuelEstimated = c.getInt(10) == 1,
                        note = if (c.isNull(11)) null else c.getString(11),
                        odoStart = if (c.isNull(12)) null else c.getDouble(12),
                        odoEnd = if (c.isNull(13)) null else c.getDouble(13),
                        shared = c.getDouble(14), passengers = c.getInt(15),
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
