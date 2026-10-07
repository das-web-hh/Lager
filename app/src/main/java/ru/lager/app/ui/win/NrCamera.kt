package ru.lager.app.ui.win

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// =====================================================================
//  Камера для снимков накладной (#nrCameraModal)
//  Серия кадров → «→» → каждый кадр распознаётся как страница накладной.
// =====================================================================

private fun Context.nrLifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

/** «1 кадр», «2 кадра», «5 кадров» (в HTML упрощено до кадр/кадров). */
private fun nrShotsLabel(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    val word = when {
        m100 in 11..14 -> "кадров"
        m10 == 1 -> "кадр"
        m10 in 2..4 -> "кадра"
        else -> "кадров"
    }
    return "$n $word"
}

/** Миниатюра кадра с учётом поворота EXIF. */
private fun nrThumb(path: String): Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inSampleSize = 16 }
    var bmp = BitmapFactory.decodeFile(path, o) ?: return null
    val rot = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
        6 -> 90f
        3 -> 180f
        8 -> 270f
        else -> 0f
    }
    if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
    bmp
}.getOrNull()

/**
 * Полноэкранная камера. Затвор делает снимок в полном качестве, внизу миниатюры и счётчик,
 * «→» отдаёт все кадры по порядку. Лишние кадры можно убрать нажатием на миниатюру.
 */
@Composable
fun NrCameraDialog(onDone: (List<Uri>) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    val shots = remember { mutableStateListOf<File>() }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    val dir = remember { File(ctx.cacheDir, "nrcam").apply { mkdirs() } }

    // Кадры прошлых серий могут ещё лежать во вложениях несохранённой партии, поэтому
    // чистим только то, что старше двух суток.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val limit = System.currentTimeMillis() - 48L * 3600_000L
            dir.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF05070A))) {
            if (granted) {
                NrCameraPreview(onCapture = { capture = it }, onError = { message = it })
            } else {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (denied) "Нет доступа к камере. Разрешите камеру для Lager в настройках телефона."
                        else "Ожидание разрешения камеры…",
                        color = Color.White,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // верх: закрыть + счётчик
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.48f))
                        .md3Clickable(color = Color.White, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        nrShotsLabel(shots.size),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.Black.copy(alpha = 0.48f))
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                    )
                }
            }

            if (message.isNotEmpty()) {
                Text(
                    message,
                    color = Color(0xFFFFB4B4),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 72.dp)
                        .widthIn(max = 380.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xBF731414))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }

            // низ: миниатюры, затвор, «далее»
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp),
            ) {
                if (shots.isNotEmpty()) {
                    val scroll = rememberScrollState()
                    LaunchedEffect(shots.size) { scroll.animateScrollTo(scroll.maxValue) }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        shots.forEachIndexed { i, f ->
                            val bmp = remember(f.path) { nrThumb(f.path) }
                            Box(
                                Modifier
                                    .size(width = 52.dp, height = 68.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                    .md3Clickable(color = Color.White) {
                                        shots.remove(f)
                                        f.delete()
                                    },
                            ) {
                                if (bmp != null) {
                                    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                                Text(
                                    "${i + 1}",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f))
                    // затвор
                    Box(
                        Modifier
                            .size(78.dp)
                            .clip(CircleShape)
                            .border(3.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                            .padding(6.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (busy || capture == null) 0.45f else 1f))
                            .md3Clickable(color = Color.Gray) {
                                val cap = capture
                                if (cap == null || busy) {
                                    if (cap == null) message = "Камера ещё запускается. Повторите снимок через секунду."
                                } else {
                                    busy = true
                                    message = ""
                                    val out = File(dir, "shot_${System.currentTimeMillis()}.jpg")
                                    cap.takePicture(
                                        ImageCapture.OutputFileOptions.Builder(out).build(),
                                        ContextCompat.getMainExecutor(ctx),
                                        object : ImageCapture.OnImageSavedCallback {
                                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                                busy = false
                                                if (out.length() > 0) shots.add(out)
                                                else message = "Не удалось сохранить снимок в хорошем качестве."
                                            }

                                            override fun onError(e: ImageCaptureException) {
                                                busy = false
                                                message = "Не удалось сделать снимок: ${e.message ?: "ошибка камеры"}"
                                            }
                                        },
                                    )
                                }
                            },
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        val ready = shots.isNotEmpty() && !busy
                        Box(
                            Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1976D2).copy(alpha = if (ready) 1f else 0.28f))
                                .then(
                                    if (ready) Modifier.md3Clickable(color = Color.White) {
                                        onDone(shots.map { Uri.fromFile(it) })
                                    } else Modifier,
                                ),
                            contentAlignment = Alignment.Center,
                        ) { Text("→", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NrCameraPreview(onCapture: (ImageCapture) -> Unit, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = remember(ctx) { ctx.nrLifecycleOwner() } ?: return
    val previewView = remember {
        PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener(
            {
                runCatching {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    provider.unbindAll()
                    val selector =
                        if (SettingsStore.camera(ctx) == 2) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    runCatching { provider.bindToLifecycle(owner, selector, preview, imageCapture) }
                        .getOrElse {
                            provider.unbindAll()
                            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                        }
                    onCapture(imageCapture)
                }.onFailure { onError("Не удалось запустить камеру: ${it.message ?: "ошибка"}") }
            },
            ContextCompat.getMainExecutor(ctx),
        )
        onDispose { runCatching { future.get().unbindAll() } }
    }
    AndroidView({ previewView }, Modifier.fillMaxSize())
}
