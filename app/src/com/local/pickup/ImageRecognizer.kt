package com.local.pickup

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Offline OCR: selected image is temporary; only reviewed text can enter records. */
class ImageRecognizer(context: Context) {
    private val context = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    @Volatile private var closed = false
    companion object { private val modelLock = Any() }

    fun cancel() { generation.incrementAndGet() }
    fun close() { closed = true; cancel(); main.removeCallbacksAndMessages(null); worker.shutdownNow() }

    fun recognize(uri: Uri, done: (String?, String?) -> Unit) {
        if (closed) return
        val token = generation.incrementAndGet()
        worker.execute {
            var bitmap: Bitmap? = null
            var api: TessBaseAPI? = null
            var temporary: File? = null
            try {
                val imageFile = File.createTempFile("pickup-image-", ".img", context.cacheDir)
                temporary = imageFile
                context.contentResolver.openInputStream(uri)?.use { input ->
                    imageFile.outputStream().use { output ->
                        val bytes = ByteArray(8192); var size = 0L
                        while (true) {
                            val n = input.read(bytes); if (n < 0) break
                            size += n
                            if (size > 20L * 1024 * 1024) throw IOException("图片超过20 MB，请先裁剪或压缩")
                            if (closed || token != generation.get()) return@execute
                            output.write(bytes, 0, n)
                        }
                    }
                } ?: throw IOException("无法读取图片，请重新选择")
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(imageFile.path, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("图片格式不受支持或文件损坏")
                var sample = 1
                while (maxOf(options.outWidth, options.outHeight) / sample > 4096 ||
                    options.outWidth.toLong() * options.outHeight / sample / sample > 8_000_000) sample *= 2
                options.inJustDecodeBounds = false; options.inSampleSize = sample
                options.inPreferredConfig = Bitmap.Config.ARGB_8888; options.inMutable = true
                bitmap = BitmapFactory.decodeFile(imageFile.path, options) ?: throw IOException("无法解码图片")
                Canvas(bitmap).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
                val orientation = try { ExifInterface(imageFile.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } catch (_: IOException) { 1 }
                val matrix = Matrix().apply {
                    when (orientation) {
                        2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                        5 -> { setRotate(90f); postScale(-1f, 1f) }
                        6 -> setRotate(90f); 7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f)
                    }
                }
                if (!matrix.isIdentity) {
                    val rotated = Bitmap.createBitmap(bitmap!!, 0, 0, bitmap!!.width, bitmap!!.height, matrix, true)
                    if (rotated !== bitmap) { bitmap!!.recycle(); bitmap = rotated }
                }
                if (closed || token != generation.get()) return@execute
                val data = File(context.filesDir, "ocr")
                synchronized(modelLock) {
                    val directory = File(data, "tessdata"); directory.mkdirs()
                    for (language in listOf("chi_sim", "eng")) {
                        val target = File(directory, "$language.traineddata")
                        if (!target.exists()) {
                            val part = File(directory, "$language.part")
                            try {
                                context.assets.open("tessdata/$language.traineddata").use { input -> part.outputStream().use { input.copyTo(it) } }
                                if (!part.renameTo(target)) throw IOException("无法准备中文识别模型")
                            } finally { part.delete() }
                        }
                    }
                }
                if (closed || token != generation.get()) return@execute
                api = TessBaseAPI()
                fun pass(mode: Int): String {
                    if (!api!!.init(data.path, "chi_sim+eng", TessBaseAPI.OEM_LSTM_ONLY)) throw IOException("无法初始化图片识别")
                    api!!.setVariable("preserve_interword_spaces", "1")
                    api!!.setVariable("user_defined_dpi", "300")
                    api!!.pageSegMode = mode
                    api!!.setImage(bitmap)
                    val raw = api!!.getUTF8Text().orEmpty().take(20000)
                    return refineCodes(api!!, bitmap!!, raw, data)
                }
                // Screenshots contain maps and separate cards; sparse segmentation keeps fields apart.
                // Still compare page segmentation even when one pass already found a pickup code.
                var text = pass(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
                if (!closed && token == generation.get()) {
                    val page = pass(TessBaseAPI.PageSegMode.PSM_AUTO)
                    fun quality(raw: String): Int {
                        val scan = ImageParcelParser.parse(raw)
                        return scan.parcels.size.coerceAtMost(12) * 100 + scan.confidence
                    }
                    if (quality(page) > quality(text)) text = page
                }
                val recognized = text
                main.post { if (!closed && token == generation.get()) done(recognized, null) }
            } catch (_: OutOfMemoryError) {
                main.post { if (!closed && token == generation.get()) done(null, "图片尺寸过大，请裁剪取件信息区域后重试") }
            } catch (error: Exception) {
                val message = if (error is IOException || error is SecurityException) error.message else "图片识别失败，请换一张清晰图片重试"
                main.post { if (!closed && token == generation.get()) done(null, message) }
            } catch (_: LinkageError) {
                main.post { if (!closed && token == generation.get()) done(null, "当前设备无法加载识别组件，仍可手动添加") }
            } finally {
                api?.recycle(); bitmap?.recycle(); temporary?.delete()
            }
        }
    }

    /** Read only labelled code lines again with the Latin model; never change arbitrary numbers. */
    private fun refineCodes(api: TessBaseAPI, bitmap: Bitmap, raw: String, data: File): String {
        val lines = mutableListOf<Pair<String, Rect>>()
        val iterator = api.resultIterator ?: return raw
        try {
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(2)?.trim().orEmpty()
                if (text.isNotBlank()) lines.add(text to iterator.getBoundingRect(2))
            } while (lines.size < 200 && iterator.next(2))
        } finally { iterator.delete() }
        var previousLabel = false
        val candidates = lines.filter { (text, _) ->
            val compact = text.replace(Regex("\\s+"), "")
            val candidate = previousLabel && compact.length in 3..16 &&
                compact.any { it.isDigit() } && compact.matches(Regex("[A-Za-z0-9\\-/_]+"))
            previousLabel = Regex("取件码|取货码|提货码|提取码|开柜码").containsMatchIn(compact)
            candidate
        }.take(12)
        if (candidates.isEmpty() || closed) return raw
        if (!api.init(data.path, "eng", TessBaseAPI.OEM_LSTM_ONLY)) return raw
        api.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-")
        api.setVariable("user_defined_dpi", "300")
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
        var result = raw
        for ((original, rect) in candidates) {
            if (closed) break
            val left = (rect.left - 12).coerceAtLeast(0); val top = (rect.top - 12).coerceAtLeast(0)
            val right = (rect.right + 12).coerceAtMost(bitmap.width); val bottom = (rect.bottom + 12).coerceAtMost(bitmap.height)
            if (right <= left || bottom <= top) continue
            val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            try {
                api.setImage(crop)
                val code = api.getUTF8Text().orEmpty().replace(Regex("\\s+"), "")
                if (SmsParser.valid(code) && code.length == original.replace(Regex("\\s+"), "").length) result = result.replace(original, code)
            } finally { if (crop !== bitmap) crop.recycle() }
        }
        return result
    }
}
