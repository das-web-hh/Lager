package ru.lager.app.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import ru.lager.app.R

/**
 * Фон главного окна (md3_wallpaper в warehouse.html): 0 = нет, 1..4 = картинка.
 * Показывается только в светлой теме. Выбор хранится в настройках и сразу виден на главном экране.
 */
object WallpaperStore {
    val res = listOf(R.drawable.wp_1, R.drawable.wp_2, R.drawable.wp_3, R.drawable.wp_4)

    private const val PREFS = "lager_settings"
    private const val KEY = "wallpaper"
    private var state by mutableIntStateOf(-1)

    fun selected(ctx: Context): Int {
        val v = state
        return if (v == -1) ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0) else v
    }

    fun select(ctx: Context, value: Int) {
        val v = if (value in 1..res.size) value else 0
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, v).apply()
        state = v
    }
}

/** Слой с картинкой под содержимым главного экрана. [alpha] = 1 в светлой теме, 0 в тёмной. */
@Composable
fun WallpaperLayer(alpha: Float) {
    val ctx = LocalContext.current
    val sel = WallpaperStore.selected(ctx)
    if (sel in 1..WallpaperStore.res.size && alpha > 0.001f) {
        Image(
            painter = painterResource(WallpaperStore.res[sel - 1]),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().alpha(alpha),
            contentScale = ContentScale.Crop,
        )
    }
}
