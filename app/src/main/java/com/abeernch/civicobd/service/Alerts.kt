package com.abeernch.civicobd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.abeernch.civicobd.MainActivity
import com.abeernch.civicobd.R
import com.abeernch.civicobd.core.AppSettings
import com.abeernch.civicobd.core.Dtc
import com.abeernch.civicobd.core.Maintenance
import com.abeernch.civicobd.core.TripSummary

/**
 * The three notifications besides the connection one: a summary when a drive ends,
 * a new fault code or check-engine light, and a service job falling due.
 * Tapping one opens the app on the matching tab (and drive, for a summary).
 */
object Alerts {

    private const val TRIPS = "trips"
    private const val CAR = "car_alerts"
    private const val ID_TRIP = 10
    private const val ID_CODES = 11
    private const val ID_SERVICE = 12

    /** Extras read by MainActivity to open the right place. */
    const val EXTRA_TAB = "open_tab"
    const val EXTRA_TRIP = "open_trip"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(TRIPS, "Trip summaries", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CAR, "Car alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun open(context: Context, tab: String, tripId: Long? = null, request: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TAB, tab)
        if (tripId != null) intent.putExtra(EXTRA_TRIP, tripId)
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun post(context: Context, id: Int, n: Notification) {
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(id, n)
        } catch (_: SecurityException) {
            // Notifications not allowed: nothing to do.
        }
    }

    /** "11.0 km · 11.3 km/L · Rs 381 · 24 min", and how it compares with the recent average. */
    fun tripEnded(context: Context, s: TripSummary, paused: Boolean = false) {
        val t = s.trip
        val (line, text) = s.notificationText()
        val more = if (paused) listOf(text, "The drive continues if you set off within 10 minutes.").filter { it.isNotBlank() }.joinToString(" ") else text
        val n = Notification.Builder(context, TRIPS)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(if (paused) "Parked" else "Drive finished")
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(if (more.isEmpty()) line else "$line\n$more"))
            .setContentIntent(open(context, "Trips", t.id, 20))
            .setAutoCancel(true)
            .build()
        post(context, ID_TRIP, n)
    }

    fun cancelTrip(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_TRIP)
    }

    /** Announces a newly lit check-engine light or codes not announced before. */
    fun codes(context: Context, settings: AppSettings, stored: List<Dtc>, pending: List<Dtc>, mil: Boolean?) {
        val seen = settings.codesNotified.split(",").filter { it.isNotBlank() }.toSet()
        val all = stored + pending
        val fresh = all.filter { it.code !in seen }
        val milNew = mil == true && !settings.milWasOn
        if (mil != null) settings.milWasOn = mil
        // Forget codes that have gone, so a code coming back is announced again.
        settings.codesNotified = all.joinToString(",") { it.code }
        if (fresh.isEmpty() && !milNew) return

        val title = when {
            milNew -> "Check-engine light is on"
            fresh.any { it in stored } -> "New fault code"
            else -> "New pending fault code"
        }
        val text = if (fresh.isNotEmpty()) fresh.joinToString("; ") { "${it.code}: ${it.description}" }
        else stored.joinToString("; ") { "${it.code}: ${it.description}" }.ifEmpty { "Open the Car tab to read the codes." }
        val n = Notification.Builder(context, CAR)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open(context, "Car", request = 21))
            .setAutoCancel(true)
            .build()
        post(context, ID_CODES, n)
    }

    /** Announces service jobs that have become due or nearly due, once per time they are done. */
    fun service(context: Context, settings: AppSettings, odometerKm: Double?) {
        val statuses = Maintenance.statuses(settings, odometerKm).filter { it.overdue || it.soon }
        if (statuses.isEmpty()) return
        val done = settings.serviceNotified.split(",").filter { it.isNotBlank() }.toMutableSet()
        fun tag(key: String, km: Double?, ms: Long?) = "$key@${km?.toLong() ?: 0}@${ms ?: 0}"
        val fresh = statuses.filter { tag(it.item.key, it.item.doneKm, it.item.doneMs) !in done }
        if (fresh.isEmpty()) return
        fresh.forEach { done += tag(it.item.key, it.item.doneKm, it.item.doneMs) }
        settings.serviceNotified = done.joinToString(",")

        val text = fresh.joinToString("; ") { "${it.item.name} ${Maintenance.dueText(it)}" }
        val n = Notification.Builder(context, CAR)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(if (fresh.size == 1) "Service due" else "${fresh.size} service jobs due")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open(context, "Car", request = 22))
            .setAutoCancel(true)
            .build()
        post(context, ID_SERVICE, n)
    }
}
