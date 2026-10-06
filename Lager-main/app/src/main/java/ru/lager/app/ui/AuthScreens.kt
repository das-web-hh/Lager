package ru.lager.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- Общие компоненты ----------

@Composable
fun LogoBadge(size: Int = 72) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(
                Brush.linearGradient(listOf(LagerColors.BlueDeep, LagerColors.Blue)),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text("L", fontSize = (size * 0.46f).sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(
                Modifier
                    .size(width = if (i + 1 == current) 24.dp else 8.dp, height = 8.dp)
                    .background(
                        if (i + 1 <= current) LagerColors.Blue else Color.White.copy(alpha = 0.2f),
                        RoundedCornerShape(4.dp),
                    ),
            )
        }
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = LagerColors.Orange,
        fontSize = 13.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

@Composable
fun AuthScaffold(
    title: String,
    subtitle: String,
    step: Int? = null,
    leftLink: Pair<String, () -> Unit>? = null,
    rightLink: Pair<String, () -> Unit>? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LogoBadge()
        Spacer(Modifier.height(20.dp))
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = LagerColors.Card,
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            shadowElevation = 12.dp,
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (step != null) {
                    StepDots(step, 3)
                    Spacer(Modifier.height(14.dp))
                }
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
                if (leftLink != null || rightLink != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (leftLink != null) LinkText(leftLink.first, leftLink.second) else Spacer(Modifier)
                        if (rightLink != null) LinkText(rightLink.first, rightLink.second) else Spacer(Modifier)
                    }
                }
            }
        }
    }
}

