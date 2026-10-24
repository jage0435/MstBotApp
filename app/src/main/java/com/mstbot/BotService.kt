package com.mstbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 無障礙服務：按下開始後，從螢幕中心往右上或左上拖曳（dispatchGesture），
 * 依設定的間隔重複，直到按停止，或偵測到關卡結束（畫面上方出現「OK」）。
 */
class BotService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BotService? = null
            private set

        val isRunning: Boolean get() = instance?.worker?.isAlive == true

        /** 手指從中心移到終點花的時間；剩下的時間停在終點不動，再放開。 */
        private const val MOVE_MS = 100L
    }

    private val main = Handler(Looper.getMainLooper())
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private var overlay: Overlay? = null
    private var detector: EndDetector? = null
    @Volatile private var lastShotAt = 0L
    @Volatile private var lastShotError = 0

    @Volatile private var worker: Thread? = null
    @Volatile private var stopFlag = false

    // ------------------------------------------------------------ 生命週期
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = Overlay(this).also { it.show() }
        detector = EndDetector(this)
        log("無障礙服務已啟動")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        stop()
        overlay?.hide()
        overlay = null
        detector?.close()
        detector = null
        instance = null
    }

    fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    // ------------------------------------------------------------ 截圖
    /** 同步截圖（只能在背景執行緒呼叫）。系統限制約每 1/3 秒才能截一次，太快會重試。 */
    fun captureBlocking(): Bitmap? {
        repeat(3) {
            val wait = 350 - (SystemClock.uptimeMillis() - lastShotAt)
            if (wait > 0) Thread.sleep(wait)
            lastShotAt = SystemClock.uptimeMillis()
            val latch = CountDownLatch(1)
            var out: Bitmap? = null
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    out = try {
                        Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) {
                        null
                    } finally {
                        hb.close()
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    lastShotError = errorCode
                    latch.countDown()
                }
            })
            if (!latch.await(5, TimeUnit.SECONDS)) return null
            out?.let { return it }
            if (lastShotError != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                log("截圖失敗（錯誤碼 $lastShotError）")
                return null
            }
        }
        return null
    }

    /** 浮動面板在螢幕上的範圍（辨識時排除，避免讀到面板上的字）。 */
    fun overlayRect(): Rect? = overlay?.screenRect()

    // ------------------------------------------------------------ 手勢
    private fun screenSize(): Pair<Int, Int> {
        val b = getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        if (b != null) return b.width() to b.height()
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /** 送出手勢並等它完成（只能在背景執行緒呼叫，在主執行緒會 deadlock）。 */
    private fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean {
        val g = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var ok = false
        main.post {
            val accepted = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            if (!accepted) latch.countDown()
        }
        latch.await(stroke.duration + 3000, TimeUnit.MILLISECONDS)
        return ok
    }

    /**
     * 從螢幕中心拖到 (cx + dx, cy + dy)：先花 MOVE_MS 移過去，停在終點到總時間 durationMs 再放開。
     * 怪物彈珠要「拉住一段時間」才算出手，跟電腦版巨集的「按下 → 移到終點 → 停 500 ms → 放開」一樣。
     */
    fun swipeFromCenter(dx: Double, dy: Double, durationMs: Long) {
        val (w, h) = screenSize()
        val x1 = w / 2f
        val y1 = h / 2f
        val x2 = (x1 + dx).toFloat().coerceIn(0f, w - 1f)
        val y2 = (y1 + dy).toFloat().coerceIn(0f, h - 1f)
        val move = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, MOVE_MS, true)
        if (!dispatch(move)) {
            log("拖曳沒有完成（可能被系統取消）")
            return
        }
        val hold = move.continueStroke(Path().apply { moveTo(x2, y2) }, 0, (durationMs - MOVE_MS).coerceAtLeast(1), false)
        if (!dispatch(hold)) log("放開的手勢沒有完成（可能被系統取消）")
    }

    // ------------------------------------------------------------ 開始 / 停止
    fun start(delayMs: Long = 0) {
        if (isRunning) return
        stopFlag = false
        overlay?.setRunning(true)
        worker = thread(name = "mst-swipe") {
            var reason: String? = null
            try {
                if (delayMs > 0) Thread.sleep(delayMs)
                var count = 0
                while (!stopFlag) {
                    val p = Prefs(this)
                    if (p.autoStop && ended(p)) {
                        reason = "關卡結束，已停止"
                        break
                    }
                    val left = when (p.direction) {
                        Direction.RIGHT_UP -> false
                        Direction.LEFT_UP -> true
                        Direction.ALTERNATE -> count % 2 == 1
                        Direction.RANDOM -> Random.nextBoolean()
                    }
                    val jitter = if (p.jitterMs > 0) Random.nextInt(-p.jitterMs, p.jitterMs + 1) else 0
                    val ms = (p.durationMs + jitter).coerceAtLeast(MOVE_MS.toInt() + 50).toLong()
                    // 45 度：x、y 各走長度的 1/√2
                    val d = p.length * screenSize().first / sqrt(2.0)
                    count++
                    val label = "第 $count 次 ${if (left) "左上" else "右上"} $ms ms"
                    log(label)
                    main.post { overlay?.setStatus(label) }
                    swipeFromCenter(if (left) -d else d, -d, ms)
                    if (p.intervalSec <= 0f) break
                    // 等待間隔時每秒查一次，關卡一結束就停，不用等到下一發
                    val until = SystemClock.uptimeMillis() + (p.intervalSec * 1000).toLong()
                    while (!stopFlag && reason == null) {
                        val left = until - SystemClock.uptimeMillis()
                        if (left <= 0) break
                        Thread.sleep(minOf(left, 1000L))
                        if (p.autoStop && SystemClock.uptimeMillis() < until - 300 && ended(p)) {
                            reason = "關卡結束，已停止"
                        }
                    }
                    if (reason != null) break
                }
                reason?.let {
                    log(it)
                    toast(it)
                }
            } catch (e: InterruptedException) {
                log("使用者停止")
            } catch (e: Exception) {
                log("執行錯誤：$e")
                toast("執行錯誤：${e.message}")
            } finally {
                main.post {
                    overlay?.setRunning(false)
                    reason?.let { overlay?.setStatus(it) }
                }
            }
        }
    }

    private fun ended(p: Prefs): Boolean = detector?.ended(p.roi) == true

    /**
     * 「測試偵測」：等 delayMs（讓使用者切回遊戲）後截一張，回報 ROI 內讀到的字。
     * 標好 ROI 線（黃）和 OK 位置（綠）的圖存在 files/detect_test.png，可以用 adb pull 拿。
     */
    fun testDetect(delayMs: Long) {
        if (isRunning) {
            toast("執行中不能測試")
            return
        }
        thread(name = "mst-detect-test") {
            Thread.sleep(delayMs)
            val f = File(getExternalFilesDir(null), "detect_test.png")
            val r = detector?.check(Prefs(this).roi, f)
            val seen = r?.texts?.joinToString("、")?.take(120)
            val msg = when {
                r == null -> "截圖失敗"
                r.found -> "有找到 OK\n讀到：$seen"
                r.texts.isEmpty() -> "沒有找到 OK（範圍內沒有讀到文字）"
                else -> "沒有找到 OK\n讀到：$seen"
            }
            log("測試偵測：$msg")
            toast(msg)
            main.post { overlay?.setStatus(if (r?.found == true) "測試：有找到" else "測試：沒找到") }
        }
    }

    fun stop() {
        stopFlag = true
        worker?.interrupt()
    }

    /** 關閉 App：停止滑動並關掉無障礙服務（浮動面板跟著消失），下次要再到無障礙設定開啟。 */
    fun close() {
        stop()
        log("使用者關閉 App")
        disableSelf()
    }

    fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
