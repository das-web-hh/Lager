package ru.lager.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.lager.app.ui.win.EmphasizedEasing
import ru.lager.app.ui.win.pressScale
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ---------- Палитры ----------

private class HomePalette(
    val bg: Brush,
    val glow: Boolean,
    val clock: Color,
    val date: Color,
    val label: Color,
    val topIcon: Color,
    val searchBg: Color,
    val searchHint: Color,
    val searchInput: Color,
    val searchIcon: Color,
    val dotActive: Color,
    val dotInactive: Color,
)

private val LightPalette = HomePalette(
    bg = Brush.verticalGradient(
        0f to Color(0xFF3A76BC),
        0.30f to Color(0xFF86BDEB),
        0.62f to Color(0xFFCDE9FB),
        0.86f to Color(0xFF93C5EC),
        1f to Color(0xFF58A5DE),
    ),
    glow = true,
    clock = Color(0xFF1F2A44),
    date = Color(0xFF3F5174),
    label = Color(0xFF2A3A57),
    topIcon = Color(0xFF2F4B7C),
    searchBg = Color.White,
    searchHint = Color(0xFF5F6672),
    searchInput = Color(0xFF1B2433),
    searchIcon = Color(0xFF111111),
    dotActive = Color.White,
    dotInactive = Color(0xFF9CC9EE),
)

private val DarkPalette = HomePalette(
    bg = Brush.verticalGradient(
        0f to Color(0xFF0B1622),
        0.5f to Color(0xFF112240),
        1f to Color(0xFF0D1F35),
    ),
    glow = false,
    clock = Color.White,
    date = Color.White.copy(alpha = 0.6f),
    label = Color.White.copy(alpha = 0.9f),
    topIcon = Color.White.copy(alpha = 0.8f),
    searchBg = Color(0xFF16263F),
    searchHint = Color.White.copy(alpha = 0.55f),
    searchInput = Color.White,
    searchIcon = Color.White,
    dotActive = Color.White,
    dotInactive = Color.White.copy(alpha = 0.3f),
)

private fun lerpPalette(a: HomePalette, b: HomePalette, t: Float) = HomePalette(
    bg = a.bg,
    glow = a.glow,
    clock = lerpColor(a.clock, b.clock, t),
    date = lerpColor(a.date, b.date, t),
    label = lerpColor(a.label, b.label, t),
    topIcon = lerpColor(a.topIcon, b.topIcon, t),
    searchBg = lerpColor(a.searchBg, b.searchBg, t),
    searchHint = lerpColor(a.searchHint, b.searchHint, t),
    searchInput = lerpColor(a.searchInput, b.searchInput, t),
    searchIcon = lerpColor(a.searchIcon, b.searchIcon, t),
    dotActive = lerpColor(a.dotActive, b.dotActive, t),
    dotInactive = lerpColor(a.dotInactive, b.dotInactive, t),
)

// ---------- Плитки ----------

private class Tile(
    val id: String,
    val label: L,
    val icon: ImageVector,
    val top: Color,
    val bottom: Color,
    val caption: String? = null,
)

private val Page1Tiles = listOf(
    Tile("receiving", Str.receiving, Icons.Filled.MoveToInbox, Color(0xFF7C8EEA), Color(0xFF6A78DB)),
    Tile("inventory", Str.inventory, Icons.Filled.Assignment, Color(0xFF79BBC8), Color(0xFF6CAEBD)),
    Tile("bware", Str.bWare, Icons.Filled.Archive, Color(0xFFE8AD52), Color(0xFFD99A3C)),
    Tile("bytask", Str.byTask, Icons.Filled.AssignmentTurnedIn, Color(0xFF6DA9BE), Color(0xFF5F9AB0)),
)

