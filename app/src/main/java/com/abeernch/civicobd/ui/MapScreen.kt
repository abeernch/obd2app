package com.abeernch.civicobd.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.abeernch.civicobd.core.AppSettings
import com.abeernch.civicobd.core.ColorScale
import com.abeernch.civicobd.core.HeatCell
import com.abeernch.civicobd.core.Route
import com.abeernch.civicobd.core.RouteMetric
import com.abeernch.civicobd.core.RoutePoint
import com.abeernch.civicobd.core.ScaleMode
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which street map is drawn under routes. Both work without an account or key. */
enum class MapTiles(val label: String, val credit: String) {
    Esri("Esri gray", "Esri, HERE, Garmin, © OpenStreetMap contributors"),
    Osm("OpenStreetMap", "© OpenStreetMap contributors"),
}

/** Esri's quiet gray canvas maps, in a dark and a light version. Their tile URLs are zoom/row/column. */
private class EsriCanvas(dark: Boolean) : OnlineTileSourceBase(
    if (dark) "EsriDark" else "EsriLight", 1, 16, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/" + (if (dark) "World_Dark_Gray_Base" else "World_Light_Gray_Base") + "/MapServer/tile/"),
    "Esri, HERE, Garmin, © OpenStreetMap contributors",
) {
    override fun getTileURLString(index: Long): String =
        baseUrl + MapTileIndex.getZoom(index) + "/" + MapTileIndex.getY(index) + "/" + MapTileIndex.getX(index)
}

private fun tiles(dark: Boolean, kind: MapTiles) = when (kind) {
    MapTiles.Esri -> EsriCanvas(dark)
    MapTiles.Osm -> TileSourceFactory.MAPNIK
}

