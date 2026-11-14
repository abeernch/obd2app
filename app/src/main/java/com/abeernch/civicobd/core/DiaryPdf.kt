package com.abeernch.civicobd.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The diary as a printable maintenance record, for a buyer or a workshop: a summary, then every entry in
 * date order with its odometer, place, cost, parts, the service jobs it covered and its photos.
 */
object DiaryPdf {

    private const val W = 595 // A4 in points
    private const val H = 842
    private const val M = 48f // margin
    private val CONTENT = W - 2 * M

    private val day = SimpleDateFormat("d MMMM yyyy", Locale.US)
    private val dayTime = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.US)

    fun write(
        out: File,
        entries: List<DiaryEntry>,
        jobNames: Map<String, String>,
        odometerNow: Double?,
        photo: (String) -> File,
        now: Long = System.currentTimeMillis(),
    ): File {
        val doc = PdfDocument()
        val w = Writer(doc)
        val list = entries.sortedWith(compareBy({ it.timeMs }, { it.id }))

        // ---- summary
        w.text("Maintenance record", size = 22f, bold = true, gapAfter = 4f)
        w.text("Honda Civic, 2005", size = 14f, color = GREY, gapAfter = 18f)
        val spent = list.mapNotNull { it.cost }.sum()
        val facts = listOfNotNull(
            "Prepared on ${day.format(Date(now))}",
            odometerNow?.let { "Odometer about ${"%,d".format(it.toLong())} km" },
            "${list.size} entr${if (list.size == 1) "y" else "ies"}" +
                (list.firstOrNull()?.let { " from ${day.format(Date(it.timeMs))} to ${day.format(Date(list.last().timeMs))}" } ?: ""),
            if (spent > 0) "Recorded spending Rs ${"%,d".format(spent.toLong())}" else null,
            list.sumOf { it.photos.size }.takeIf { it > 0 }?.let { "$it photos of receipts, parts and work" },
        )
        facts.forEach { w.text(it, size = 11f, gapAfter = 3f) }
        w.gap(14f)
        w.rule()

        // ---- entries
        for (e in list) {
            w.keepSpace(90f)
            w.gap(14f)
            val head = (if (e.hasTime) dayTime else day).format(Date(e.timeMs)) +
                (e.odometerKm?.let { "   ·   ${"%,d".format(it.toLong())} km" } ?: "")
            w.text(head, size = 10f, color = GREY, gapAfter = 2f)
            w.text(e.title.ifBlank { "Maintenance" }, size = 15f, bold = true, gapAfter = 4f)
            val where = listOfNotNull(e.shop.takeIf { it.isNotBlank() }, e.cost?.let { "Rs ${"%,d".format(it.toLong())}" })
            if (where.isNotEmpty()) w.text(where.joinToString("   ·   "), size = 11f, gapAfter = 6f)
            if (e.parts.isNotEmpty()) {
                w.text("Parts", size = 10f, bold = true, color = GREY, gapAfter = 2f)
                e.parts.forEach { p ->
                    val detail = DiaryText.partDetail(p)
                    w.text("•  ${p.name}" + (if (detail.isNotEmpty()) ",  $detail" else ""), size = 11f, indent = 8f, gapAfter = 2f)
                }
                w.gap(4f)
            }
            if (e.jobs.isNotEmpty()) {
                w.text("Service: " + e.jobs.joinToString(", ") { jobNames[it] ?: it }, size = 11f, gapAfter = 4f)
            }
            if (e.notes.isNotBlank()) w.text(e.notes.trim(), size = 11f, gapAfter = 6f)
            w.photos(e.photos.map(photo).filter { it.exists() })
            w.gap(8f)
            w.rule()
        }
        w.finish()
        out.parentFile?.mkdirs()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    private val GREY = Color.rgb(100, 100, 100)

    /** Lays text and photos down the page, starting a new page when one is full. */
    private class Writer(val doc: PdfDocument) {
        private var number = 0
        private var page: PdfDocument.Page? = null
        private var y = M

        private val canvas: Canvas get() = (page ?: newPage()).canvas

        private fun newPage(): PdfDocument.Page {
            page?.let { finishPage(it) }
            number++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, number).create())
            page = p
            y = M
            return p
        }

        private fun finishPage(p: PdfDocument.Page) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = GREY }
            p.canvas.drawText("Maintenance record, Honda Civic 2005", M, H - 24f, paint)
            val n = "Page $number"
            p.canvas.drawText(n, W - M - paint.measureText(n), H - 24f, paint)
            doc.finishPage(p)
        }

        fun finish() {
            page?.let { finishPage(it) }
            page = null
            if (number == 0) { newPage(); finish() }
        }

        fun keepSpace(h: Float) {
            if (page == null || y + h > H - M - 20) newPage()
        }

        fun gap(h: Float) { y += h }

        fun rule() {
            keepSpace(4f)
            canvas.drawLine(M, y, W - M, y, Paint().apply { color = Color.rgb(210, 210, 210); strokeWidth = 0.8f })
            y += 2f
        }

        fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK, indent: Float = 0f, gapAfter: Float = 0f) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = if (bold) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.SERIF
            }
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, paint, (CONTENT - indent).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(1.5f, 1f).build()
            // Line by line, so a long note may run on to the next page.
            for (line in 0 until layout.lineCount) {
                val h = (layout.getLineBottom(line) - layout.getLineTop(line)).toFloat()
                keepSpace(h)
                canvas.save()
                canvas.translate(M + indent, y - layout.getLineTop(line))
                canvas.clipRect(0f, layout.getLineTop(line).toFloat(), CONTENT, layout.getLineBottom(line).toFloat())
                layout.draw(canvas)
                canvas.restore()
                y += h
            }
            y += gapAfter
        }

        /** Photos two to a row, each scaled to fit its half of the width and at most 260 points tall. */
        fun photos(files: List<File>) {
            if (files.isEmpty()) return
            gap(4f)
            val cell = (CONTENT - 12f) / 2
            for (row in files.chunked(2)) {
                val bmps = row.mapNotNull { load(it) }
                if (bmps.isEmpty()) continue
                val sizes = bmps.map { b ->
                    val k = minOf(cell / b.width, 260f / b.height)
                    b.width * k to b.height * k
                }
                val h = sizes.maxOf { it.second }
                keepSpace(h + 8f)
                bmps.forEachIndexed { i, b ->
                    val x = M + i * (cell + 12f)
                    val (bw, bh) = sizes[i]
                    canvas.drawBitmap(b, null, RectF(x, y, x + bw, y + bh), Paint(Paint.FILTER_BITMAP_FLAG))
                    b.recycle()
                }
                y += h + 8f
            }
        }

        /** A photo at most 1000 px on its long side, which prints sharp at this size and keeps the file small. */
        private fun load(f: File): Bitmap? = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1000) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
