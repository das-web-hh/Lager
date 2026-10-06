package ru.lager.app.ui.win

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val R10 = RoundedCornerShape(10.dp)
private val R12 = RoundedCornerShape(12.dp)
private val R14 = RoundedCornerShape(14.dp)
private val R16 = RoundedCornerShape(16.dp)

// ---------- Окно с шапкой (.fullscreen-modal + .modal-topbar) ----------

@Composable
fun WindowScaffold(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = Md3.c
    Surface(Modifier.fillMaxSize(), color = c.surface, contentColor = c.onSurface) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Surface(color = c.appbar, shadowElevation = 6.dp) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            title,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        actions()
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            if (footer != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = footer,
                )
            } else {
                Spacer(Modifier.fillMaxWidth().navigationBarsPadding())
            }
        }
    }
}

/** Кнопка-«пилюля» в шапке (Далее, История, Вставить…). */
@Composable
fun TopBarTextButton(text: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(source, 0.94f)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.16f))
            .md3Clickable(color = Color.White, interactionSource = source, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ScrollBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

// ---------- Карточки и подписи ----------

@Composable
fun Md3Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Md3.c
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = R16,
        color = c.card,
        contentColor = c.onSurface,
        shadowElevation = 1.dp,
    ) {
        Column(content = content)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Md3.c.primary,
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = Md3.c.onSurfaceVariant,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.3.sp,
        modifier = modifier.padding(bottom = 6.dp),
    )
}

@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Md3.c.onSurfaceVariant,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = modifier,
    )
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Md3.c.onSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

enum class StatusKind { Ok, Warn, Err }

@Composable
fun StatusLine(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val c = Md3.c
    val (bg, fg) = when (kind) {
        StatusKind.Ok -> c.successContainer to c.success
        StatusKind.Warn -> c.warningContainer to c.warning
        StatusKind.Err -> c.errorContainer to c.error
    }
    Text(
        text,
        color = fg,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .fillMaxWidth()
            .clip(R10)
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

// ---------- Кнопки ----------

enum class LongKind { Green, Blue, Red }

@Composable
fun LongButton(
    text: String,
    kind: LongKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = when (kind) {
        LongKind.Green -> listOf(Color(0xFF2B8A3E), Color(0xFF37A34A))
        LongKind.Blue -> listOf(Color(0xFF1563B8), Color(0xFF1C7ED6))
        LongKind.Red -> listOf(Color(0xFFC92A2A), Color(0xFFE03131))
    }
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .pressScale(source, 1f, 0.84f)
            .clip(R14)
            .background(Brush.linearGradient(colors))
            .md3Clickable(color = Color.White, enabled = enabled, interactionSource = source, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** Небольшая кнопка с рамкой (Сохранить / Проверить, Отмена). */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    danger: Boolean = false,
) {
    val c = Md3.c
    val bg = when {
        danger -> c.error
        filled -> c.primary
        else -> c.secondaryContainer
    }
    val fg = when {
        danger -> Color.White
        filled -> c.onPrimary
        else -> c.onSecondaryContainer
    }
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(source, 0.98f)
            .clip(R12)
            .background(bg)
            .md3Clickable(color = fg, interactionSource = source, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/** Квадратная кнопка с иконкой-глифом (сканер, добавить). */
@Composable
fun SquareIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Md3.c
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(46.dp)
            .pressScale(source, 0.92f)
            .clip(R10)
            .background(c.primaryContainer)
            .md3Clickable(color = c.onPrimaryContainer, interactionSource = source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ---------- Поля ввода ----------

@Composable
fun LabeledInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minHeight: Int = 0,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Md3.c
    Column(modifier) {
        if (label.isNotEmpty()) FieldLabel(label)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight.dp)
                .clip(R10)
                .background(c.surfaceLow)
                .border(1.5.dp, c.outlineVariant, R10)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, color = c.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 15.sp)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    textStyle = TextStyle(color = c.onSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(c.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (trailing != null) trailing()
        }
    }
}

@Composable
fun SelectField(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Md3.c
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label.isNotEmpty()) FieldLabel(label)
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(R10)
                    .background(c.surfaceLow)
                    .border(1.5.dp, c.outlineVariant, R10)
                    .md3Clickable { open = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    options.getOrElse(selected) { "" },
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("▾", color = c.onSurfaceVariant)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(i); open = false })
                }
            }
        }
    }
}

@Composable
fun Md3Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF2B8A3E), checkedThumbColor = Color.White),
    )
}

/** Сегментный переключатель (.md3-seg). */
@Composable
fun SegmentedPill(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val c = Md3.c
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(c.surfaceContainer)
            .border(1.dp, c.outlineVariant, RoundedCornerShape(50))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, text ->
            val active = i == selected
            val segBg by animateColorAsState(
                if (active) c.secondaryContainer else Color.Transparent,
                tween(250, easing = EmphasizedEasing), label = "segBg",
            )
            val segFg by animateColorAsState(
                if (active) c.onSecondaryContainer else c.onSurfaceVariant,
                tween(250, easing = EmphasizedEasing), label = "segFg",
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(segBg)
                    .md3Clickable { onSelect(i) }
                    .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 8.dp else 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text,
                    color = segFg,
                    fontSize = if (compact) 12.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ---------- Строки настроек ----------

@Composable
fun SettingsRow(
    icon: String,
    title: String,
    sub: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Md3.c
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.md3Clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(icon, fontSize = 22.sp, modifier = Modifier.widthIn(min = 32.dp), textAlign = TextAlign.Center)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
            if (sub != null) Text(sub, fontSize = 12.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            if (below != null) {
                Spacer(Modifier.height(8.dp))
                below()
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Md3.c.outlineVariant))
}

// ---------- Диалоги ----------

/** Центральное окно-карточка с заголовком и крестиком (.inventory-edit-modal и др.). */
@Composable
fun DialogCard(
    title: String,
    onDismiss: () -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Md3.c
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .popIn()
                .padding(horizontal = 20.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = c.card,
            contentColor = c.onSurface,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .md3Clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", color = c.onSurfaceVariant, fontSize = 16.sp) }
                }
                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
                if (actions != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
                }
            }
        }
    }
}

@Composable
fun RowScope.DialogActionCancel(text: String, onClick: () -> Unit) {
    SoftButton(text, onClick, Modifier.weight(1f))
}

@Composable
fun RowScope.DialogActionConfirm(text: String, onClick: () -> Unit, danger: Boolean = false) {
    SoftButton(text, onClick, Modifier.weight(1f), filled = true, danger = danger)
}

@Composable
fun BorderChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Md3.c
    Box(
        modifier
            .clip(R10)
            .background(if (selected) c.primaryContainer else c.surfaceLow)
            .border(BorderStroke(1.dp, if (selected) c.primary else c.outlineVariant), R10)
            .md3Clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) c.onPrimaryContainer else c.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Блок «подпись + значение» (адресные кнопки Склад / Ряд / Этаж / Полка). */
@Composable
fun AddressPart(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Md3.c
    Column(
        modifier
            .shadow(1.dp, R12)
            .clip(R12)
            .background(c.card)
            .border(1.dp, c.outlineVariant, R12)
            .md3Clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = c.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(value, color = c.onSurface, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
    }
}
