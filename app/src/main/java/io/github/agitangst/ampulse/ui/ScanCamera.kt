package io.github.agitangst.ampulse.ui

import android.util.Log
import android.util.Size
import android.view.View
import androidx.activity.compose.LocalActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AmpulseScan"

/**
 * Окно камеры сканера: превью CameraX и разбор кадров ML Kit (встроенная модель, без сети) — только DataMatrix.
 * Камера привязана к жизненному циклу окна и освобождается, когда экран уходит из композиции или программа
 * сворачивается.
 *
 * @param scanning читать ли кадры; `false` — превью идёт, но коды не разбираются (показан результат).
 * @param onRaw прочитанный код как текст, в главном потоке; GS-символы разделителей сохраняются.
 * @param onFlash есть ли у камеры фонарик (сообщается, когда камера открылась).
 * @param onError камера не открылась (нет камеры, занята другой программой).
 */
@Composable
internal fun ScanCamera(
    torch: Boolean,
    scanning: Boolean,
    onRaw: (String) -> Unit,
    onFlash: (Boolean) -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner: LifecycleOwner? = LocalActivity.current
    val latestOnRaw by rememberUpdatedState(onRaw)
    val latestOnFlash by rememberUpdatedState(onFlash)
    val latestOnError by rememberUpdatedState(onError)

    // Превью на TextureView: SurfaceView не слушается прозрачности, а экран появляется с перекрёстным затуханием.
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            keepScreenOn = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    // Анализатор работает в своём потоке и читает флаг, а не состояние Compose.
    val scanningFlag = remember { AtomicBoolean(scanning) }
    SideEffect { scanningFlag.set(scanning) }

    LaunchedEffect(owner) {
        if (owner == null) {
            Log.w(TAG, "No activity to bind the camera to")
            latestOnError()
            return@LaunchedEffect
        }
        val provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Camera provider is not available", e)
            latestOnError()
            return@LaunchedEffect
        }
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX).build(),
        )
        try {
            val bound = try {
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                // Кадр побольше, чем по умолчанию (640x480): плотный код «Честного знака» на малом кадре не читается.
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, barcodeAnalyzer(scanner, scanningFlag) { raw -> latestOnRaw(raw) }) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not open the camera", e)
                latestOnError()
                false
            }
            if (bound) awaitCancellation()
        } finally {
            // Экран ушёл или программа закрыта: отпустить камеру и разбор кадров.
            camera = null
            try {
                provider.unbindAll()
            } catch (e: Exception) {
                Log.w(TAG, "Could not release the camera", e)
            }
            scanner.close()
            executor.shutdown()
        }
    }

    // Камера открылась — есть ли у неё фонарик; фонарик включается и выключается отдельно от привязки.
    LaunchedEffect(camera) { latestOnFlash(camera?.cameraInfo?.hasFlashUnit() == true) }
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Разбор кадров: ML Kit ищет DataMatrix, первый найденный код уходит в [onRaw] (главный поток). Кадр закрывается,
 * когда разбор закончился; пока [scanning] выключен, кадры пропускаются без разбора.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun barcodeAnalyzer(scanner: BarcodeScanner, scanning: AtomicBoolean, onRaw: (String) -> Unit) =
    ImageAnalysis.Analyzer { proxy ->
        val media = proxy.image
        if (media == null || !scanning.get()) {
            proxy.close()
            return@Analyzer
        }
        try {
            scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener { barcodes ->
                    // rawValue — текст с GS-разделителями; байты — запасной путь, если текста нет.
                    val raw = barcodes.firstNotNullOfOrNull { b ->
                        b.rawValue?.takeIf { it.isNotEmpty() }
                            ?: b.rawBytes?.takeIf { it.isNotEmpty() }?.toString(Charsets.ISO_8859_1)
                    }
                    if (raw != null && scanning.get()) onRaw(raw)
                }
                .addOnFailureListener { e -> Log.w(TAG, "Barcode scan failed", e) }
                .addOnCompleteListener { proxy.close() }
        } catch (e: Exception) {
            // Разбор закрыт вместе с экраном — кадр всё равно надо вернуть камере.
            Log.w(TAG, "Barcode scan could not start", e)
            proxy.close()
        }
    }