private val Page2Tiles = listOf(
    Tile("history", Str.history, Icons.Filled.AccessTime, Color(0xFF86A5E6), Color(0xFF7692D8)),
    Tile("catalog", Str.catalog, Icons.Filled.Folder, Color(0xFF9B87DB), Color(0xFF8B76CE)),
    Tile("importexport", Str.importExport, Icons.Filled.SwapVert, Color(0xFF8CC5A4), Color(0xFF7DB594)),
    Tile("settings", Str.settings, Icons.Filled.Settings, Color(0xFFEDA87B), Color(0xFFE39B6A)),
    Tile("itembarcode", Str.itemBarcode, Icons.Filled.Link, Color(0xFFE88EA3), Color(0xFFDC7C93), caption = "ШК"),
    Tile("tasks", Str.tasks, Icons.Filled.Check, Color(0xFF84A2E4), Color(0xFF7490D6)),
    Tile("profile", Str.profile, Icons.Filled.Person, Color(0xFF9885D8), Color(0xFF8873CA)),
    Tile("info", Str.info, Icons.Filled.Info, Color(0xFF8CC5A0), Color(0xFF7DB591)),
    Tile("documents", Str.documents, Icons.Filled.Description, Color(0xFFEEAB7C), Color(0xFFE39B6A)),
    Tile("gemini", Str.geminiChat, Icons.Filled.Chat, Color(0xFF8168CE), Color(0xFF7059BF)),
)

private val TileShape = RoundedCornerShape(18.dp)

@Composable
private fun TileCell(
    tile: Tile,
    lang: Lang,
    labelColor: Color,
    onClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "tile")
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interaction, indication = null, onClick = { onClick(bounds) }),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .onGloballyPositioned { bounds = it.boundsInRoot() }
                .size(58.dp)
                .shadow(8.dp, TileShape, ambientColor = tile.bottom, spotColor = tile.bottom)
                .background(Brush.verticalGradient(listOf(tile.top, tile.bottom)), TileShape),
            contentAlignment = Alignment.Center,
        ) {
            if (tile.caption != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(tile.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                    Text(tile.caption, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Icon(tile.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            tile.label(lang),
            color = labelColor,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

// ---------- Элементы окна 1 ----------

private val TimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

@Composable
private fun ClockBlock(p: HomePalette) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = LocalDateTime.now()
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            now.format(TimeFmt),
            color = p.clock,
            fontSize = 48.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp,
        )
        Text(
            now.format(DateFmt),
            color = p.date,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun BarcodeScanIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.085f
        val arm = w * 0.28f
        val pad = stroke / 2f

        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + dx * arm, y), stroke, StrokeCap.Round)
            drawLine(color, Offset(x, y), Offset(x, y + dy * arm), stroke, StrokeCap.Round)
        }
        corner(pad, pad, 1f, 1f)
        corner(w - pad, pad, -1f, 1f)
        corner(pad, h - pad, 1f, -1f)
        corner(w - pad, h - pad, -1f, -1f)

        // штрихи: (x, ширина) в долях ширины
        val bars = listOf(
            0.26f to 0.05f, 0.35f to 0.03f, 0.42f to 0.07f,
            0.54f to 0.03f, 0.61f to 0.06f, 0.72f to 0.03f,
        )
        bars.forEach { (x, bw) ->
            drawRect(color, topLeft = Offset(x * w, 0.27f * h), size = Size(bw * w, 0.46f * h))
        }
    }
}

