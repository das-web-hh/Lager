package ru.lager.app.ui.win

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.hypot

/** cubic-bezier(.2,0,0,1) — кривая всех окон в warehouse.html. */
val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

// ---------- Ripple (.md3-ripple): круг от точки касания, 0.55 с, прозрачность .16 → 0 ----------

class Md3RippleFactory(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        Md3RippleNode(interactionSource, color)

    override fun equals(other: Any?): Boolean = other is Md3RippleFactory && other.color == color
    override fun hashCode(): Int = color.hashCode()
}

private class Md3RippleNode(
    private val source: InteractionSource,
    private val color: Color,
) : Modifier.Node(), DrawModifierNode {

    private val progress = Animatable(0f)
    private val alpha = Animatable(0f)
    private var center = Offset.Zero

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                if (interaction is PressInteraction.Press) {
                    center = interaction.pressPosition
                    launch {
                        progress.snapTo(0f)
                        alpha.snapTo(0.16f)
                        launch { alpha.animateTo(0f, tween(550, easing = EmphasizedEasing)) }
                        progress.animateTo(1f, tween(550, easing = EmphasizedEasing))
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = alpha.value
        if (a > 0f) {
            val maxRadius = hypot(size.width, size.height)
            drawCircle(color = color, radius = maxRadius * progress.value, center = center, alpha = a)
        }
    }
}

/** clickable с волной как в HTML. Цвет по умолчанию — цвет текста (currentColor). */
@Composable
fun Modifier.md3Clickable(
    color: Color = Color.Unspecified,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier {
    val rippleColor = if (color == Color.Unspecified) LocalContentColor.current else color
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = source,
        indication = Md3RippleFactory(rippleColor),
        enabled = enabled,
        onClick = onClick,
    )
}

/** :active — лёгкое уменьшение и/или прозрачность. Ставится ДО clip/background. */
@Composable
fun Modifier.pressScale(
    source: MutableInteractionSource,
    pressedScale: Float,
    pressedAlpha: Float = 1f,
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, tween(120), label = "pressScale")
    val alpha by animateFloatAsState(if (pressed) pressedAlpha else 1f, tween(120), label = "pressAlpha")
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

// ---------- nr-tap-feedback: scale 1 → .975 → 1, яркость +38% ----------

@Composable
fun Modifier.tapFeedback(trigger: Int): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            p.snapTo(0f)
            p.animateTo(1f, tween(126, easing = LinearOutSlowInEasing))
            p.animateTo(0f, tween(154, easing = FastOutSlowInEasing))
        }
    }
    return this
        .graphicsLayer {
            val s = 1f - 0.025f * p.value
            scaleX = s
            scaleY = s
        }
        .drawWithContent {
            drawContent()
            if (p.value > 0f) drawRect(Color.White.copy(alpha = 0.22f * p.value))
        }
}

// ---------- nr-new-product-pulse: зелёное кольцо пульсирует ----------

@Composable
fun Modifier.newProductPulse(active: Boolean): Modifier {
    if (!active) return this
    val transition = rememberInfiniteTransition(label = "pulse")
    val p by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1200
                0f at 0
                1f at 600
                0f at 1200
            },
        ),
        label = "pulseValue",
    )
    return this.drawWithContent {
        drawContent()
        if (p > 0f) {
            val w = 3.dp.toPx() * p
            drawRect(
                color = Color(0xFF63E6BE).copy(alpha = 0.9f * p),
                topLeft = Offset(w / 2f, w / 2f),
                size = Size(size.width - w, size.height - w),
                style = Stroke(w),
            )
        }
    }
}

// ---------- scanLine: синяя линия бегает 25% ↔ 75% ----------

@Composable
fun ScanLine(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scanLine")
    val fraction by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scanLineY",
    )
    Canvas(modifier.fillMaxSize()) {
        drawRect(
            color = Color(0xE61C7ED6),
            topLeft = Offset(8.dp.toPx(), size.height * fraction),
            size = Size(size.width - 16.dp.toPx(), 2.dp.toPx()),
        )
    }
}

// ---------- nrOcrSpin: вращающееся кольцо ----------

@Composable
fun SpinnerRing(modifier: Modifier = Modifier.size(20.dp), color: Color = Color.White) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "spinAngle",
    )
    Canvas(modifier) {
        val sw = size.minDimension * 0.14f
        val topLeft = Offset(sw / 2f, sw / 2f)
        val arcSize = Size(size.width - sw, size.height - sw)
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, topLeft, arcSize, style = Stroke(sw))
        drawArc(color, angle, 90f, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

// ---------- geminiChatMessageIn: появление сообщения (fade + сдвиг 5dp, 0.2 с) ----------

@Composable
fun Modifier.messageIn(): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(200, easing = LinearOutSlowInEasing)) }
    return this.graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * 5.dp.toPx()
    }
}

// ---------- geminiChatTyping: три точки «печатает…» ----------

@Composable
fun TypingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val lift by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1000
                        0f at 0
                        1f at 300
                        0f at 600
                        0f at 1000
                    },
                    initialStartOffset = StartOffset(i * 150),
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer { translationY = -3.dp.toPx() * lift }
                    .background(color.copy(alpha = 0.35f + 0.65f * lift), CircleShape),
            )
        }
    }
}

// ---------- Появление диалога: масштаб .92 → 1 + прозрачность ----------

@Composable
fun Modifier.popIn(): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(200, easing = EmphasizedEasing)) }
    return this.graphicsLayer {
        val s = 0.92f + 0.08f * p.value
        scaleX = s
        scaleY = s
        alpha = p.value
    }
}