/** Turns the light OpenStreetMap map into a dim dark one: colours inverted, then darkened slightly. */
private val InvertDim = android.graphics.ColorMatrixColorFilter(
    android.graphics.ColorMatrix(
        floatArrayOf(
            -0.85f, 0f, 0f, 0f, 220f,
            0f, -0.85f, 0f, 0f, 220f,
            0f, 0f, -0.85f, 0f, 220f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

/** Applies the tile source; the OpenStreetMap map is colour-inverted in dark mode. */
private fun MapView.useTiles(dark: Boolean, kind: MapTiles) {
    val wanted = tiles(dark, kind)
    if (tileProvider.tileSource.name() != wanted.name()) setTileSource(wanted)
    overlayManager.tilesOverlay.setColorFilter(if (kind == MapTiles.Osm && dark) InvertDim else null)
}

private fun newMap(context: Context, dark: Boolean, kind: MapTiles): MapView = MapView(context).apply {
    useTiles(dark, kind)
    setMultiTouchControls(true)
    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
    isTilesScaledToDpi = true
    minZoomLevel = 4.0
    maxZoomLevel = 19.0
    isHorizontalMapRepetitionEnabled = false
    isVerticalMapRepetitionEnabled = false
}

/**
 * Draws the route as coloured segments plus markers, and handles press-and-hold scrubbing:
 * hold anywhere near the path, then slide along it; the nearest point is selected.
 */
private class RouteOverlay(
    private val density: Float,
    private val onSelect: (Int) -> Unit,
) : Overlay() {
    var points: List<RoutePoint> = emptyList()
    var colors: IntArray = IntArray(0)
    var stops: List<com.abeernch.civicobd.core.Stop> = emptyList()
    var harsh: List<com.abeernch.civicobd.core.Harsh> = emptyList()
    var selected: Int? = null
    var accent: Int = 0xFFFF8000.toInt()
    var ink: Int = 0xFFFFFFFF.toInt()

    private var scrubbing = false
    private val p0 = Point()
    private val p1 = Point()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || points.size < 2) return
        val proj = map.projection
        // A dark casing under the coloured line keeps it readable on any street colour.
        line.strokeWidth = 8f * density
        line.color = 0x99000000.toInt()
        proj.toPixels(GeoPoint(points[0].lat, points[0].lon), p0)
        for (i in 1 until points.size) {
            proj.toPixels(GeoPoint(points[i].lat, points[i].lon), p1)
            canvas.drawLine(p0.x.toFloat(), p0.y.toFloat(), p1.x.toFloat(), p1.y.toFloat(), line)
            p0.set(p1.x, p1.y)
        }
        line.strokeWidth = 5f * density
        proj.toPixels(GeoPoint(points[0].lat, points[0].lon), p0)
        for (i in 1 until points.size) {
            proj.toPixels(GeoPoint(points[i].lat, points[i].lon), p1)
            line.color = colors.getOrElse(i) { ink }
            canvas.drawLine(p0.x.toFloat(), p0.y.toFloat(), p1.x.toFloat(), p1.y.toFloat(), line)
            p0.set(p1.x, p1.y)
        }

        // Stops: hollow circles, larger for longer waits.
        ring.strokeWidth = 2f * density
        for (s in stops) {
            proj.toPixels(GeoPoint(s.lat, s.lon), p0)
            val r = (6f + (s.seconds / 30.0).coerceAtMost(10.0).toFloat()) * density
            fill.color = 0x66FFFFFF
            canvas.drawCircle(p0.x.toFloat(), p0.y.toFloat(), r, fill)
            ring.color = ink
            canvas.drawCircle(p0.x.toFloat(), p0.y.toFloat(), r, ring)
        }
        // Harsh braking (red) and hard acceleration (yellow).
        for (h in harsh) {
            proj.toPixels(GeoPoint(h.lat, h.lon), p0)
            fill.color = if (h.g < 0) 0xFFE5484D.toInt() else 0xFFF5C542.toInt()
            canvas.drawCircle(p0.x.toFloat(), p0.y.toFloat(), 5f * density, fill)
        }
        // Start: ring in the accent colour. End: solid accent dot.
        proj.toPixels(GeoPoint(points.first().lat, points.first().lon), p0)
        ring.color = accent; ring.strokeWidth = 3f * density
        canvas.drawCircle(p0.x.toFloat(), p0.y.toFloat(), 8f * density, ring)
        proj.toPixels(GeoPoint(points.last().lat, points.last().lon), p0)
        fill.color = accent
        canvas.drawCircle(p0.x.toFloat(), p0.y.toFloat(), 7f * density, fill)

        // Selected point
        selected?.let { i ->
            val p = points.getOrNull(i) ?: return@let
            proj.toPixels(GeoPoint(p.lat, p.lon), p0)
            fill.color = 0x55000000
            canvas.drawCircle(p0.x.toFloat(), p0.y.toFloat(), 14f * density, fill)
            ring.color = ink; ring.strokeWidth = 3f * density
            canvas.drawCircle(p0.x.toFloat(), p0.y.toFloat(), 11f * density, ring)
            fill.color = accent
            canvas.drawCircle(p0.x.toFloat(), p0.y.toFloat(), 5f * density, fill)
        }
    }

    private fun nearest(e: MotionEvent, map: MapView, maxPx: Float): Int? {
        val proj = map.projection
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in points.indices) {
            proj.toPixels(GeoPoint(points[i].lat, points[i].lon), p0)
            val dx = p0.x - e.x
            val dy = p0.y - e.y
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best >= 0 && bestD <= maxPx * maxPx) best else null
    }

    override fun onLongPress(e: MotionEvent, map: MapView): Boolean {
        // Holding anywhere picks the nearest point on the route, so it is easy to hit with a thumb.
        val i = nearest(e, map, Float.MAX_VALUE) ?: return false
        scrubbing = true
        selected = i
        onSelect(i)
        map.invalidate()
        return true
    }

    override fun onTouchEvent(e: MotionEvent, map: MapView): Boolean {
        if (!scrubbing) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> nearest(e, map, Float.MAX_VALUE)?.let {
                if (it != selected) {
                    selected = it
                    onSelect(it)
                    map.invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scrubbing = false
        }
        return true
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val i = nearest(e, map, 32f * density) ?: return false
        selected = i
        onSelect(i)
        map.invalidate()
        return true
    }
}

/** One trip on the map, coloured by the chosen metric, with a readout for the held point. */
@Composable
fun RouteMapScreen(
    points: List<RoutePoint>,
    title: String,
    settings: AppSettings,
    onBack: () -> Unit,
    onExport: (gpx: Boolean, colorScale: ColorScale, range: Pair<Double, Double>) -> Unit,
) {
    BackHandler(onBack = onBack)
    var metric by remember { mutableStateOf(RouteMetric.Economy) }
    var scale by remember { mutableStateOf(ColorScale.entries.firstOrNull { it.name == settings.routeScale } ?: ColorScale.Parula) }
    var mode by remember { mutableStateOf(ScaleMode.Relative) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var tileKind by remember { mutableStateOf(MapTiles.entries.firstOrNull { it.name == settings.mapTiles } ?: MapTiles.Esri) }

    val range = remember(points, metric, mode) { Route.range(points, metric, mode) }
    val colors = remember(points, metric, scale, range) {
        IntArray(points.size) { i ->
            val p = points[i]
            when {
                metric == RouteMetric.Economy && p.fuelCut -> Route.FUEL_CUT_COLOR
                else -> metric.of(p)?.let { Route.color(scale, Route.position(it, range, metric)) } ?: 0xFF8C8C92.toInt()
            }
        }
    }
    val stops = remember(points) { Route.stops(points) }
    val harsh = remember(points) { Route.harsh(points) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Back", style = Type.body.copy(color = Palette.Accent),
                modifier = Modifier.pressable(onClick = onBack).padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
            )
            Text(title, style = Type.title, modifier = Modifier.weight(1f), maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().weight(0.58f).clipToBounds()) {
            RouteMap(points, colors, stops, harsh, selected, tileKind) { selected = it }
            Text(
                tileKind.credit,
                style = Type.label.copy(fontSize = Type.sz(10f), color = Palette.Label),
                modifier = Modifier.align(Alignment.BottomEnd).background(Palette.Background.copy(alpha = 0.6f)).padding(4.dp),
            )
            if (selected == null) {
                Text(
                    "Hold on the route and slide to read it",
                    style = Type.label.copy(color = Palette.Value),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.Background.copy(alpha = 0.75f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(0.42f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            selected?.let { PointReadout(points, it) }
            Chips(RouteMetric.entries.toList(), metric, { it.label }) { metric = it }
            Chips(ColorScale.entries.toList(), scale, { it.label }) { scale = it; settings.routeScale = it.name }
            Chips(ScaleMode.entries.toList(), mode, { it.label }) { mode = it }
            Legend(scale, range, metric)
            Chips(MapTiles.entries.toList(), tileKind, { it.label }) { tileKind = it; settings.mapTiles = it.name }
            if (points.any { it.altitude != null }) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("Elevation", style = Type.section)
                    Spacer(Modifier.height(6.dp))
                    ElevationProfile(points, selected) { selected = it }
                }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    "${stops.size} stops  ·  ${harsh.count { it.g < 0 }} hard brakes  ·  ${harsh.count { it.g > 0 }} hard accelerations",
                    style = Type.label,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { OutlineButton("Export GPX") { onExport(true, scale, range) } }
                    Box(Modifier.weight(1f)) { OutlineButton("Export KML") { onExport(false, scale, range) } }
                }
            }
        }
    }
}

@Composable
private fun RouteMap(
    points: List<RoutePoint>,
    colors: IntArray,
    stops: List<com.abeernch.civicobd.core.Stop>,
    harsh: List<com.abeernch.civicobd.core.Harsh>,
    selected: Int?,
    tileKind: MapTiles,
    onSelect: (Int) -> Unit,
) {
    val dark = Palette.isDark
    val accent = Palette.Accent
    val ink = Palette.Value
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    DisposableEffect(Unit) { onDispose { mapRef?.onDetach() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            newMap(ctx, dark, tileKind).also { map ->
                val overlay = RouteOverlay(ctx.resources.displayMetrics.density, onSelect)
                map.overlays.add(overlay)
                map.tag = overlay
                mapRef = map
                if (points.size >= 2) {
                    map.post {
                        val box = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.lat, it.lon) })
                        map.zoomToBoundingBox(box.increaseByScale(1.15f), false)
                    }
                }
            }
        },
        update = { map ->
            val overlay = map.tag as RouteOverlay
            overlay.points = points
            overlay.colors = colors
            overlay.stops = stops
            overlay.harsh = harsh
            overlay.selected = selected
            overlay.accent = accent.toArgbInt()
            overlay.ink = ink.toArgbInt()
            map.useTiles(dark, tileKind)
            map.invalidate()
        },
    )
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

