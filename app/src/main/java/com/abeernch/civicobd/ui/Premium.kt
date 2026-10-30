package com.abeernch.civicobd.ui

import android.graphics.BlurMaskFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RawRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.abeernch.civicobd.R
import kotlinx.coroutines.launch

/**
 * The Drive tab's quiet instrument look: near-black surfaces, hairlines and one warm accent
 * (the chosen accent colour, gold by default). Light mode keeps the same structure in the light palette.
 */
object Lux {
    val dark: Boolean get() = Palette.isDark
    val Txt: Color get() = if (dark) Color(0xFFECEEF1) else Palette.Value
    val Sub: Color get() = if (dark) Color(0xFF8A919C) else Palette.Label
    val Dim: Color get() = if (dark) Color(0xFF6F7783) else Palette.Label
    val Faint: Color get() = if (dark) Color(0xFF565D68) else Color(0xFF8C8C93)
    val Line: Color get() = if (dark) Color(0xFF1B1F26) else Palette.Hairline
    val Gold: Color get() = Palette.Accent
    val GoldSoft: Color get() = if (dark) lerp(Palette.Accent, Color.White, 0.36f) else Palette.Accent
    val Cut: Color get() = if (dark) Color(0xFF6FC3D6) else Color(0xFF0A7FA3)
    val Red: Color get() = economyColor(0.0)
    val Amber: Color get() = economyColor(13.0)
    val Green: Color get() = economyColor(30.0)

    /** Economy colour, or the secondary text colour when there is no reading. */
    fun eco(kmPerL: Double?): Color = if (kmPerL == null) Sub else economyColor(kmPerL)

    /** The page: a soft lift of dark grey behind the instruments, falling to black at the edges. */
    fun DrawScope.drawLuxPage(alpha: Float = 1f, centreY: Float = 0.34f, inner: Color = Color(0xFF0B0E12), mid: Color = Color(0xFF050608)) {
        if (!dark || alpha <= 0f) return
        val c = Offset(size.width * 0.5f, size.height * centreY)
        val ry = size.height * 0.7f
        val k = (size.width * 1.2f) / ry
        scale(scaleX = k, scaleY = 1f, pivot = c) {
            drawRect(
                brush = Brush.radialGradient(0f to inner, 0.58f to mid, 1f to Color.Black, center = c, radius = ry),
                topLeft = Offset(c.x - c.x / k, 0f),
                size = Size(size.width / k, size.height),
                alpha = alpha,
            )
        }
    }
}

/** Saira at an exact size, independent of the chosen face. */
fun lux(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = Lux.Txt, spacing: TextUnit = 0.sp) =
    TextStyle(fontFamily = Instrument, fontSize = size, fontWeight = weight, color = color, letterSpacing = spacing, fontFeatureSettings = "tnum")

// ---------------------------------------------------------------- slanted black buttons

/** A rectangle whose left and right sides lean, like a parallelogram. */
class SlantShape(private val cut: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }.coerceAtMost(size.width / 3)
        return Outline.Generic(slantPath(size, c))
    }
}

private fun slantPath(size: Size, c: Float, dy: Float = 0f) = Path().apply {
    moveTo(c, dy)
    lineTo(size.width, dy)
    lineTo(size.width - c, size.height + dy)
    lineTo(0f, size.height + dy)
    close()
}

private val PressEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val LightEase = CubicBezierEasing(0.3f, 0.6f, 0.2f, 1f)

/** The flat gloss-black face: a dark gradient, a faint gloss on the upper half, a light top line and a fainter bottom one. */
private fun DrawScope.glossFace(lines: Boolean = true) {
    drawRect(Brush.verticalGradient(0f to Color(0xFF101114), 0.52f to Color(0xFF08090B), 1f to Color(0xFF040405)))
    drawRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.04f), 0.49f to Color.White.copy(alpha = 0.012f),
            0.5f to Color.Transparent, 1f to Color.Transparent,
        )
    )
    if (!lines) return
    val px = 1.dp.toPx()
    fun line(alpha: Float, y: Float) = drawRect(
        Brush.horizontalGradient(
            0.05f to Color.Transparent, 0.30f to Color.White.copy(alpha = alpha),
            0.70f to Color.White.copy(alpha = alpha), 0.95f to Color.Transparent,
        ),
        topLeft = Offset(0f, y), size = Size(size.width, px),
    )
    line(0.16f, 0f)
    line(0.07f, size.height - px)
}

