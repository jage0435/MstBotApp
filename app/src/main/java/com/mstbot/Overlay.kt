package com.mstbot

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 浮動控制面板（用無障礙服務的 overlay，不需要額外的「顯示在其他應用程式上層」權限）。
 * 握把拖曳移動（放開會貼齊左右邊）· 開始/停止 · 開啟 App
 *
 * 注意：面板會擋住它底下的觸控，請拖到角落，不要放在螢幕中心。
 */
class Overlay(private val svc: BotService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(svc)
    private fun dp(v: Int) = svc.dp(v)

    private val root = svc.column().apply {
        background = rounded(Color.argb(235, 27, 35, 54), svc.dpf(20f), Palette.border, dp(1))
        setPadding(dp(4), dp(4), dp(8), dp(6))
        elevation = svc.dpf(6f)
    }
    private val handle = IconView(svc, Icon.GRIP, Palette.mutedFg).apply {
        contentDescription = "拖曳移動面板"
    }
    private val btnRun = IconView(svc, Icon.PLAY, Palette.onAccent).apply {
        background = svc.pressable(Palette.accent, svc.dpf(22f))
        contentDescription = "開始"
        setOnClickListener { if (BotService.isRunning) svc.stop() else svc.start() }
    }
    private val btnApp = IconView(svc, Icon.MENU, Palette.fg).apply {
        background = svc.pressable(Palette.muted, svc.dpf(22f))
        contentDescription = "開啟設定"
        setOnClickListener { svc.openApp() }
    }
    private val dot = View(svc)
    private val status = svc.label(IDLE, 12f, Palette.mutedFg).apply {
        fontFeatureSettings = "tnum"
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    // 固定寬度：狀態文字變長變短時面板不會跟著伸縮（貼右邊時才不會超出螢幕）
    private val lp = WindowManager.LayoutParams(
        dp(156),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.overlayX
        y = prefs.overlayY
    }

    private var shown = false
    private var snap: ValueAnimator? = null

    companion object {
        private const val IDLE = "待命"
    }

    init {
        val r = svc.row()
        r.add(handle, dp(32), dp(48))
        r.add(btnRun, dp(44), dp(44))
        r.add(View(svc), 0, 1, 1f)
        r.add(btnApp, dp(44), dp(44))
        root.add(r)
        val s = svc.row().apply { setPadding(dp(10), 0, 0, 0) }
        s.add(dot, dp(6), dp(6))
        s.add(status, 0, WRAP, 1f).also {
            (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
        }
        root.add(s, top = dp(4))
        setDot(false)
        // 存的位置可能來自別的螢幕寬度或舊版面板，確保整個面板在螢幕內
        lp.x = lp.x.coerceIn(0, (screenWidth() - lp.width - dp(8)).coerceAtLeast(0))
        enableDrag()
    }

    private fun setDot(running: Boolean) {
        dot.background = rounded(if (running) Palette.accent else Palette.mutedFg, svc.dpf(3f))
    }

    private fun screenWidth(): Int =
        wm?.maximumWindowMetrics?.bounds?.width() ?: svc.resources.displayMetrics.widthPixels

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag() {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snap?.cancel()
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = (startY + (e.rawY - touchY).toInt()).coerceAtLeast(0)
                    if (shown) wm?.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> snapToEdge()
            }
            true
        }
    }

    /** 放開後貼齊比較近的左右邊，讓螢幕中間保持乾淨。 */
    private fun snapToEdge() {
        val margin = dp(8)
        val maxX = screenWidth() - lp.width - margin
        val target = if (lp.x + lp.width / 2 < screenWidth() / 2) margin else maxX
        if (abs(target - lp.x) < 2) {
            prefs.overlayX = lp.x
            prefs.overlayY = lp.y
            return
        }
        snap = ValueAnimator.ofInt(lp.x, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lp.x = it.animatedValue as Int
                if (shown) wm?.updateViewLayout(root, lp)
            }
            start()
        }
        prefs.overlayX = target
        prefs.overlayY = lp.y
    }

    fun show() {
        if (shown || wm == null) return
        wm.addView(root, lp)
        shown = true
    }

    fun hide() {
        if (!shown) return
        snap?.cancel()
        try {
            wm?.removeView(root)
        } catch (e: Exception) {
            // view 已經被系統移除
        }
        shown = false
    }

    fun setRunning(running: Boolean) {
        btnRun.icon = if (running) Icon.STOP else Icon.PLAY
        btnRun.tint = if (running) Color.WHITE else Palette.onAccent
        btnRun.background = svc.pressable(if (running) Palette.danger else Palette.accent, svc.dpf(22f))
        btnRun.contentDescription = if (running) "停止" else "開始"
        setDot(running)
        if (!running) status.text = IDLE
        lp.flags = if (running) lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        if (shown) wm?.updateViewLayout(root, lp)
    }

    /** 面板目前在螢幕上的範圍（文字辨識會排除這塊）。 */
    fun screenRect(): Rect? {
        if (!shown || root.width == 0) return null
        val m = dp(8)
        return Rect(lp.x - m, lp.y - m, lp.x + root.width + m, lp.y + root.height + m)
    }

    fun setStatus(text: String) {
        status.text = text
    }
}

enum class Icon { GRIP, PLAY, STOP, MENU }

/** 用 Canvas 畫的小圖示（不用 emoji，各家字型顯示才會一致）。 */
class IconView(ctx: Context, icon: Icon, tint: Int) : View(ctx) {
    var icon = icon
        set(v) {
            field = v
            invalidate()
        }
    var tint = tint
        set(v) {
            field = v
            paint.color = v
            invalidate()
        }

    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        when (icon) {
            Icon.GRIP -> {
                paint.style = Paint.Style.FILL
                for (i in -1..1) for (j in listOf(-1, 1)) {
                    canvas.drawCircle(cx + j * 3.5f * d, cy + i * 6f * d, 1.8f * d, paint)
                }
            }
            Icon.PLAY -> {
                paint.style = Paint.Style.FILL
                val s = 8f * d
                path.reset()
                path.moveTo(cx - s * 0.6f, cy - s)
                path.lineTo(cx + s, cy)
                path.lineTo(cx - s * 0.6f, cy + s)
                path.close()
                canvas.drawPath(path, paint)
            }
            Icon.STOP -> {
                paint.style = Paint.Style.FILL
                val s = 6.5f * d
                canvas.drawRoundRect(cx - s, cy - s, cx + s, cy + s, 2 * d, 2 * d, paint)
            }
            Icon.MENU -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * d
                for (i in -1..1) canvas.drawLine(cx - 7 * d, cy + i * 5.5f * d, cx + 7 * d, cy + i * 5.5f * d, paint)
            }
        }
    }
}