@Composable
private fun PointReadout(points: List<RoutePoint>, i: Int) {
    val p = points.getOrNull(i) ?: return
    val start = points.first().t
    val into = (p.t - start) / 1000
    val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(p.t))
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Surface)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock, style = Type.body.copy(color = Palette.Accent), modifier = Modifier.weight(1f))
            Text("%d:%02d into trip  ·  %s km".format(into / 60, into % 60, fmt(p.km, 2)), style = Type.label)
        }
        Row(Modifier.fillMaxWidth()) {
            Tile("Speed", fmt(p.speed, 0), "km/h", Modifier.weight(1f))
            Tile(
                if (p.fuelCut) "Fuel cut" else "Economy",
                if (p.fuelCut) "0.0" else fmt(p.kmPerL), if (p.fuelCut) "L/h" else "km/L", Modifier.weight(1f),
                color = if (p.fuelCut) Color(Route.FUEL_CUT_COLOR) else Palette.Value,
            )
            Tile("Gear", p.gear?.let { if (it < 1) "N" else fmt(it, 0) } ?: "--", "", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Tile("RPM", fmt(p.rpm, 0), "", Modifier.weight(1f))
            Tile("Throttle", fmt(p.throttle, 0), "%", Modifier.weight(1f))
            Tile("Altitude", fmt(p.altitude, 0), "m", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Legend(scale: ColorScale, range: Pair<Double, Double>, metric: RouteMetric) {
    val stops = (0..10).map { Color(Route.color(scale, it / 10f)) }
    // Left end of the bar is "poor", right end "good"; label the matching values.
    val left = if (metric.highIsGood) range.first else range.second
    val right = if (metric.highIsGood) range.second else range.first
    Column(Modifier.padding(horizontal = 20.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Brush.horizontalGradient(stops))
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${fmt(left, 1)} ${metric.unit}", style = Type.label, modifier = Modifier.weight(1f))
            if (metric == RouteMetric.Economy) {
                Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(Route.FUEL_CUT_COLOR)))
                Text("  fuel cut", style = Type.label)
                Spacer(Modifier.weight(1f))
            }
            Text("${fmt(right, 1)} ${metric.unit}", style = Type.label)
        }
    }
}