/** One pass of light after a press: a faint band across the gloss and a thin light running along the top line. */
private fun DrawScope.pressLight(run: Float) {
    if (run <= 0f || run >= 1f) return
    val w = size.width
    val h = size.height
    // The band: 30 % of the width, leaning like the sides, from off the left edge to off the right.
    val bw = w * 0.30f
    val x0 = (-1.7f + 5.9f * run) * bw
    val lean = h * 1.6f * 0.325f / 2f // tan(18°) across the band's height, which overhangs top and bottom
    val band = Path().apply {
        moveTo(x0 + lean, -h * 0.3f)
        lineTo(x0 + bw + lean, -h * 0.3f)
        lineTo(x0 + bw - lean, h * 1.3f)
        lineTo(x0 - lean, h * 1.3f)
        close()
    }
    drawPath(band, Brush.horizontalGradient(0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.10f), 1f to Color.Transparent, startX = x0 - lean, endX = x0 + bw + lean))
    // The edge light: 32 % wide, one line tall.
    val ew = w * 0.32f
    val ex = (-1.1f + 4.7f * run) * ew
    drawRect(
        Brush.horizontalGradient(0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.85f), 1f to Color.Transparent, startX = ex, endX = ex + ew),
        topLeft = Offset(ex, 0f), size = Size(ew, 1.dp.toPx()),
    )
}

/** A soft drop shadow under a slanted face. Needs Android 9 for the blur; older phones go without. */
private fun DrawScope.slantShadow(cut: Float, offsetY: Float, blur: Float, alpha: Float) {
    if (Build.VERSION.SDK_INT < 28 || !Palette.isDark) return
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.Black.copy(alpha = alpha).toArgb()
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.nativeCanvas.drawPath(slantPath(size, cut, offsetY).asAndroidPath(), paint)
    }
}

/**
 * The app's button for the Drive tab: black, flat, slanted sides, light gloss, lines only along the top
 * and bottom, and a soft shadow. A press settles it by a pixel, tightens the shadow, and sends one pass
 * of light across it, with a light haptic tick.
 */
@Composable
fun SlantButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cut: Dp = 12.dp,
    description: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, tween(200, easing = PressEase), label = "press")
    val run = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(source) {
        source.interactions.collect { i ->
            if (i is PressInteraction.Press) scope.launch {
                run.snapTo(0f)
                run.animateTo(1f, tween(600, easing = LightEase))
            }
        }
    }
    val shape = remember(cut) { SlantShape(cut) }
    Box(
        modifier
            .semantics { if (description != null) contentDescription = description }
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .drawBehind {
                val c = cut.toPx().coerceAtMost(size.width / 3)
                slantShadow(c, (6f - 3f * press).dp.toPx(), (9f - 4f * press).dp.toPx(), 0.6f - 0.05f * press)
            }
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { translationY = press * 1.dp.toPx() }
                .clip(shape)
                .drawWithContent {
                    glossFace()
                    pressLight(run.value)
                    drawContent()
                },
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/** A square-ish control: icon, name, and a status with its small light. */
@Composable
fun SlantTile(
    icon: LuxIcon,
    label: String,
    status: String,
    state: Led = Led.Off,
    modifier: Modifier = Modifier,
    description: String = "${label.lowercase().replaceFirstChar { it.uppercase() }}: $status",
    onClick: () -> Unit,
) {
    SlantButton(onClick, modifier.height(62.dp), description = description) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LineGlyph(icon, Color(0xFFD5D9DF), 20.dp)
            Text(label, style = lux(10.5.sp, FontWeight.Medium, Color(0xFFE6E8EB), 1.8.sp), maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                StatusLed(state)
                Text(
                    status,
                    style = lux(10.sp, FontWeight.Normal, if (state == Led.Off) Color(0xFF6F7783) else Color(0xFFAEB4BD)),
                    maxLines = 1,
                )
            }
        }
    }
}

enum class Led { Off, On, Blink }

@Composable
private fun StatusLed(state: Led) {
    val blink = if (state == Led.Blink) {
        val t = rememberInfiniteTransition(label = "led")
        t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "blink").value
    } else 1f
    Canvas(Modifier.size(4.dp)) {
        val r = size.minDimension / 2
        if (state == Led.Off) {
            drawCircle(Color(0xFF2C3036), r)
        } else {
            drawCircle(Color.White.copy(alpha = 0.35f * blink), r * 2.4f)
            drawCircle(Color(0xFFF4F5F7).copy(alpha = blink), r)
        }
    }
}

