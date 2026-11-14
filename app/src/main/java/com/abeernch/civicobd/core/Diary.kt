package com.abeernch.civicobd.core

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File
import java.io.InputStream

/** A part fitted: what it is, its make, and its part number. */
data class Part(val name: String, val brand: String = "", val number: String = "")

/**
 * One entry in the maintenance diary: what was done, on what day (the time is optional), at what
 * odometer, where and for how much, with the parts fitted, the service jobs it covers, and photos
 * (receipts, parts, the work).
 */
data class DiaryEntry(
    val id: Long = -1,
    val timeMs: Long,
    val hasTime: Boolean = false,
    val title: String,
    val notes: String = "",
    val odometerKm: Double? = null,
    val cost: Double? = null,
    val shop: String = "",
    val parts: List<Part> = emptyList(),
    val jobs: List<String> = emptyList(),
    val photos: List<String> = emptyList(),
)

/** The diary's text formats and rules, apart from storage, so they can be checked on their own. */
object DiaryText {

    /** Tabs and line breaks would break the stored format, so they become spaces. */
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    /** One part per line, its name, make and number separated by tabs. Parts with no name are dropped. */
    fun encodeParts(parts: List<Part>): String =
        parts.filter { it.name.isNotBlank() }.joinToString("\n") { "${clean(it.name)}\t${clean(it.brand)}\t${clean(it.number)}" }

    fun decodeParts(text: String?): List<Part> =
        text.orEmpty().lines().filter { it.isNotBlank() }.map {
            val f = it.split('\t')
            Part(f[0], f.getOrElse(1) { "" }, f.getOrElse(2) { "" })
        }

    fun encodeList(items: List<String>): String = items.map(::clean).filter { it.isNotEmpty() }.joinToString("\n")
    fun decodeList(text: String?): List<String> = text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** "Bosch · 0 986 AF0 123" for a part's make and number, or what of them there is. */
    fun partDetail(p: Part): String = listOf(p.brand, p.number).filter { it.isNotBlank() }.joinToString(" · ")
}

/** The maintenance diary, kept in the trips database, its photos in the app's own files. */
class Diary(private val store: TripStore, private val photoDir: File) {

    init {
        photoDir.mkdirs()
    }

    fun all(): List<DiaryEntry> {
        val out = mutableListOf<DiaryEntry>()
        store.readableDatabase.rawQuery(
            "SELECT id, time_ms, has_time, title, notes, odometer, cost, shop, parts, jobs, photos FROM diary ORDER BY time_ms DESC, id DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += DiaryEntry(
                    id = c.getLong(0),
                    timeMs = c.getLong(1),
                    hasTime = c.getInt(2) == 1,
                    title = c.getString(3).orEmpty(),
                    notes = c.getString(4).orEmpty(),
                    odometerKm = if (c.isNull(5)) null else c.getDouble(5),
                    cost = if (c.isNull(6)) null else c.getDouble(6),
                    shop = c.getString(7).orEmpty(),
                    parts = DiaryText.decodeParts(c.getString(8)),
                    jobs = DiaryText.decodeList(c.getString(9)),
                    photos = DiaryText.decodeList(c.getString(10)),
                )
            }
        }
        return out
    }

    /** Saves a new entry or a changed one; returns it with its id. Photos taken out of it are deleted. */
    fun save(entry: DiaryEntry): DiaryEntry {
        val v = ContentValues().apply {
            put("time_ms", entry.timeMs)
            put("has_time", if (entry.hasTime) 1 else 0)
            put("title", entry.title.trim())
            put("notes", entry.notes.trim())
            if (entry.odometerKm != null) put("odometer", entry.odometerKm) else putNull("odometer")
            if (entry.cost != null) put("cost", entry.cost) else putNull("cost")
            put("shop", entry.shop.trim())
            put("parts", DiaryText.encodeParts(entry.parts))
            put("jobs", DiaryText.encodeList(entry.jobs))
            put("photos", DiaryText.encodeList(entry.photos))
        }
        val db = store.writableDatabase
        return if (entry.id < 0) {
            entry.copy(id = db.insert("diary", null, v))
        } else {
            val before = all().firstOrNull { it.id == entry.id }
            db.update("diary", v, "id = ?", arrayOf(entry.id.toString()))
            before?.photos?.filter { it !in entry.photos }?.forEach { photo(it).delete() }
            entry
        }
    }

    fun delete(entry: DiaryEntry) {
        store.writableDatabase.delete("diary", "id = ?", arrayOf(entry.id.toString()))
        entry.photos.forEach { photo(it).delete() }
    }

    fun photo(name: String): File = File(photoDir, File(name).name)

    /**
     * Keeps a photo: turned upright (as the camera tagged it), at most 2000 px on its long side, as JPEG.
     * [open] is called twice (once to read its size and turn, once to decode). Returns its file name.
     */
    fun addPhoto(open: () -> InputStream): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "That is not a picture" }
        val rotation = runCatching {
            open().use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrDefault(0f)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2000) sample *= 2
        var bmp = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IllegalArgumentException("That picture could not be read")
        val long = maxOf(bmp.width, bmp.height)
        if (long > 2000) {
            val k = 2000f / long
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        }
        if (rotation != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        val name = "p_${System.currentTimeMillis()}_${(Math.random() * 1e6).toInt()}.jpg"
        photo(name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return name
    }

    /** Photos no entry refers to (a form left without saving), removed. */
    fun tidy() {
        val used = all().flatMap { it.photos }.toSet()
        photoDir.listFiles()?.filter { it.name !in used && System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
    }

    companion object {
        fun dir(context: android.content.Context) = File(context.filesDir, "diary")

        /** The table, made when first needed; an older build simply leaves it alone. */
        internal const val CREATE = "CREATE TABLE IF NOT EXISTS diary (id INTEGER PRIMARY KEY AUTOINCREMENT, time_ms INTEGER, " +
            "has_time INTEGER, title TEXT, notes TEXT, odometer REAL, cost REAL, shop TEXT, parts TEXT, jobs TEXT, photos TEXT)"
    }
}