/** Altitude against distance. Tap or slide to move the map marker. */
@Composable
private fun ElevationProfile(points: List<RoutePoint>, selected: Int?, onSelect: (Int) -> Unit) {
    val withAlt = remember(points) { points.withIndex().filter { it.value.altitude != null } }
    if (withAlt.size < 2) return
    val total = points.last().km.coerceAtLeast(0.001)
    val lo = withAlt.minOf { it.value.altitude!! }
    val hi = withAlt.maxOf { it.value.altitude!! }.let { if (it - lo < 5) lo + 5 else it }
    fun pick(x: Float, width: Float) {
        val km = (x / width).coerceIn(0f, 1f) * total
        val best = withAlt.minByOrNull { kotlin.math.abs(it.value.km - km) } ?: return
        onSelect(best.index)
    }
    val ink = Palette.Value
    val grid = Palette.Hairline
    val accent = Palette.Accent
    ComposeCanvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .pointerInput(points) { detectTapGestures { pick(it.x, size.width.toFloat()) } }
            .pointerInput(points) { detectHorizontalDragGestures { c, _ -> pick(c.position.x, size.width.toFloat()) } }
    ) {
        fun x(km: Double) = (km / total * size.width).toFloat()
        fun y(a: Double) = (size.height - (a - lo) / (hi - lo) * size.height * 0.9 - size.height * 0.05).toFloat()
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        val path = Path()
        withAlt.forEachIndexed { k, iv ->
            val px = x(iv.value.km); val py = y(iv.value.altitude!!)
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, ink, style = Stroke(width = 2.dp.toPx()))
        selected?.let { i ->
            val p = points.getOrNull(i) ?: return@let
            val px = x(p.km)
            drawLine(accent, Offset(px, 0f), Offset(px, size.height), 1.5.dp.toPx())
            p.altitude?.let { drawCircle(accent, 4.dp.toPx(), Offset(px, y(it))) }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text("${fmt(lo, 0)} to ${fmt(hi, 0)} m", style = Type.label, modifier = Modifier.weight(1f))
        Text("${fmt(total, 1)} km", style = Type.label)
    }
}

