package com.abeernch.civicobd.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.abeernch.civicobd.MainActivity
import com.abeernch.civicobd.core.Period
import com.abeernch.civicobd.core.Summary
import com.abeernch.civicobd.core.TripStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home-screen widget: the last drive, today and this month, at a glance.
 * Refreshed by the background service whenever a trip is saved.
 */
class TripWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = TripStore.get(context)
        val last = Period.All.range().let { (a, b) -> store.between(a, b) }.firstOrNull()
        val today = store.summary(Period.Today)
        val month = store.summary(Period.Month)
        provideContent { Content(last?.let { Summary.of(listOf(it)) }, last?.startMs, today, month) }
    }

    @androidx.compose.runtime.Composable
    private fun Content(last: Summary?, lastStart: Long?, today: Summary, month: Summary) {
        val ink = ColorProvider(Color(0xFFF5F5F2))
        val quiet = ColorProvider(Color(0xFFA6A6AD))
        val accent = ColorProvider(Color(0xFFFF8000))
        val big = TextStyle(color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val small = TextStyle(color = quiet, fontSize = 12.sp)

        Column(
            GlanceModifier
                .fillMaxSize()
                .background(Color(0xFF0E0E0F))
                .cornerRadius(20.dp)
                .padding(14.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Civic", style = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.defaultWeight())
                val when_ = lastStart?.let { SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(it)) } ?: "no drives yet"
                Text("Last drive  $when_", style = TextStyle(color = accent, fontSize = 12.sp))
            }
            Spacer(GlanceModifier.height(8.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                Cell(fmt(last?.distanceKm, 1), "km", big, small)
                Cell(fmt(last?.kmPerL, 1), "km/L", big, small)
                Cell(fmt(last?.cost, 0), "PKR", big, small)
            }
            Spacer(GlanceModifier.height(10.dp))
            Text(
                "Today  ${fmt(today.distanceKm, 1)} km  ·  ${fmt(today.cost, 0)} PKR",
                style = TextStyle(color = ink, fontSize = 13.sp),
            )
            Text(
                "This month  ${fmt(month.distanceKm, 0)} km  ·  ${fmt(month.kmPerL, 1)} km/L  ·  ${fmt(month.cost, 0)} PKR",
                style = TextStyle(color = quiet, fontSize = 12.sp),
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun androidx.glance.layout.RowScope.Cell(value: String, unit: String, big: TextStyle, small: TextStyle) {
        Column(GlanceModifier.defaultWeight()) {
            Text(value, style = big)
            Text(unit, style = small)
        }
    }

    private fun fmt(v: Double?, decimals: Int) = if (v == null || v.isNaN()) "--" else "%.${decimals}f".format(v)

    companion object {
        suspend fun refresh(context: Context) {
            try {
                TripWidget().updateAll(context)
            } catch (e: Exception) {
                // No widget placed, or the launcher is busy. Nothing to do.
            }
        }
    }
}

class TripWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TripWidget()
}
