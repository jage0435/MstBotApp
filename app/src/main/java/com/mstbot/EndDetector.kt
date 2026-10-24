package com.mstbot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 關卡結束偵測：截圖 → 只看上方 roi 比例的範圍 → 文字辨識找「OK」。
 *
 * 只看上方是因為戰鬥中 SS 準備好時，下方隊伍欄（約 y = 0.63～0.83）也會顯示「OK」。
 * 所有方法都會等截圖和辨識，只能在背景執行緒呼叫。
 */
class EndDetector(private val svc: BotService) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    class Result(val texts: List<String>, val okBox: Rect?, val roiBottom: Int) {
        val found get() = okBox != null
    }

    /** 截一張圖並辨識；截圖失敗回傳 null。saveTo 會存一張畫出 ROI 和 OK 位置的圖（校正用）。 */
    fun check(roi: Float, saveTo: File? = null): Result? {
        val full = svc.captureBlocking() ?: return null
        try {
            val bottom = (full.height * roi).toInt().coerceIn(1, full.height)
            val crop = Bitmap.createBitmap(full, 0, 0, full.width, bottom)
            val text = try {
                Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0)), 10, TimeUnit.SECONDS)
            } finally {
                if (crop !== full) crop.recycle()
            }
            val panel = svc.overlayRect()
            val texts = mutableListOf<String>()
            var ok: Rect? = null
            for (block in text.textBlocks) for (line in block.lines) {
                val lb = line.boundingBox
                if (panel != null && lb != null && panel.contains(lb.centerX(), lb.centerY())) continue
                texts += line.text
                for (e in line.elements) {
                    val box = e.boundingBox ?: continue
                    if (panel != null && panel.contains(box.centerX(), box.centerY())) continue
                    if (ok == null && isOk(e.text)) ok = box
                }
            }
            val r = Result(texts, ok, bottom)
            if (saveTo != null) saveDebug(full, r, saveTo)
            return r
        } finally {
            full.recycle()
        }
    }

    /** 連續兩次（隔約 0.5 秒）都看到 OK 才算，避免一閃而過的畫面誤判。 */
    fun ended(roi: Float): Boolean {
        val first = check(roi) ?: return false
        if (!first.found) return false
        Thread.sleep(500)
        val second = check(roi) ?: return false
        if (second.found) log("偵測到 OK：${second.okBox}")
        return second.found
    }

    fun close() = recognizer.close()

    private fun saveDebug(full: Bitmap, r: Result, f: File) {
        val bmp = full.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val p = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        p.color = Color.YELLOW
        c.drawLine(0f, r.roiBottom.toFloat(), bmp.width.toFloat(), r.roiBottom.toFloat(), p)
        r.okBox?.let {
            p.color = Color.GREEN
            c.drawRect(it, p)
        }
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    companion object {
        /** 「OK」「Ok」「0K」「OK!」都算；「BOOK」「OKAY」不算。 */
        fun isOk(s: String): Boolean =
            s.uppercase().replace('0', 'O').filter { it.isLetterOrDigit() } == "OK"
    }
}
