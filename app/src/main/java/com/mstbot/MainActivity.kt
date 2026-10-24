package com.mstbot

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusHelp: TextView
    private lateinit var preview: DirectionPreview
    private lateinit var previewCaption: TextView
    private lateinit var cta: TextView
    private lateinit var ctaHint: TextView
    private val refreshers = mutableListOf<() -> Unit>()
    private lateinit var selectDirection: (Int) -> Unit

    /** 畫面開著時每 0.5 秒更新一次服務／執行狀態。 */
    private val poll = object : Runnable {
        override fun run() {
            refreshState()
            main.postDelayed(this, 500)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // 內容畫到系統列底下，自己用 insets 留空間（Android 15 起強制 edge-to-edge，舊版也一致）
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setBackgroundDrawable(ColorDrawable(Palette.bg))

        val content = column().apply { setPadding(dp(16), 0, dp(16), 0) }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        val bottom = buildBottomBar()
        // 狀態列底下的遮罩，捲動時內容不會跟時間、電量疊在一起
        val statusScrim = View(this).apply { setBackgroundColor(Palette.bg) }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Palette.bg)
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(statusScrim, FrameLayout.LayoutParams(MATCH, 0, Gravity.TOP))
            addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        }
        setContentView(root)

        buildHeader(content)
        buildStatusCard(content)
        buildDirectionCard(content)
        buildTimingCard(content)
        buildRepeatCard(content)
        buildAutoStopCard(content)
        buildFooter(content)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            statusScrim.layoutParams = statusScrim.layoutParams.apply { height = bars.top }
            bottom.setPadding(dp(16), dp(12), dp(16), bars.bottom + dp(12))
            bottom.post {
                content.setPadding(dp(16), bars.top + dp(12), dp(16), bottom.height + dp(16))
            }
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        refreshers.forEach { it() }
        main.post(poll)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(poll)
    }

    // ------------------------------------------------------------ 版面
    private fun buildHeader(page: LinearLayout) {
        page.add(label(getString(R.string.app_name), 26f, bold = true))
        page.add(label("從螢幕中心自動拖曳彈射", 14f, Palette.mutedFg), top = dp(2))
    }

    private fun buildStatusCard(page: LinearLayout) {
        val c = page.card("無障礙服務")
        val r = row()
        statusDot = View(this)
        r.add(statusDot, dp(10), dp(10))
        statusText = label("", 16f, bold = true)
        r.add(statusText, 0, WRAP, 1f).also {
            (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
        }
        val open = label("設定", 15f, Palette.accent, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = pressable(Palette.card, dpf(10f))
            contentDescription = "開啟無障礙設定"
            setOnClickListener { openA11ySettings() }
        }
        r.add(open, WRAP, dp(48))
        c.add(r, top = dp(6))
        statusHelp = label("找到「${getString(R.string.a11y_label)}」並開啟。開關若是灰色（Android 13 以上）：設定 → 應用程式 → ${getString(R.string.app_name)} → 右上角 ⋮ →「允許受限制的設定」。",
            13f, Palette.mutedFg)
        c.add(statusHelp, top = dp(6))
    }

    private fun buildDirectionCard(page: LinearLayout) {
        val c = page.card("方向")
        preview = DirectionPreview(this).apply {
            direction = prefs.direction
            length = prefs.length
            background = rounded(Palette.muted, dpf(12f))
        }
        c.add(preview, MATCH, dp(176), top = dp(12))
        previewCaption = label("", 14f, Palette.mutedFg).apply { gravity = Gravity.CENTER }
        c.add(previewCaption, top = dp(8))
        selectDirection = c.segmented(Direction.entries.map { it.label }, prefs.direction.ordinal) { i ->
            prefs.direction = Direction.entries[i]
            showDirection()
        }
        refreshers += { showDirection() }
        refreshers += c.slider("拖曳長度", 10, 45,
            get = { Math.round(prefs.length * 100) },
            set = { prefs.length = it / 100f; preview.length = prefs.length },
            format = { "螢幕寬 $it%" })
    }

    private fun showDirection() {
        val d = prefs.direction
        selectDirection(d.ordinal)
        preview.direction = d
        preview.length = prefs.length
        previewCaption.text = when (d) {
            Direction.RIGHT_UP -> "往右上拉，球往左下飛"
            Direction.LEFT_UP -> "往左上拉，球往右下飛"
            Direction.ALTERNATE -> "右上、左上輪流"
            Direction.RANDOM -> "每次隨機右上或左上"
        }
    }

    private fun buildTimingCard(page: LinearLayout) {
        val c = page.card("拖曳時間")
        refreshers += c.stepper("按住時間", "建議 480–500 ms",
            get = { prefs.durationMs.toDouble() }, set = { prefs.durationMs = it.toInt() },
            step = 10.0, min = 150.0, max = 2000.0, format = { "${it.toInt()} ms" })
        refreshers += c.stepper("隨機誤差", "每次 ± 隨機，避免太規律",
            get = { prefs.jitterMs.toDouble() }, set = { prefs.jitterMs = it.toInt() },
            step = 5.0, min = 0.0, max = 100.0, format = { if (it <= 0) "關閉" else "±${it.toInt()} ms" })
        c.add(label("先用 0.1 秒移到終點，停住到按住時間結束才放開。電腦版巨集常用 500 ms；彈不出去再調長到 600–800。",
            13f, Palette.mutedFg), top = dp(14))
    }

    private fun buildRepeatCard(page: LinearLayout) {
        val c = page.card("重複")
        refreshers += c.stepper("間隔", "設為 0 只滑一次",
            get = { prefs.intervalSec.toDouble() }, set = { prefs.intervalSec = it.toFloat() },
            step = 0.5, min = 0.0, max = 30.0,
            format = { if (it <= 0) "只一次" else if (it % 1.0 == 0.0) "${it.toInt()} 秒" else "$it 秒" })
    }

    private fun buildAutoStopCard(page: LinearLayout) {
        val c = page.card("關卡結束自動停止")
        refreshers += c.toggle("出現 OK 就停止", "打完關卡畫面上方會出現 OK",
            get = { prefs.autoStop }, set = { prefs.autoStop = it })
        refreshers += c.slider("偵測範圍", 30, 80,
            get = { Math.round(prefs.roi * 100) },
            set = { prefs.roi = it / 100f },
            format = { "畫面上方 $it%" })
        c.add(label("戰鬥中 SS 準備好時，下方隊伍欄也會出現 OK，所以只看上方。範圍太大會誤停，太小會抓不到 OK。",
            13f, Palette.mutedFg))
        val test = label("測試偵測（3 秒後截圖）", 15f, bold = true).apply {
            gravity = Gravity.CENTER
            background = pressable(Palette.muted, dpf(12f))
            setOnClickListener { testDetect() }
        }
        c.add(test, MATCH, dp(48), top = dp(14))
    }

    private fun testDetect() {
        val svc = BotService.instance
        if (svc == null) {
            Toast.makeText(this, "請先開啟無障礙服務", Toast.LENGTH_SHORT).show()
            return
        }
        svc.testDetect(3000)
        Toast.makeText(this, "請切到要測試的畫面，3 秒後截圖", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun buildFooter(page: LinearLayout) {
        val reset = label("恢復預設值", 15f, Palette.fg, bold = true).apply {
            gravity = Gravity.CENTER
            background = pressable(Palette.bg, dpf(12f), Palette.border)
            setOnClickListener {
                prefs.resetSwipe()
                refreshers.forEach { it() }
                Toast.makeText(this@MainActivity, "已恢復預設值", Toast.LENGTH_SHORT).show()
            }
        }
        val close = label("關閉 App", 15f, Palette.dangerText, bold = true).apply {
            gravity = Gravity.CENTER
            background = pressable(Palette.bg, dpf(12f), Palette.dangerText)
            setOnClickListener { confirmClose() }
        }
        val actions = row()
        actions.add(reset, 0, dp(48), 1f)
        actions.add(View(this), dp(12), 1)
        actions.add(close, 0, dp(48), 1f)
        page.add(actions, top = dp(16))
        page.add(label("也可以用遊戲畫面上的浮動面板開始／停止。面板請拖到角落，不要擋住螢幕中心。",
            13f, Palette.mutedFg), top = dp(16))
        page.add(label("⚠ 自動化操作違反遊戲使用條款，可能被停權，建議用小號測試。", 13f, Palette.warn), top = dp(8))
    }

    private fun buildBottomBar(): LinearLayout {
        val bar = column().apply {
            setBackgroundColor(Palette.bg)
            elevation = dpf(8f)
        }
        cta = label("", 17f, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { onCta() }
        }
        bar.add(cta, MATCH, dp(56))
        ctaHint = label("", 13f, Palette.mutedFg).apply { gravity = Gravity.CENTER }
        bar.add(ctaHint, top = dp(6))
        return bar
    }

    // ------------------------------------------------------------ 狀態
    private enum class State { OFF, WAITING, READY, RUNNING }

    private fun state(): State {
        if (BotService.isRunning) return State.RUNNING
        if (BotService.instance != null) return State.READY
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/") == true
        return if (enabled) State.WAITING else State.OFF
    }

    private fun refreshState() {
        val s = state()
        val (dotColor, text) = when (s) {
            State.OFF -> Palette.danger to "尚未開啟"
            State.WAITING -> Palette.warn to "已開啟，等待連線…"
            State.READY -> Palette.accent to "已就緒"
            State.RUNNING -> Palette.accent to "執行中"
        }
        statusDot.background = rounded(dotColor, dpf(5f))
        statusText.text = text
        statusHelp.visibility = if (s == State.OFF || s == State.WAITING) View.VISIBLE else View.GONE

        // 主按鈕永遠是「下一步該做的事」
        val (bg, fg, label) = when (s) {
            State.OFF, State.WAITING -> Triple(Palette.accent, Palette.onAccent, "開啟無障礙服務")
            State.READY -> Triple(Palette.accent, Palette.onAccent, "開始")
            State.RUNNING -> Triple(Palette.danger, Color.WHITE, "停止")
        }
        cta.text = label
        cta.setTextColor(fg)
        cta.background = pressable(bg, dpf(14f))
        ctaHint.text = when (s) {
            State.OFF, State.WAITING -> "開啟後回到這裡就能開始"
            State.READY -> "按下後 App 退到背景，請在 3 秒內切回遊戲"
            State.RUNNING -> "設定改了會在下一次滑動生效"
        }
    }

    private fun onCta() {
        when (state()) {
            State.OFF, State.WAITING -> openA11ySettings()
            State.RUNNING -> BotService.instance?.stop()
            State.READY -> {
                BotService.instance?.start(delayMs = 3000) ?: return
                Toast.makeText(this, "請切到遊戲畫面，3 秒後開始", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)   // 退回上一個 App（通常就是遊戲）
            }
        }
        refreshState()
    }

    private fun confirmClose() {
        val svc = BotService.instance
        if (svc == null) {
            finishAndRemoveTask()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("關閉 App？")
            .setMessage("會停止滑動、移除浮動面板，並關掉無障礙服務。下次使用要再到無障礙設定開啟。")
            .setPositiveButton("關閉") { _, _ ->
                svc.close()
                finishAndRemoveTask()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openA11ySettings() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}
