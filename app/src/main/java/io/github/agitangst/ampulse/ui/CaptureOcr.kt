package io.github.agitangst.ampulse.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

// Фото рецепта → текст на телефоне, без сети (задача 3.1): снимок готовится (поворот по EXIF, до 2000 px, оттенки
// серого), Tesseract читает его на русском и английском. Работает на Dispatchers.Default; отмена корутины останавливает
// распознавание (TessBaseAPI.stop), движок и снимок освобождаются в любом случае.

/** Почему фото не распознано (текст для человека выбирает экран). */
internal enum class OcrFailure {
    /** Файл не открылся или это не картинка. */
    Photo,

    /** Не хватило памяти на снимок. */
    Memory,

    /** Модели не скопировались или движок не запустился. */
    Engine,
}

internal class OcrException(val failure: OcrFailure, cause: Throwable? = null) : Exception(cause)

/**
 * Читает текст с фото [uri] и возвращает его уже очищенным ([cleanRecognizedText]); пустая строка — ничего не
 * прочитано. [onProgress] получает проценты (0–100) с потока распознавания. Ошибки — [OcrException]; отмена —
 * обычная `CancellationException`.
 */
internal suspend fun recognizePhoto(context: Context, uri: Uri, onProgress: (Int) -> Unit): String =
    withContext(Dispatchers.Default) {
        val app = context.applicationContext
        var bitmap: Bitmap? = null
        var tess: TessBaseAPI? = null
        try {
            val photo = try {
                CapturePhotoPrep.load(app, uri)
            } catch (e: OutOfMemoryError) {
                throw OcrException(OcrFailure.Memory, e)
            } catch (e: IOException) {
                throw OcrException(OcrFailure.Photo, e)
            } catch (e: SecurityException) {
                throw OcrException(OcrFailure.Photo, e)
            }
            bitmap = photo
            ensureActive()
            val dataPath = try {
                TessData.install(app)
            } catch (e: IOException) {
                throw OcrException(OcrFailure.Engine, e)
            }
            ensureActive()

            val api = TessBaseAPI(TessBaseAPI.ProgressNotifier { onProgress(it.getPercent()) })
            tess = api
            if (!api.init(dataPath, TessData.LANGUAGE)) throw OcrException(OcrFailure.Engine)
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setImage(photo)

            // getUTF8Text блокирует поток; отмена не прерывает его сама — её передаём движку через stop().
            val raw = coroutineScope {
                val work = async { api.getUTF8Text().orEmpty() }
                try {
                    work.await()
                } catch (e: CancellationException) {
                    api.stop()
                    throw e
                }
            }
            cleanRecognizedText(raw)
        } finally {
            tess?.recycle()
            bitmap?.recycle()
        }
    }

/** Модели Tesseract: rus и eng лежат в `assets/tessdata`, при первом запуске копируются в `filesDir/tessdata`. */
internal object TessData {
    /** Языки через «+»: по-русски и по-английски в одном проходе (названия лекарств бывают латиницей). */
    const val LANGUAGE = "rus+eng"

    private const val DIR = "tessdata"
    private val LANGS = listOf("rus", "eng")

    /** Поменяли модели в assets — поднять число, и они скопируются заново. */
    private const val MODELS_VERSION = "1"

    /**
     * Копирует модели, если их ещё нет или версия другая, и возвращает папку-родителя `tessdata` — её ждёт
     * `TessBaseAPI.init`. Копия идёт во временный файл и переименовывается: оборванный запуск не оставит битую модель.
     */
    fun install(context: Context): String {
        val root = context.filesDir
        val dir = File(root, DIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
        val marker = File(dir, ".version")
        val ready = marker.isFile && marker.readText() == MODELS_VERSION &&
            LANGS.all { File(dir, "$it.traineddata").length() > 0 }
        if (!ready) {
            for (lang in LANGS) {
                val target = File(dir, "$lang.traineddata")
                val part = File(dir, "$lang.traineddata.part")
                context.assets.open("$DIR/$lang.traineddata").use { input ->
                    part.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.exists() && !target.delete()) throw IOException("Cannot replace ${target.path}")
                if (!part.renameTo(target)) throw IOException("Cannot move ${part.path}")
            }
            marker.writeText(MODELS_VERSION)
        }
        return root.absolutePath
    }
}

/** Подготовка снимка: чтение с уменьшением, поворот по EXIF, до [CAPTURE_MAX_SIDE] px, оттенки серого. */
private object CapturePhotoPrep {
    fun load(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        fun open() = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image: $uri")

        val orientation = try {
            open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL // PNG и скриншоты без EXIF
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = captureSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val source = open().use { BitmapFactory.decodeStream(it, null, options) } ?: throw IOException("Cannot decode $uri")

        // Поворот, уменьшение и серый цвет — одним проходом, без промежуточных копий.
        val matrix = orientationMatrix(orientation)
        val turned = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()).also { matrix.mapRect(it) }
        val scale = captureScale(turned.width().roundToInt(), turned.height().roundToInt())
        matrix.postTranslate(-turned.left, -turned.top)
        matrix.postScale(scale, scale)
        val width = (turned.width() * scale).roundToInt().coerceAtLeast(1)
        val height = (turned.height() * scale).roundToInt().coerceAtLeast(1)

        val result = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            source.recycle()
            throw e
        }
        val gray = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(result).apply {
            drawColor(Color.WHITE) // у PNG с прозрачностью фон иначе станет чёрным
            drawBitmap(source, matrix, gray)
        }
        source.recycle()
        return result
    }

    private fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                setRotate(180f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
}
