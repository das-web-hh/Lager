package ru.lager.app.ui.win

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// =====================================================================
//  Сканер штрихкодов: камера (CameraX) + распознавание (ML Kit, офлайн)
// =====================================================================

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

private class ScanAnalyzer(
    private val scanner: BarcodeScanner,
    private val onCode: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { list ->
                val code = list.firstNotNullOfOrNull { b -> b.rawValue?.trim()?.takeIf { it.length >= 6 } }
                if (code != null) onCode(code)
            }
            .addOnCompleteListener { proxy.close() }
    }
}

@Composable
private fun ScannerCamera(
    torch: Boolean,
    onCamera: (Camera) -> Unit,
    onCode: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val owner = remember(ctx) { ctx.findLifecycleOwner() } ?: return
    val codeCallback by rememberUpdatedState(onCode)
    val cameraCallback by rememberUpdatedState(onCamera)
    val previewView = remember {
        PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_ITF, Barcode.FORMAT_CODABAR, Barcode.FORMAT_QR_CODE,
                )
                .build(),
        )
        val done = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener(
            {
                runCatching {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(
                        executor,
                        ScanAnalyzer(scanner) { code ->
                            if (done.compareAndSet(false, true)) main.post { codeCallback(code) }
                        },
                    )
                    provider.unbindAll()
                    val selector = if (SettingsStore.camera(ctx) == 2) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    val cam = runCatching { provider.bindToLifecycle(owner, selector, preview, analysis) }
                        .getOrElse { provider.unbindAll(); provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
                    camera = cam
                    cameraCallback(cam)
                }
            },
            ContextCompat.getMainExecutor(ctx),
        )
        onDispose {
            done.set(true)
            runCatching { future.get().unbindAll() }
            executor.shutdown()
            runCatching { scanner.close() }
        }
    }

    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }

    AndroidView({ previewView }, Modifier.fillMaxSize())
}

/**
 * Полноэкранный сканер. onResult получает нормализованный штрихкод (один раз), после чего
 * вызывающий закрывает диалог. Разрешение камеры запрашивается при первом открытии.
 */
@Composable
fun BarcodeScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) {
                ScannerCamera(
                    torch = torch,
                    onCamera = { hasFlash = it.cameraInfo.hasFlashUnit() },
                    onCode = { raw ->
                        if (SettingsStore.vibration(ctx) > 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onResult(CatalogStore.normalizeBarcode(raw))
                    },
                )
                // рамка прицела
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .width(290.dp)
                            .height(170.dp)
                            .border(2.dp, Color(0xFF69DB7C), RoundedCornerShape(16.dp)),
                    )
                    Text(
                        "Наведите камеру на штрихкод",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = 18.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
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
            // верхняя панель: закрыть и фонарик
            Box(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .size(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .md3Clickable(color = Color.White, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                if (granted && hasFlash) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(44.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (torch) Color(0xFFFFD43B) else Color.Black.copy(alpha = 0.55f))
                            .md3Clickable(color = Color.White) { torch = !torch },
                        contentAlignment = Alignment.Center,
                    ) { Text("🔦", fontSize = 18.sp) }
                }
            }
        }
    }
}
