package io.github.agitangst.ampulse.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.github.agitangst.ampulse.R
import io.github.agitangst.anvil.compose.AnvilTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

// Ввод назначения фото и голосом (задача 3.1 этапа 0.5; макет — экран 7). Обе кнопки отдают распознанный текст в
// [onText] и больше ничего не знают о разборе: экран разбора кладёт его в поле для правки. Всё без сети — фото читает
// Tesseract на телефоне (CaptureOcr.kt), речь — служба распознавания телефона (офлайн, если скачан язык).

private const val CAPTURE_TAG = "AmpulseCapture"

/** Папка кэша для снимка с камеры (должна совпадать с `res/xml/file_paths.xml`). */
private const val CAPTURE_DIR = "capture"

/** Сколько ждать итог после «Стоп», мс: не пришёл — берём то, что успело распознаться. */
private const val FINISH_TIMEOUT_MS = 8000L

/** Сколько тишины (мс) служба считает концом фразы: назначение диктуют с паузами. */
private const val SILENCE_MS = 2500L

// --- Фото ---

private sealed interface CapturePhotoStep {
    data object Closed : CapturePhotoStep
    data object Choose : CapturePhotoStep
    data object CameraDenied : CapturePhotoStep
    data object Working : CapturePhotoStep
    data object Empty : CapturePhotoStep
    data class Failed(@StringRes val message: Int) : CapturePhotoStep
}

/** Задача распознавания, которую можно отменить из любого места экрана. */
private class CaptureJob {
    var job: Job? = null
}

/**
 * Фото рецепта → текст (Tesseract на телефоне, русский и английский, без сети). Кнопка открывает лист: «Снять» (камера
 * системы, снимок во временный файл кэша) или «Из галереи» (системный выбор фото, без разрешений). Пока идёт
 * распознавание — полоса и «Отмена»; ничего не прочитано или ошибка — понятное сообщение в том же листе. Снимок
 * не сохраняется: временный файл удаляется сразу после распознавания.
 */