/** A wide action: END DRIVE NOW, VIEW DRIVE. */
@Composable
fun SlantWide(
    label: String,
    modifier: Modifier = Modifier,
    trailing: LuxIcon? = null,
    onClick: () -> Unit,
) {
    SlantButton(onClick, modifier.fillMaxWidth().height(54.dp), cut = 14.dp, description = label.lowercase().replaceFirstChar { it.uppercase() }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = lux(12.sp, FontWeight.Medium, Color(0xFFECEEF1), 2.6.sp), maxLines = 1)
            if (trailing != null) LineGlyph(trailing, Color(0xFFD5D9DF), 16.dp)
        }
    }
}

/**
 * Two slanted segments. The black gloss face slides under the chosen one, following [position]
 * (0 to 1, so it can track a swipe). A tap on a segment picks it.
 */
@Composable
fun ViewSwitch(labels: List<String>, icons: List<LuxIcon>, position: Float, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    val n = labels.size
    val run = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Box(modifier.fillMaxWidth().height(42.dp)) {
        // The sliding face.
        Canvas(Modifier.matchParentSize()) {
            val seg = size.width / n
            val c = 11.dp.toPx()
            val x = seg * position.coerceIn(0f, (n - 1).toFloat())
            translate(left = x) {
                val face = Size(seg, size.height)
                val path = slantPath(face, c)
                drawContext.canvas.save()
                drawContext.canvas.clipPath(path)
                // Draw the face within one segment's width.
                drawRect(Brush.verticalGradient(0f to Color(0xFF101114), 0.52f to Color(0xFF08090B), 1f to Color(0xFF040405)), size = face)
                drawRect(
                    Brush.verticalGradient(0f to Color.White.copy(alpha = 0.04f), 0.49f to Color.White.copy(alpha = 0.012f), 0.5f to Color.Transparent),
                    size = face,
                )
                val px = 1.dp.toPx()
                drawRect(
                    Brush.horizontalGradient(0.06f to Color.Transparent, 0.32f to Color.White.copy(alpha = 0.17f), 0.68f to Color.White.copy(alpha = 0.17f), 0.94f to Color.Transparent, startX = 0f, endX = seg),
                    size = Size(seg, px),
                )
                drawRect(
                    Brush.horizontalGradient(0.06f to Color.Transparent, 0.32f to Color.White.copy(alpha = 0.07f), 0.68f to Color.White.copy(alpha = 0.07f), 0.94f to Color.Transparent, startX = 0f, endX = seg),
                    topLeft = Offset(0f, face.height - px), size = Size(seg, px),
                )
                if (run.value < 1f) {
                    val ew = seg * 0.34f
                    val ex = (-1.1f + 4.7f * run.value) * ew
                    drawRect(
                        Brush.horizontalGradient(0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.8f), 1f to Color.Transparent, startX = ex, endX = ex + ew),
                        topLeft = Offset(ex, 0f), size = Size(ew, px),
                    )
                }
                drawContext.canvas.restore()
            }
        }
        Row(Modifier.matchParentSize()) {
            labels.forEachIndexed { i, label ->
                val near = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
                val color = lerp(Color(0xFF6F7783), Color(0xFFF2F4F7), near)
                val source = remember { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val haptics = LocalHapticFeedback.current
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer { translationY = if (pressed) 1.dp.toPx() else 0f }
                        .semantics { contentDescription = label.lowercase().replaceFirstChar { it.uppercase() } }
                        .clickable(interactionSource = source, indication = null, role = Role.Tab) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scope.launch { run.snapTo(0f); run.animateTo(1f, tween(600, easing = LightEase)) }
                            onPick(i)
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LineGlyph(icons[i], color, 16.dp)
                    Spacer(Modifier.width(7.dp))
                    Text(label, style = lux(12.sp, FontWeight.Medium, color, 2.2.sp), maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- readouts

data class Readout(val label: String, val value: String, val unit: String = "", val color: Color? = null)

/** A row of figures over a hairline, divided by hairlines: TRIP 12.4 km | TIME 24:18 | TRIP AVG 15.2. */
@Composable
fun Readouts(items: List<Readout>, modifier: Modifier = Modifier, size: TextUnit = 25.sp) {
    val line = Lux.Line
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(line))
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            items.forEachIndexed { i, r ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(line))
                BoxWithConstraints(Modifier.weight(1f).semantics(mergeDescendants = true) {}, contentAlignment = Alignment.TopCenter) {
                    val text = valueWithUnit(r.value, r.unit)
                    val style = lux(size, FontWeight.Light, r.color ?: Lux.Txt).copy(lineHeight = size * 1.2f)
                    // A long figure (a four-digit cost, say) steps down in size rather than being cut off.
                    val room = with(density) { (maxWidth - 6.dp).toPx() }
                    val wide = measurer.measure(text, style).size.width
                    val fitted = if (wide > room && wide > 0) style.copy(fontSize = size * (room / wide)) else style
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(r.label, style = lux(9.5.sp, FontWeight.Normal, Lux.Dim, 2.2.sp), maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text(text, style = fitted, maxLines = 1, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

private fun valueWithUnit(value: String, unit: String): AnnotatedString = buildAnnotatedString {
    append(value)
    if (unit.isNotEmpty()) {
        withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = Lux.Dim, letterSpacing = 0.sp)) { append(" $unit") }
    }
}

// ---------------------------------------------------------------- the cloud behind the goodbye

/** A faint, slowly drifting white cloud, like the glow behind a premium car's farewell screen. */
@Composable
fun CloudBackdrop(alpha: Float, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "cloud")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(18_000), RepeatMode.Reverse), label = "c1")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(22_000), RepeatMode.Reverse), label = "c2")
    val c by t.animateFloat(0f, 1f, infiniteRepeatable(tween(20_000), RepeatMode.Reverse), label = "c3")
    Canvas(modifier) {
        if (alpha <= 0f || !Palette.isDark) return@Canvas
        val u = 1.dp.toPx()
        val boxW = size.width + 180 * u
        val left = -90 * u
        fun blob(x: Float, y: Float, w: Float, h: Float, color: Color, dx: Float, dy: Float, s: Float) {
            val cx = left + boxW * (x + w / 2) + dx * u
            val cy = (10 + y + h / 2) * u + dy * u
            val rx = boxW * w / 2 * s
            val ry = h / 2 * u * s
            val k = rx / ry
            scale(scaleX = k, scaleY = 1f, pivot = Offset(cx, cy)) {
                drawCircle(
                    Brush.radialGradient(
                        0f to color.copy(alpha = color.alpha * alpha), 0.55f to color.copy(alpha = color.alpha * alpha * 0.45f), 1f to Color.Transparent,
                        center = Offset(cx, cy), radius = ry,
                    ),
                    radius = ry, center = Offset(cx, cy),
                )
            }
        }
        blob(0.34f, 26f, 0.60f, 190f, Color(0xFFDEE8F6).copy(alpha = 0.10f), 18f - 48f * b, 4f - 10f * b, 1.04f - 0.08f * b)
        blob(0.14f, 70f, 0.72f, 170f, Color(0xFFECF2FA).copy(alpha = 0.15f), -24f + 50f * a, 8f * a, 1f + 0.08f * a)
        blob(0.04f, 130f, 0.56f, 130f, Color.White.copy(alpha = 0.08f), 22f * c, -8f * c, 0.96f + 0.10f * c)
    }
}

// ---------------------------------------------------------------- a thin title revealed by light

/**
 * A thin title that appears left to right behind a feathered edge, with one slow light passing across it.
 * [reveal] and [sheen] run from 0 to 1.
 */
@Composable
fun RevealTitle(text: String, reveal: Float, sheen: Float, modifier: Modifier = Modifier, fontSize: TextUnit = 34.sp) {
    val base = lux(fontSize, FontWeight.ExtraLight, Color(0xFFE9ECF1), 3.sp).copy(textAlign = TextAlign.Center)
    Box(
        modifier
            .semantics { contentDescription = text }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // The wipe: opaque up to its leading edge, feathered over the last 18 %.
                val span = size.width + 60.dp.toPx()
                val x = -span + span * reveal
                drawRect(
                    Brush.horizontalGradient(0f to Color.White, 0.82f to Color.White, 1f to Color.Transparent, startX = x, endX = x + span),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = base.copy(color = Lux.Txt, shadow = Shadow(Color.White.copy(alpha = 0.28f), Offset.Zero, 22f)), maxLines = 1)
        if (sheen in 0.001f..0.999f) {
            Box(
                Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val w = size.width
                        val x1 = -420.dp.toPx() + (w + 420.dp.toPx()) * sheen
                        val x2 = x1 + 400.dp.toPx()
                        drawRect(
                            Brush.horizontalGradient(
                                0f to Color.Transparent, 0.44f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.95f),
                                0.56f to Color.Transparent, 1f to Color.Transparent, startX = x1, endX = x2,
                            ),
                            blendMode = BlendMode.SrcIn,
                        )
                    }
            ) {
                Text(text, style = base.copy(color = Color.White), maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- the car clip

/**
 * Plays a short silent clip once. Its black background is added onto what is behind it (screen blend),
 * so the frame's edges disappear into the page. [onEnd] runs when it finishes or cannot play.
 */
@Composable
fun ClipPlayer(@RawRes res: Int, modifier: Modifier = Modifier, alpha: Float = 1f, onEnd: () -> Unit = {}) {
    val end by rememberUpdatedState(onEnd)
    val holder = remember { arrayOfNulls<MediaPlayer>(1) }
    val surface = remember { arrayOfNulls<Surface>(1) }
    fun release() {
        holder[0]?.let { runCatching { it.release() } }
        holder[0] = null
        surface[0]?.let { runCatching { it.release() } }
        surface[0] = null
    }
    fun paint(a: Float) = android.graphics.Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        this.alpha = (a.coerceIn(0f, 1f) * 255).toInt()
    }
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = false
                setLayerPaint(paint(alpha))
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        try {
                            val mp = MediaPlayer()
                            holder[0] = mp
                            ctx.resources.openRawResourceFd(res).use { fd -> mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
                            val sf = Surface(st)
                            surface[0] = sf
                            mp.setSurface(sf)
                            mp.setVolume(0f, 0f)
                            mp.setOnCompletionListener { end() }
                            mp.setOnErrorListener { _, _, _ -> end(); true }
                            mp.setOnPreparedListener { it.start() }
                            mp.prepareAsync()
                        } catch (e: Exception) {
                            end()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        release()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        update = { view ->
            // Only when the opacity changes: a new paint redraws the video.
            if (view.tag != alpha) {
                view.tag = alpha
                view.setLayerPaint(paint(alpha))
            }
        },
        modifier = modifier,
    )
    DisposableEffect(Unit) { onDispose { release() } }
}

/** The car's outline with its lights off: the clip's resting frame, blended the same way. */
@Composable
fun ClipStill(modifier: Modifier = Modifier, alpha: Float = 1f) {
    val image = androidx.compose.ui.graphics.ImageBitmap.imageResource(R.drawable.civic_outline)
    Canvas(modifier) {
        drawImage(
            image, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            alpha = alpha.coerceIn(0f, 1f), blendMode = if (Palette.isDark) BlendMode.Screen else BlendMode.Multiply,
        )
    }
}

// ---------------------------------------------------------------- line icons, 1.5 units on a 24 grid

enum class LuxIcon(vararg val d: String) {
    Gauges("M4 16a8 8 0 1 1 16 0", "M12 16l4-5"),
    Map("M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2z", "M9 4v14M15 6v14"),
    Hud("M3.5 6h17l-2.2 11.5H5.7z", "M9 10.5h6M10 13.5h4"),
    Bluetooth("M7 7l10 10-5 5V2l5 5L7 17"),
    Gps("M5 12a7 7 0 1 0 14 0a7 7 0 1 0-14 0", "M9.6 12a2.4 2.4 0 1 0 4.8 0a2.4 2.4 0 1 0-4.8 0", "M12 2v3M12 19v3M2 12h3M19 12h3"),
    Chevron("M9.5 6l6 6-6 6"),
    Back("M14.5 6l-6 6 6 6"),
    Expand("M14 4h6v6M10 20H4v-6M20 4l-7 7M4 20l7-7"),
}

private val iconPaths = HashMap<LuxIcon, List<Path>>()

private fun pathsOf(icon: LuxIcon): List<Path> = iconPaths.getOrPut(icon) {
    icon.d.map { PathParser().parsePathString(it).toPath() }
}

@Composable
fun LineGlyph(icon: LuxIcon, color: Color, size: Dp = 20.dp, weight: Float = 1.5f) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        scale(s, s, pivot = Offset.Zero) {
            pathsOf(icon).forEach {
                drawPath(it, color, style = Stroke(width = weight, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** A small round button floating over the map. */
@Composable
fun MapButton(icon: LuxIcon, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(44.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(Color(0xFF050608).copy(alpha = 0.85f))
            .drawBehind { drawRect(Lux.Line, style = Stroke(1.dp.toPx())) }
            .semantics { contentDescription = description }
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { LineGlyph(icon, Color(0xFFC9CED6), 20.dp) }
}

/** Fraction of the way from [a] to [b] seconds that [t] has gone, eased, clamped to 0..1. */
fun span(t: Float, a: Float, b: Float, easing: androidx.compose.animation.core.Easing = androidx.compose.animation.core.FastOutSlowInEasing): Float =
    if (b <= a) (if (t >= b) 1f else 0f) else easing.transform(((t - a) / (b - a)).coerceIn(0f, 1f))