@Composable
private fun SearchBar(
    p: HomePalette,
    lang: Lang,
    onScan: () -> Unit,
    onSearch: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val pill = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(55.dp)
            .shadow(6.dp, pill, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
            .background(p.searchBg, pill)
            .padding(start = 22.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(Str.searchHint(lang), color = p.searchHint, fontSize = 18.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = p.searchInput, fontSize = 18.sp),
                cursorBrush = SolidColor(LagerColors.Blue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val scanSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(44.dp)
                .pressScale(scanSource, 0.91f)
                .clickable(interactionSource = scanSource, indication = null, onClick = onScan),
            contentAlignment = Alignment.Center,
        ) {
            BarcodeScanIcon(p.searchIcon, Modifier.size(30.dp))
        }
    }
}

@Composable
private fun HomePage1(
    p: HomePalette,
    lang: Lang,
    onTile: (String, String, Rect) -> Unit,
    onScan: () -> Unit,
    onSearch: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(50.dp))
        ClockBlock(p)
        Spacer(Modifier.height(28.dp))
        SearchBar(p, lang, onScan, onSearch)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp)
                .padding(bottom = 36.dp),
        ) {
            Page1Tiles.forEach { t ->
                TileCell(
                    tile = t,
                    lang = lang,
                    labelColor = p.label,
                    onClick = { r -> onTile(t.id, t.label(lang), r) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HomePage2(p: HomePalette, lang: Lang, onTile: (String, String, Rect) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 136.dp)
            .padding(horizontal = 11.dp),
    ) {
        Page2Tiles.chunked(4).forEach { rowTiles ->
            Row(Modifier.fillMaxWidth().height(130.dp)) {
                rowTiles.forEach { t ->
                    TileCell(
                        tile = t,
                        lang = lang,
                        labelColor = p.label,
                        onClick = { r -> onTile(t.id, t.label(lang), r) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(4 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PageDots(current: Int, count: Int, p: HomePalette) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val active = i == current
            val dotSize by animateDpAsState(if (active) 10.dp else 8.dp, tween(200), label = "dotSize")
            val dotColor by animateColorAsState(
                if (active) p.dotActive else p.dotInactive, tween(200), label = "dotColor",
            )
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(dotSize)
                    .background(dotColor, CircleShape),
            )
        }
    }
}

@Composable
private fun TopActions(
    p: HomePalette,
    lang: Lang,
    dark: Boolean,
    onLanguage: (Lang) -> Unit,
    onToggleDark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Row(modifier.padding(top = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.Language, contentDescription = "Language", tint = p.topIcon, modifier = Modifier.size(28.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Lang.entries.forEach { l ->
                    DropdownMenuItem(
                        text = { Text(l.title) },
                        onClick = {
                            onLanguage(l)
                            menu = false
                        },
                        trailingIcon = {
                            if (l == lang) Icon(Icons.Filled.Check, contentDescription = null)
                        },
                    )
                }
            }
        }
        IconButton(onClick = onToggleDark) {
            Icon(
                if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                contentDescription = "Theme",
                tint = p.topIcon,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

// ---------- Главное окно 1 и 2 ----------

@Composable
fun MainHomeScreen(
    lang: Lang,
    dark: Boolean,
    onLanguage: (Lang) -> Unit,
    onToggleDark: () -> Unit,
    onTile: (id: String, title: String, bounds: Rect) -> Unit,
    onScan: () -> Unit,
    onSearch: (String) -> Unit,
) {
    val themeT by animateFloatAsState(
        if (dark) 1f else 0f, tween(300, easing = EmphasizedEasing), label = "homeTheme",
    )
    val p = lerpPalette(LightPalette, DarkPalette, themeT)
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    BackHandler(enabled = pagerState.currentPage == 1) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(LightPalette.bg)
                .drawBehind {
                    drawRect(brush = DarkPalette.bg, alpha = themeT)
                    val glowAlpha = 0.35f * (1f - themeT)
                    if (glowAlpha > 0f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(Color.White.copy(alpha = glowAlpha), Color.Transparent),
                                center = Offset(size.width * 0.35f, size.height * 0.62f),
                                radius = size.width * 0.85f,
                            ),
                            radius = size.width * 0.85f,
                            center = Offset(size.width * 0.35f, size.height * 0.62f),
                        )
                    }
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { page ->
                    if (page == 0) {
                        HomePage1(p, lang, onTile, onScan, onSearch)
                    } else {
                        HomePage2(p, lang, onTile)
                    }
                }
                PageDots(pagerState.currentPage, 2, p)
            }
            TopActions(
                p = p,
                lang = lang,
                dark = dark,
                onLanguage = onLanguage,
                onToggleDark = onToggleDark,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Box(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}