@Composable
fun PhotoTextButton(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val latestOnText by rememberUpdatedState(onText)
    var step by remember { mutableStateOf<CapturePhotoStep>(CapturePhotoStep.Closed) }
    var percent by remember { mutableIntStateOf(0) }
    var canAsk by remember { mutableStateOf(true) }
    // Путь снимка переживает пересоздание экрана, пока открыта камера.
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val running = remember { CaptureJob() }

    fun recognize(uri: Uri, temp: File?) {
        running.job?.cancel()
        percent = 0
        step = CapturePhotoStep.Working
        running.job = scope.launch {
            try {
                val text = recognizePhoto(context, uri) { percent = it }
                if (text.isEmpty()) {
                    step = CapturePhotoStep.Empty
                } else {
                    step = CapturePhotoStep.Closed
                    latestOnText(text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OcrException) {
                Log.w(CAPTURE_TAG, "Photo was not recognized: ${e.failure}", e)
                step = CapturePhotoStep.Failed(
                    when (e.failure) {
                        OcrFailure.Photo -> R.string.capture_failed_open
                        OcrFailure.Memory -> R.string.capture_failed_memory
                        OcrFailure.Engine -> R.string.capture_failed_engine
                    },
                )
            } catch (e: Exception) {
                Log.w(CAPTURE_TAG, "Photo recognition failed", e)
                step = CapturePhotoStep.Failed(R.string.capture_failed_engine)
            } finally {
                temp?.delete()
            }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { done ->
        val file = cameraPath?.let { File(it) }
        cameraPath = null
        if (done && file != null && file.length() > 0) {
            recognize(FileProvider.getUriForFile(context, captureAuthority(context), file), file)
        } else {
            file?.delete() // вернулись без снимка — остаёмся в выборе источника
        }
    }

    fun launchCamera() {
        try {
            val file = newCaptureFile(context)
            cameraPath = file.absolutePath
            takePicture.launch(FileProvider.getUriForFile(context, captureAuthority(context), file))
        } catch (e: ActivityNotFoundException) {
            Log.w(CAPTURE_TAG, "No camera app", e)
            cameraPath = null
            step = CapturePhotoStep.Failed(R.string.capture_failed_camera)
        } catch (e: IllegalArgumentException) {
            Log.w(CAPTURE_TAG, "Camera file is not shareable", e)
            cameraPath = null
            step = CapturePhotoStep.Failed(R.string.capture_failed_camera)
        }
    }

    // Приложение объявляет CAMERA (сканер), поэтому системная камера без этого права не откроется.
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera()
        } else {
            canAsk = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
            step = CapturePhotoStep.CameraDenied
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) recognize(uri, null)
    }

    fun onCamera() {
        if (hasPermission(context, Manifest.permission.CAMERA)) launchCamera() else askCamera.launch(Manifest.permission.CAMERA)
    }

    fun onGallery() {
        try {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            Log.w(CAPTURE_TAG, "No photo picker", e)
            step = CapturePhotoStep.Failed(R.string.capture_failed_open)
        }
    }

    fun dismiss() {
        running.job?.cancel()
        running.job = null
        step = CapturePhotoStep.Closed
    }

    // Вернулись из настроек системы с включённой камерой — снова выбор источника.
    OnPermissionGranted(Manifest.permission.CAMERA, active = step is CapturePhotoStep.CameraDenied) { step = CapturePhotoStep.Choose }

    Box(modifier) {
        CaptureButton(
            text = stringResource(R.string.capture_photo_button),
            icon = CaptureIcons.Camera,
            onClick = { step = CapturePhotoStep.Choose },
        )
        if (step !is CapturePhotoStep.Closed) {
            TodaySheet(onDismiss = { dismiss() }) { close ->
                when (val current = step) {
                    CapturePhotoStep.Closed -> Unit
                    CapturePhotoStep.Choose -> PhotoChoose(onCamera = { onCamera() }, onGallery = { onGallery() })
                    CapturePhotoStep.CameraDenied -> CaptureMessage(
                        title = stringResource(R.string.capture_cam_perm_title),
                        hint = stringResource(R.string.capture_cam_perm_hint),
                        note = if (canAsk) null else stringResource(R.string.capture_perm_blocked),
                        primary = stringResource(if (canAsk) R.string.capture_perm_allow else R.string.capture_perm_settings),
                        onPrimary = {
                            if (canAsk) askCamera.launch(Manifest.permission.CAMERA) else openSystemSettings(context)
                        },
                        secondary = stringResource(R.string.capture_photo_gallery),
                        onSecondary = { onGallery() },
                    )
                    CapturePhotoStep.Working -> CaptureWorking(
                        percent,
                        // Отмена гасит задачу сразу, а не после анимации листа: иначе текст ещё мог бы прийти.
                        onCancel = {
                            running.job?.cancel()
                            close()
                        },
                    )
                    CapturePhotoStep.Empty -> CaptureMessage(
                        title = stringResource(R.string.capture_empty_title),
                        hint = stringResource(R.string.capture_empty_hint),
                        primary = stringResource(R.string.capture_other_photo),
                        onPrimary = { step = CapturePhotoStep.Choose },
                        secondary = stringResource(R.string.capture_close),
                        onSecondary = close,
                    )
                    is CapturePhotoStep.Failed -> CaptureMessage(
                        title = stringResource(R.string.capture_failed_title),
                        hint = stringResource(current.message),
                        warn = true,
                        primary = stringResource(R.string.capture_retry),
                        onPrimary = { step = CapturePhotoStep.Choose },
                        secondary = stringResource(R.string.capture_close),
                        onSecondary = close,
                    )
                }
            }
        }
    }
}

/** Лист «Фото рецепта»: что делает и откуда взять снимок. */
@Composable
private fun PhotoChoose(onCamera: () -> Unit, onGallery: () -> Unit) {
    val p = AnvilTheme.palette
    CaptureSheetTitle(stringResource(R.string.capture_photo_title))
    Text(stringResource(R.string.capture_photo_hint), color = p.weak, style = MaterialTheme.typography.bodyLarge)
    CaptureSourceRow(
        icon = CaptureIcons.Camera,
        title = stringResource(R.string.capture_photo_camera),
        hint = stringResource(R.string.capture_photo_camera_hint),
        onClick = onCamera,
    )
    CaptureSourceRow(
        icon = CaptureIcons.Image,
        title = stringResource(R.string.capture_photo_gallery),
        hint = stringResource(R.string.capture_photo_gallery_hint),
        onClick = onGallery,
    )
    Text(stringResource(R.string.capture_photo_tip), color = p.faint, style = MaterialTheme.typography.bodyMedium)
}

/** Адрес FileProvider из манифеста: `<applicationId>.fileprovider`. */
private fun captureAuthority(context: Context): String = "${context.packageName}.fileprovider"

/** Свободный файл снимка в кэше; остатки прошлых снимков стираются — фото рецепта не должно лежать на телефоне. */
private fun newCaptureFile(context: Context): File {
    val dir = File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    return File(dir, "prescription.jpg")
}

// --- Голос ---

private sealed interface CaptureVoiceStep {
    data object Closed : CaptureVoiceStep
    data object Listening : CaptureVoiceStep
    data object Finishing : CaptureVoiceStep
    data object NoPermission : CaptureVoiceStep
    data class Problem(val kind: VoiceProblem) : CaptureVoiceStep
}

/** Распознаватель текущей попытки; [session] отсекает запоздавшие вызовы уже закрытой попытки. */
private class CaptureSpeech {
    var recognizer: SpeechRecognizer? = null
    var session = 0

    fun release() {
        session++
        val old = recognizer
        recognizer = null
        if (old != null) {
            try {
                old.destroy()
            } catch (e: RuntimeException) {
                Log.w(CAPTURE_TAG, "Recognizer was not destroyed cleanly", e)
            }
        }
    }
}

/**
 * Голос → текст: `SpeechRecognizer` телефона, язык — по языку программы (ru-RU / en-US), без сети, если скачан язык
 * (`EXTRA_PREFER_OFFLINE`). Кнопка открывает лист и сразу начинает слушать; уже распознанное видно по ходу, «Стоп»
 * заканчивает. Права на микрофон просит при первом нажатии. Нет службы распознавания — кнопка серая, причина под ней.
 */
@Composable
fun VoiceTextButton(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val p = AnvilTheme.palette
    val latestOnText by rememberUpdatedState(onText)
    val available = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    var step by remember { mutableStateOf<CaptureVoiceStep>(CaptureVoiceStep.Closed) }
    var partial by remember { mutableStateOf("") }
    var canAsk by remember { mutableStateOf(true) }
    val holder = remember { CaptureSpeech() }

    // Распознаватель освобождается при уходе с экрана, как бы ни закрылся лист.
    DisposableEffect(Unit) { onDispose { holder.release() } }

    fun deliver(text: String) {
        holder.release()
        partial = ""
        step = CaptureVoiceStep.Closed
        latestOnText(text.trim().replace(Regex("\\s+"), " "))
    }

    fun fail(kind: VoiceProblem) {
        holder.release()
        if (kind == VoiceProblem.Permission) {
            canAsk = activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true
            step = CaptureVoiceStep.NoPermission
        } else {
            step = CaptureVoiceStep.Problem(kind)
        }
    }

    fun start() {
        holder.release()
        partial = ""
        val session = holder.session
        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: RuntimeException) {
            Log.w(CAPTURE_TAG, "Recognizer was not created", e)
            null
        }
        if (recognizer == null) {
            step = CaptureVoiceStep.Problem(VoiceProblem.Other)
            return
        }
        holder.recognizer = recognizer
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    if (session != holder.session) return
                    val text = firstResult(partialResults)
                    if (!text.isNullOrBlank()) partial = text
                }

                override fun onResults(results: Bundle?) {
                    if (session != holder.session) return
                    val text = firstResult(results)?.takeIf { it.isNotBlank() } ?: partial.takeIf { it.isNotBlank() }
                    if (text != null) deliver(text) else fail(VoiceProblem.NoMatch)
                }

                override fun onError(error: Int) {
                    if (session != holder.session) return
                    val kind = voiceProblemOf(error)
                    Log.i(CAPTURE_TAG, "Recognizer error $error")
                    // Успело распознаться — отдаём это: человек поправит текст в поле.
                    val keep = partial.takeIf { it.isNotBlank() && kind != VoiceProblem.Permission && kind != VoiceProblem.Audio }
                    if (keep != null) deliver(keep) else fail(kind)
                }
            },
        )
        step = CaptureVoiceStep.Listening
        try {
            recognizer.startListening(speechIntent(context))
        } catch (e: RuntimeException) {
            Log.w(CAPTURE_TAG, "Listening did not start", e)
            fail(VoiceProblem.Other)
        }
    }

    fun stop() {
        step = CaptureVoiceStep.Finishing
        try {
            holder.recognizer?.stopListening()
        } catch (e: RuntimeException) {
            Log.w(CAPTURE_TAG, "Listening did not stop", e)
            fail(VoiceProblem.Other)
        }
    }

    fun dismiss() {
        holder.release()
        partial = ""
        step = CaptureVoiceStep.Closed
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            start()
        } else {
            canAsk = activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true
            step = CaptureVoiceStep.NoPermission
        }
    }

    fun open() {
        if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) start() else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    // «Стоп» нажат, а итог не пришёл — не висеть вечно.
    LaunchedEffect(step) {
        if (step == CaptureVoiceStep.Finishing) {
            delay(FINISH_TIMEOUT_MS)
            if (step == CaptureVoiceStep.Finishing) {
                val text = partial.takeIf { it.isNotBlank() }
                if (text != null) deliver(text) else fail(VoiceProblem.NoMatch)
            }
        }
    }

    // Вернулись из настроек системы с включённым микрофоном — слушаем.
    OnPermissionGranted(Manifest.permission.RECORD_AUDIO, active = step is CaptureVoiceStep.NoPermission) { start() }

    Column(modifier) {
        CaptureButton(
            text = stringResource(R.string.capture_voice_button),
            icon = CaptureIcons.Mic,
            onClick = { open() },
            enabled = available,
            stateText = if (available) null else stringResource(R.string.capture_voice_unavailable),
        )
        if (!available) {
            Text(
                stringResource(R.string.capture_voice_unavailable),
                Modifier.padding(start = 12.dp, top = 6.dp, end = 12.dp),
                color = p.faint,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        if (step !is CaptureVoiceStep.Closed) {
            TodaySheet(onDismiss = { dismiss() }) { close ->
                when (val current = step) {
                    CaptureVoiceStep.Closed -> Unit
                    CaptureVoiceStep.Listening, CaptureVoiceStep.Finishing -> CaptureVoicePanel(
                        finishing = current == CaptureVoiceStep.Finishing,
                        partial = partial,
                        onStop = { stop() },
                        // Как и с фото: сначала отпускаем распознаватель, потом закрываем лист.
                        onCancel = {
                            holder.release()
                            close()
                        },
                    )
                    CaptureVoiceStep.NoPermission -> CaptureMessage(
                        title = stringResource(R.string.capture_mic_perm_title),
                        hint = stringResource(R.string.capture_mic_perm_hint),
                        note = if (canAsk) null else stringResource(R.string.capture_perm_blocked),
                        primary = stringResource(if (canAsk) R.string.capture_perm_allow else R.string.capture_perm_settings),
                        onPrimary = {
                            if (canAsk) askMic.launch(Manifest.permission.RECORD_AUDIO) else openSystemSettings(context)
                        },
                        secondary = stringResource(R.string.capture_close),
                        onSecondary = close,
                    )
                    is CaptureVoiceStep.Problem -> VoiceProblemMessage(current.kind, onRetry = { open() }, onClose = close)
                }
            }
        }
    }
}

/** Сообщение об ошибке распознавателя речи: что случилось и что делать; «Повторить» и «Закрыть». */
@Composable
private fun VoiceProblemMessage(kind: VoiceProblem, onRetry: () -> Unit, onClose: () -> Unit) {
    val (title, hint) = when (kind) {
        VoiceProblem.NoMatch -> R.string.capture_voice_nomatch_title to R.string.capture_voice_nomatch
        VoiceProblem.Offline -> R.string.capture_voice_offline_title to R.string.capture_voice_offline
        VoiceProblem.Audio -> R.string.capture_voice_failed_title to R.string.capture_voice_audio
        VoiceProblem.Permission, VoiceProblem.Other -> R.string.capture_voice_failed_title to R.string.capture_voice_failed
    }
    CaptureMessage(
        title = stringResource(title),
        hint = stringResource(hint),
        warn = kind != VoiceProblem.NoMatch,
        primary = stringResource(R.string.capture_retry),
        onPrimary = onRetry,
        secondary = stringResource(R.string.capture_close),
        onSecondary = onClose,
    )
}

private fun firstResult(bundle: Bundle?): String? =
    bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

/** Запрос на диктовку: свободная речь, язык программы, частичные результаты, по возможности без сети. */
private fun speechIntent(context: Context): Intent {
    val tag = speechLanguageTag(context.resources.configuration.locales[0])
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
}

// --- Разрешения ---

private fun hasPermission(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** Настройки программы в системе: оттуда право включают, когда диалог уже не показывается. */
private fun openSystemSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        Log.w(CAPTURE_TAG, "App settings not found", e)
    }
}

/** Пока [active], при возвращении в программу проверяет право [permission] и, если его включили, зовёт [onGranted]. */
@Composable
private fun OnPermissionGranted(permission: String, active: Boolean, onGranted: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val latest by rememberUpdatedState(onGranted)
    DisposableEffect(activity, active) {
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (active && event == Lifecycle.Event.ON_RESUME && hasPermission(context, permission)) latest()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
}
