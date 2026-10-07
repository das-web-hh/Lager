package ru.lager.app.ui.win

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Большой счётчик поверх окна «Приём по наименованию» (nrShowTapCount / nrShowAddedFx в warehouse.html):
 * серия нажатий 1, 2, 3… (через 3 с без нажатий обнуляется и озвучивается) и добавленное количество.
 */
object NrFx {
    var seq by mutableIntStateOf(0)
        private set
    var tapCount = 0
    private var addedValue = 0
    private var kind = 0 // 1 тап, 2 добавлено

    fun tap() {
        tapCount += 1
        kind = 1
        seq += 1
    }

    fun added(value: Int) {
        if (value <= 0) return
        tapCount = 0
        addedValue = value
        kind = 2
        seq += 1
    }

    internal fun kind() = kind
    internal fun addedValue() = addedValue
}

@Composable
fun NrFxHost(langCode: String) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    val fade = remember { Animatable(0f) }
    val scale = remember { Animatable(0.72f) }

    LaunchedEffect(NrFx.seq) {
        if (NrFx.seq == 0) return@LaunchedEffect
        val tag = Speech.langTag(langCode)
        if (NrFx.kind() == 1) {
            val count = NrFx.tapCount
            val inMs = if (count == 1) 220 else 120
            text = count.toString()
            scale.snapTo(0.72f)
            coroutineScope {
                launch { fade.animateTo(0.88f, tween(inMs)) }
                launch { scale.animateTo(1f, tween(inMs)) }
            }
            delay((2000 - inMs).toLong())
            fade.animateTo(0f, tween(950))
            delay(50)
            val finalCount = NrFx.tapCount
            NrFx.tapCount = 0
            text = ""
            Speech.announce(ctx, finalCount, "tap", tag)
        } else {
            val added = NrFx.addedValue()
            val showMs = Speech.settings(ctx).showSec * 1000L
            text = added.toString()
            scale.snapTo(0.72f)
            Speech.announce(ctx, added, "wheel", tag)
            coroutineScope {
                launch { fade.animateTo(0.88f, tween(250)) }
                launch { scale.animateTo(1f, tween(250)) }
            }
            delay(showMs - 250)
            fade.animateTo(0f, tween(900))
            delay(100)
            text = ""
        }
    }

    Box(Modifier.fillMaxSize().zIndex(10f), contentAlignment = Alignment.Center) {
        if (text.isNotEmpty()) {
            val size = when {
                text.length == 3 -> 190.sp
                text.length >= 4 -> 140.sp
                else -> 264.sp
            }
            Text(
                text,
                fontSize = size,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.65f), Offset(0f, 6f), 40f)),
                modifier = Modifier.graphicsLayer {
                    alpha = fade.value
                    scaleX = scale.value
                    scaleY = scale.value
                },
            )
        }
    }
}
