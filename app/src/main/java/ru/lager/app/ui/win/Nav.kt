package ru.lager.app.ui.win

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import ru.lager.app.auth.ProfileData
import ru.lager.app.auth.Role
import ru.lager.app.ui.Lang

sealed interface Win {
    data object ReceiveHub : Win
    data object ReceiveManual : Win
    data object ReceiveName : Win
    data object ReceiveAuto : Win
    data object Inventory : Win
    data object InventoryImport : Win
    data object InventoryExport : Win
    data object BWare : Win
    data object BWareHistory : Win
    data object Plan : Win
    data object PlanSession : Win
    data object PlanResult : Win
    data object History : Win
    data object Catalog : Win
    data object ImportExportSheet : Win
    data object ExcelImport : Win
    data object ExportExcel : Win
    data object Settings : Win
    data object LinkTool : Win
    data object Tasks : Win
    data object Profile : Win
    data object Info : Win
    data object Documents : Win
    data object GeminiChat : Win
    data object ProductCard : Win
    data object ProductInfo : Win
    data object CardFilter : Win
}

class WinEntry(val win: Win, val origin: Rect?) {
    var closing by mutableStateOf(false)
}

class WinNav {
    val entries = mutableStateListOf<WinEntry>()

    /** Открытые окна (без тех, что сейчас закрываются). */
    val stack: List<Win> get() = entries.filter { !it.closing }.map { it.win }

    fun push(w: Win, origin: Rect? = null) { entries.add(WinEntry(w, origin)) }
    fun pop() { entries.lastOrNull { !it.closing }?.closing = true }
    fun replaceTop(w: Win) {
        val i = entries.indexOfLast { !it.closing }
        if (i >= 0) entries.removeAt(i)
        push(w)
    }
    fun clear() { entries.clear() }
    val isOpen: Boolean get() = entries.any { !it.closing }
}

/** Всё, что окнам нужно от приложения. */
class WinEnv(
    val dark: Boolean,
    val lang: Lang,
    val setLanguage: (Lang) -> Unit,
    val setDark: (Boolean) -> Unit,
    val role: Role,
    val profile: ProfileData?,
    val biometricAvailable: Boolean,
    val biometricEnabled: Boolean,
    val toggleBiometric: (Boolean) -> Unit,
    val logout: () -> Unit,
    val info: (String) -> Unit,
    val nav: WinNav,
)

@Composable
fun WindowHost(env: WinEnv) {
    Md3Theme(env.dark) {
        env.nav.entries.toList().forEach { entry ->
            key(entry) {
                if (entry.win == Win.ImportExportSheet) {
                    if (entry.closing) {
                        SideEffect { env.nav.entries.remove(entry) }
                    } else {
                        ImportExportSheet(env)
                    }
                } else {
                    AnimatedWindow(entry, onRemoved = { env.nav.entries.remove(entry) }) {
                        WindowContent(entry.win, env)
                    }
                }
            }
        }
    }
}

/**
 * Открытие/закрытие окна: .fullscreen-modal { translateX(100%) → 0, .38s cubic-bezier(.2,0,0,1) }.
 * Окна, открытые плиткой, вырастают из плитки и сжимаются обратно в неё.
 */
@Composable
private fun AnimatedWindow(entry: WinEntry, onRemoved: () -> Unit, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(entry.closing) {
        if (entry.closing) {
            progress.animateTo(0f, tween(300, easing = EmphasizedEasing))
            onRemoved()
        } else {
            progress.animateTo(1f, tween(380, easing = EmphasizedEasing))
        }
    }
    val origin = entry.origin
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress.value
                val w = size.width
                val h = size.height
                if (origin != null && w > 0f && h > 0f) {
                    val sx0 = (origin.width / w).coerceIn(0.05f, 0.95f)
                    val sy0 = (origin.height / h).coerceIn(0.05f, 0.95f)
                    val px = (origin.center.x - w / 2f * sx0) / (1f - sx0)
                    val py = (origin.center.y - h / 2f * sy0) / (1f - sy0)
                    transformOrigin = TransformOrigin(px / w, py / h)
                    scaleX = sx0 + (1f - sx0) * p
                    scaleY = sy0 + (1f - sy0) * p
                    alpha = (p * 2.5f).coerceIn(0f, 1f)
                    shape = RoundedCornerShape(28.dp.toPx() * (1f - p))
                    clip = true
                } else {
                    translationX = (1f - p) * w
                }
            },
    ) { content() }
}

@Composable
private fun WindowContent(win: Win, env: WinEnv) {
    when (win) {
        Win.ReceiveHub -> ReceiveHubWindow(env)
        Win.ReceiveManual -> ReceiveManualWindow(env)
        Win.ReceiveName -> ReceiveNameWindow(env)
        Win.ReceiveAuto -> AutoReceiveWindow(env)
        Win.Inventory -> InventoryWindow(env)
        Win.InventoryImport -> InventoryImportWindow(env)
        Win.InventoryExport -> InventoryExportWindow(env)
        Win.BWare -> BWareWindow(env)
        Win.BWareHistory -> BWareHistoryWindow(env)
        Win.Plan -> PlanWindow(env)
        Win.PlanSession -> PlanSessionWindow(env)
        Win.PlanResult -> PlanResultWindow(env)
        Win.History -> HistoryWindow(env)
        Win.Catalog -> CatalogWindow(env)
        Win.ImportExportSheet -> Unit
        Win.ExcelImport -> CatExcelImportWindow(env)
        Win.ExportExcel -> ExportExcelWindow(env)
        Win.Settings -> SettingsWindow(env)
        Win.LinkTool -> LinkToolWindow(env)
        Win.Tasks -> TasksWindow()
        Win.Profile -> ProfileWindow(env)
        Win.Info -> InfoWindow()
        Win.Documents -> DocumentsWindow(env)
        Win.GeminiChat -> GeminiChatWindow(env)
        Win.ProductCard -> ProductCardWindow(env)
        Win.ProductInfo -> ProductInfoWindow(env)
        Win.CardFilter -> CardFilterWindow(env)
    }
}