/** Economy of every road stretch across all drives. Tap a dot to read it. */
@Composable
fun HeatMapScreen(cells: List<HeatCell>, settings: AppSettings, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var scale by remember { mutableStateOf(ColorScale.entries.firstOrNull { it.name == settings.routeScale } ?: ColorScale.Parula) }
    var picked by remember { mutableStateOf<HeatCell?>(null) }
    val range = 6.0 to 20.0
    val dark = Palette.isDark
    val tileKind = MapTiles.entries.firstOrNull { it.name == settings.mapTiles } ?: MapTiles.Esri
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    DisposableEffect(Unit) { onDispose { mapRef?.onDetach() } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Back", style = Type.body.copy(color = Palette.Accent),
                modifier = Modifier.pressable(onClick = onBack).padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
            )
            Text("Fuel map, all drives", style = Type.title)
        }
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            if (cells.isEmpty()) {
                Text(
                    "No drives with GPS yet. Routes are recorded once location is allowed.",
                    style = Type.label, modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        newMap(ctx, dark, tileKind).also { map ->
                            val d = ctx.resources.displayMetrics.density
                            map.overlays.add(HeatOverlay(d) { picked = it })
                            mapRef = map
                            map.post {
                                val box = BoundingBox.fromGeoPoints(cells.map { GeoPoint(it.lat, it.lon) })
                                map.zoomToBoundingBox(box.increaseByScale(1.15f), false)
                            }
                        }
                    },
                    update = { map ->
                        (map.overlays.firstOrNull { it is HeatOverlay } as? HeatOverlay)?.let {
                            it.cells = cells
                            it.colors = IntArray(cells.size) { i -> Route.color(scale, Route.position(cells[i].kmPerL, range, RouteMetric.Economy)) }
                        }
                        map.invalidate()
                    },
                )
            }
            Text(
                tileKind.credit,
                style = Type.label.copy(fontSize = Type.sz(10f), color = Palette.Label),
                modifier = Modifier.align(Alignment.BottomEnd).background(Palette.Background.copy(alpha = 0.6f)).padding(4.dp),
            )
        }
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            picked?.let {
                Text(
                    "This stretch: ${fmt(it.kmPerL)} km/L over ${fmt(it.km, 1)} km driven",
                    style = Type.body, modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Chips(ColorScale.entries.toList(), scale, { it.label }) { scale = it; settings.routeScale = it.name }
            Legend(scale, range, RouteMetric.Economy)
        }
    }
}

private class HeatOverlay(private val density: Float, private val onPick: (HeatCell) -> Unit) : Overlay() {
    var cells: List<HeatCell> = emptyList()
    var colors: IntArray = IntArray(0)
    private val p = Point()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        // Dot size follows zoom so stretches stay joined up when zoomed in.
        val r = (map.zoomLevelDouble - 10).coerceIn(1.0, 8.0).toFloat() * density
        for (i in cells.indices) {
            proj.toPixels(GeoPoint(cells[i].lat, cells[i].lon), p)
            paint.color = colors.getOrElse(i) { 0xFFFFFFFF.toInt() }
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, paint)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val proj = map.projection
        var best: HeatCell? = null
        var bestD = (28f * density) * (28f * density)
        for (c in cells) {
            proj.toPixels(GeoPoint(c.lat, c.lon), p)
            val d = (p.x - e.x) * (p.x - e.x) + (p.y - e.y) * (p.y - e.y)
            if (d < bestD) { bestD = d; best = c }
        }
        best?.let { onPick(it) }
        return best != null
    }
}