@Composable
fun AuthField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    password: Boolean = false,
    leadingIcon: ImageVector? = null,
    maxLength: Int = 200,
    onDone: (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    val trailing: (@Composable () -> Unit)? = if (password) {
        {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Скрыть пароль" else "Показать пароль",
                )
            }
        }
    } else {
        null
    }
    val leading: (@Composable () -> Unit)? = if (leadingIcon != null) {
        { Icon(leadingIcon, contentDescription = null) }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= maxLength) onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        leadingIcon = leading,
        trailingIcon = trailing,
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LagerColors.Blue,
            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
            focusedContainerColor = Color.White.copy(alpha = 0.12f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.07f),
            focusedLabelColor = LagerColors.Blue,
            unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
            cursorColor = LagerColors.Blue,
            focusedLeadingIconColor = LagerColors.Blue,
            unfocusedLeadingIconColor = Color.White.copy(alpha = 0.6f),
            focusedTrailingIconColor = Color.White,
            unfocusedTrailingIconColor = Color.White.copy(alpha = 0.6f),
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun PrimaryButton(text: String, loading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !loading,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = LagerColors.Blue,
            contentColor = Color.White,
            disabledContainerColor = LagerColors.Blue.copy(alpha = 0.6f),
            disabledContentColor = Color.White,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

// ---------- Вход ----------

@Composable
fun LoginScreen(
    loading: Boolean,
    onLogin: (String, String) -> Unit,
    onForgot: () -> Unit,
    onRegister: () -> Unit,
) {
    var login by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AuthScaffold(
        title = "Вход в систему",
        subtitle = "Введите Email и пароль",
        leftLink = "Забыл пароль?" to onForgot,
        rightLink = "Регистрация" to onRegister,
    ) {
        AuthField(
            label = "Email / Логин",
            value = login,
            onValueChange = { login = it },
            keyboardType = KeyboardType.Email,
            leadingIcon = Icons.Filled.Person,
        )
        AuthField(
            label = "Пароль",
            value = pass,
            onValueChange = { pass = it },
            password = true,
            imeAction = ImeAction.Done,
            leadingIcon = Icons.Filled.Lock,
            onDone = { onLogin(login, pass) },
        )
        PrimaryButton("Войти", loading) { onLogin(login, pass) }
    }
}

// ---------- Регистрация ----------

@Composable
fun RegisterEmailScreen(loading: Boolean, onSend: (String) -> Unit, onBackToLogin: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    AuthScaffold(
        title = "Регистрация",
        subtitle = "Введите Email для получения кода",
        step = 1,
        leftLink = "Уже есть аккаунт?" to onBackToLogin,
    ) {
        AuthField(
            label = "Email адрес",
            value = email,
            onValueChange = { email = it },
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
            leadingIcon = Icons.Filled.Email,
            onDone = { onSend(email) },
        )
        PrimaryButton("Получить код", loading) { onSend(email) }
    }
}

@Composable
fun RegisterCodeScreen(onVerify: (String) -> Unit, onBackToLogin: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    AuthScaffold(
        title = "Подтверждение",
        subtitle = "Введите код, отправленный на почту",
        step = 2,
        leftLink = "Уже есть аккаунт?" to onBackToLogin,
    ) {
        AuthField(
            label = "Код из Email",
            value = code,
            onValueChange = { code = it.filter(Char::isDigit) },
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
            maxLength = 6,
            onDone = { onVerify(code) },
        )
        PrimaryButton("Подтвердить код", false) { onVerify(code) }
    }
}

@Composable
fun RegisterProfileScreen(
    loading: Boolean,
    onComplete: (first: String, last: String, password: String, observer: Boolean) -> Unit,
    onBackToLogin: () -> Unit,
) {
    var first by rememberSaveable { mutableStateOf("") }
    var last by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var observer by rememberSaveable { mutableStateOf(false) }
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = Color.White.copy(alpha = 0.07f),
        labelColor = Color.White.copy(alpha = 0.7f),
        selectedContainerColor = LagerColors.Blue.copy(alpha = 0.4f),
        selectedLabelColor = Color.White,
    )
    AuthScaffold(
        title = "Ваши данные",
        subtitle = "Укажите имя, пароль и тип учётной записи",
        step = 3,
        leftLink = "Уже есть аккаунт?" to onBackToLogin,
    ) {
        AuthField("Имя", first, { first = it }, leadingIcon = Icons.Filled.Person)
        AuthField("Фамилия", last, { last = it }, leadingIcon = Icons.Filled.Person)
        AuthField(
            label = "Пароль (минимум 6 символов)",
            value = pass,
            onValueChange = { pass = it },
            password = true,
            imeAction = ImeAction.Done,
            leadingIcon = Icons.Filled.Lock,
        )
        Text(
            "Тип учётной записи",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.6f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !observer,
                onClick = { observer = false },
                label = { Text("Сотрудник склада") },
                colors = chipColors,
            )
            FilterChip(
                selected = observer,
                onClick = { observer = true },
                label = { Text("Наблюдатель") },
                colors = chipColors,
            )
        }
        PrimaryButton("Завершить регистрацию", loading) { onComplete(first, last, pass, observer) }
    }
}

// ---------- Сброс пароля ----------

@Composable
fun ResetEmailScreen(loading: Boolean, onSend: (String) -> Unit, onBackToLogin: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    AuthScaffold(
        title = "Сброс пароля",
        subtitle = "Введите почту для получения кода",
        leftLink = "Вернуться ко входу" to onBackToLogin,
    ) {
        AuthField(
            label = "Email для восстановления",
            value = email,
            onValueChange = { email = it },
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
            leadingIcon = Icons.Filled.Email,
            onDone = { onSend(email) },
        )
        PrimaryButton("Отправить код", loading) { onSend(email) }
    }
}

@Composable
fun ResetCodeScreen(loading: Boolean, onVerify: (String) -> Unit, onBackToLogin: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    AuthScaffold(
        title = "Введите код",
        subtitle = "После проверки кода мы отправим ссылку для смены пароля",
        leftLink = "Вернуться ко входу" to onBackToLogin,
    ) {
        AuthField(
            label = "Код из Email",
            value = code,
            onValueChange = { code = it.filter(Char::isDigit) },
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
            maxLength = 6,
            onDone = { onVerify(code) },
        )
        PrimaryButton("Получить ссылку для сброса", loading) { onVerify(code) }
    }
}
