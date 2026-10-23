package com.abeernch.civicobd.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * One motion language for the whole app.
 * Springs instead of fixed durations, so movement settles naturally and never snaps.
 */
object Motion {
    /** Default for positions and sizes: quick, with a soft landing and no bounce. */
    fun <T> settle() = spring<T>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    /** For things that should feel physical, like the tab indicator: a touch of overshoot. */
    fun <T> glide() = spring<T>(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow)

    /** For colour and fades, where springs look odd. */
    fun <T> fade(ms: Int = 320) = tween<T>(ms, easing = FastOutSlowInEasing)
}

/**
 * Click with feedback: the element sinks to 96 % and dims slightly while pressed,
 * then springs back, with a light haptic tick. Used on every touch target.
 */
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val dim by animateFloatAsState(if (pressed) 0.75f else 1f, Motion.fade(120), label = "dim")
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = dim
        }
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}
