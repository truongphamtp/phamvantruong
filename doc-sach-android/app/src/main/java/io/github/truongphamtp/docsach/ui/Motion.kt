package io.github.truongphamtp.docsach.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Thông số chuyển động dùng chung: lò xo mềm, không giật, kiểu HyperOS. */
object Motion {
    /** Đường cong "giảm tốc mạnh" cho chuyển động đi vào. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Đường cong tăng tốc cho chuyển động đi ra. */
    val Exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> soft(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.86f, stiffness = 380f, visibilityThreshold = visibilityThreshold)

    fun <T> snappy(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = 650f, visibilityThreshold = visibilityThreshold)

    fun <T> bouncy(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.62f, stiffness = 480f, visibilityThreshold = visibilityThreshold)

    private val offsetSoft: FiniteAnimationSpec<IntOffset> =
        spring(dampingRatio = 0.86f, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold)

    /** Tấm kính trượt vào từ cạnh, hơi phóng to từ phía cạnh đó. */
    fun enterFromSide(fromRight: Boolean): EnterTransition =
        slideInHorizontally(offsetSoft) { if (fromRight) it / 2 else -it / 2 } +
            fadeIn(tween(220)) +
            scaleIn(soft(), initialScale = 0.94f, transformOrigin = TransformOrigin(if (fromRight) 1f else 0f, 0.5f))

    fun exitToSide(toRight: Boolean): ExitTransition =
        slideOutHorizontally(tween(220, easing = Exit)) { if (toRight) it / 2 else -it / 2 } +
            fadeOut(tween(170)) +
            scaleOut(tween(200), targetScale = 0.96f, transformOrigin = TransformOrigin(if (toRight) 1f else 0f, 0.5f))

    fun enterFromTop(): EnterTransition =
        slideInVertically(offsetSoft) { -it / 2 } + fadeIn(tween(200)) +
            scaleIn(soft(), initialScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 0f))

    fun exitToTop(): ExitTransition =
        slideOutVertically(tween(200, easing = Exit)) { -it / 2 } + fadeOut(tween(150)) +
            scaleOut(tween(200), targetScale = 0.97f, transformOrigin = TransformOrigin(0.5f, 0f))

    fun enterFromBottom(): EnterTransition =
        slideInVertically(offsetSoft) { it / 2 } + fadeIn(tween(200)) +
            scaleIn(soft(), initialScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 1f))

    fun exitToBottom(): ExitTransition =
        slideOutVertically(tween(200, easing = Exit)) { it / 2 } + fadeOut(tween(150)) +
            scaleOut(tween(200), targetScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 1f))
}

/**
 * Hiệu ứng nhấn kiểu HyperOS: phần tử co lại nhẹ khi chạm và nảy về khi nhả tay,
 * kể cả khi chạm rất nhanh (giữ hiệu ứng tối thiểu ~110 ms để mắt kịp thấy).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.93f,
    role: Role? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val visualPressed = remember { mutableStateOf(false) }
    LaunchedEffect(interaction) {
        var pressedAt = 0L
        interaction.interactions.collectLatest { event ->
            when (event) {
                is PressInteraction.Press -> {
                    pressedAt = System.nanoTime()
                    visualPressed.value = true
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    val held = (System.nanoTime() - pressedAt) / 1_000_000
                    if (held < 110) delay(110 - held)
                    visualPressed.value = false
                }
            }
        }
    }
    val pressed = visualPressed.value
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1500f)
        } else {
            spring(dampingRatio = 0.48f, stiffness = 520f)
        },
        label = "nhấn",
    )
    val dim by animateFloatAsState(if (pressed) 0.86f else 1f, tween(if (pressed) 60 else 220), label = "mờ khi nhấn")
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = dim
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}
