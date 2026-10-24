package com.mstbot

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** 不用 XML layout，直接用程式組畫面的小工具。深色主題，顏色都從 Palette 拿。 */

object Palette {
    val bg = Color.parseColor("#0F172A")
    val card = Color.parseColor("#1B2336")
    val muted = Color.parseColor("#272F42")
    val border = Color.parseColor("#334155")
    val fg = Color.parseColor("#F8FAFC")
    val mutedFg = Color.parseColor("#94A3B8")
    val accent = Color.parseColor("#22C55E")
    val onAccent = Color.parseColor("#0F172A")
    val danger = Color.parseColor("#DC2626")
    /** 深色底上的紅字（#DC2626 對比不夠 4.5:1）。 */
    val dangerText = Color.parseColor("#F87171")
    val warn = Color.parseColor("#F59E0B")
    val ripple = Color.argb(60, 255, 255, 255)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, strokeColor: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeColor != null) setStroke(strokeWidth, strokeColor)
}

/** 有按下回饋（ripple）的圓角背景。 */
fun Context.pressable(color: Int, radius: Float, strokeColor: Int? = null): Drawable =
    RippleDrawable(ColorStateList.valueOf(Palette.ripple),
        rounded(color, radius, strokeColor, dp(1)), rounded(Color.WHITE, radius))

val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.label(s: String, size: Float = 15f, color: Int = Palette.fg, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.25f)
    }

fun LinearLayout.add(v: View, w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, top: Int = 0): View {
    addView(v, LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top })
    return v
}

fun Context.column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** 卡片：標題 + 內容，回傳內容區。 */
fun LinearLayout.card(title: String): LinearLayout {
    val c = context.column().apply {
        background = rounded(Palette.card, context.dpf(16f))
        setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(16))
    }
    c.add(context.label(title, 13f, Palette.mutedFg, bold = true).apply { letterSpacing = 0.06f })
    add(c, top = context.dp(12))
    return c
}

/** 分段選擇（例如方向）。回傳「設定選取項目」的函式。 */
fun LinearLayout.segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): (Int) -> Unit {
    val ctx = context
    val bar = ctx.row().apply {
        background = rounded(Palette.muted, ctx.dpf(12f))
        setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
    }
    val items = options.mapIndexed { i, s ->
        ctx.label(s, 15f, bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { onSelect(i) }
        }.also { bar.add(it, 0, ctx.dp(44), 1f) }
    }
    val select = { idx: Int ->
        items.forEachIndexed { i, tv ->
            val on = i == idx
            tv.isSelected = on
            tv.background = if (on) ctx.pressable(Palette.accent, ctx.dpf(9f)) else ctx.pressable(Palette.muted, ctx.dpf(9f))
            tv.setTextColor(if (on) Palette.onAccent else Palette.fg)
        }
    }
    select(selected)
    add(bar, top = ctx.dp(12))
    return select
}

/** 按住會連續觸發（第一次立刻、0.4 秒後每 70 ms 一次）。無障礙點兩下走 onClick，只觸發一次。 */
@SuppressLint("ClickableViewAccessibility")
private fun View.repeatOnHold(action: () -> Unit) {
    val h = Handler(Looper.getMainLooper())
    val repeat = object : Runnable {
        override fun run() {
            action()
            h.postDelayed(this, 70)
        }
    }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.drawableHotspotChanged(e.x, e.y)
                v.isPressed = true
                action()
                h.postDelayed(repeat, 400)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                h.removeCallbacks(repeat)
            }
        }
        true
    }
    setOnClickListener { action() }
}

/**
 * 「標題／說明 ··· [−] 數值 [+]」一列。回傳更新顯示的函式。
 * 數值存在 Prefs，按鈕直接改 Prefs，不用另外按儲存。
 */
fun LinearLayout.stepper(
    title: String, helper: String,
    get: () -> Double, set: (Double) -> Unit,
    step: Double, min: Double, max: Double,
    format: (Double) -> String,
): () -> Unit {
    val ctx = context
    val r = ctx.row()
    val texts = ctx.column()
    texts.add(ctx.label(title, 16f))
    texts.add(ctx.label(helper, 13f, Palette.mutedFg))
    r.add(texts, 0, WRAP, 1f)

    val value = ctx.label("", 17f, bold = true).apply {
        gravity = Gravity.CENTER
        fontFeatureSettings = "tnum"
        minWidth = ctx.dp(76)
    }
    fun btn(sign: String, desc: String, delta: Double) = ctx.label(sign, 22f, bold = true).apply {
        gravity = Gravity.CENTER
        contentDescription = "$desc$title"
        background = ctx.pressable(Palette.muted, ctx.dpf(12f))
        repeatOnHold {
            val v = (get() + delta).coerceIn(min, max)
            set(Math.round(v / step) * step)
            value.text = format(get())
        }
    }
    r.add(btn("−", "減少", -step), ctx.dp(48), ctx.dp(48))
    r.add(value, WRAP, WRAP)
    r.add(btn("+", "增加", step), ctx.dp(48), ctx.dp(48))
    add(r, top = ctx.dp(16))

    val refresh = { value.text = format(get()) }
    refresh()
    return refresh
}

/** 「標題／說明 ··· 開關」一列，整列都可以點。回傳更新顯示的函式。 */
fun LinearLayout.toggle(title: String, helper: String, get: () -> Boolean, set: (Boolean) -> Unit): () -> Unit {
    val ctx = context
    val r = ctx.row().apply {
        background = ctx.pressable(Palette.card, ctx.dpf(12f))
        minimumHeight = ctx.dp(56)
    }
    val texts = ctx.column()
    texts.add(ctx.label(title, 16f))
    texts.add(ctx.label(helper, 13f, Palette.mutedFg))
    r.add(texts, 0, WRAP, 1f)
    val sw = Switch(ctx).apply {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Palette.accent, Palette.mutedFg))
        trackTintList = ColorStateList(states, intArrayOf(Color.argb(110, 34, 197, 94), Palette.border))
        contentDescription = title
        setOnCheckedChangeListener { _, on -> set(on) }
    }
    r.add(sw, WRAP, WRAP)
    r.setOnClickListener { sw.toggle() }
    add(r, top = ctx.dp(12))
    val refresh = { sw.isChecked = get() }
    refresh()
    return refresh
}

/** 標題＋數值＋滑桿。progress 以整數表示，回傳更新顯示的函式。 */
fun LinearLayout.slider(
    title: String, min: Int, max: Int,
    get: () -> Int, set: (Int) -> Unit, format: (Int) -> String,
): () -> Unit {
    val ctx = context
    val head = ctx.row()
    head.add(ctx.label(title, 16f), 0, WRAP, 1f)
    val value = ctx.label("", 17f, bold = true).apply { fontFeatureSettings = "tnum" }
    head.add(value, WRAP, WRAP)
    add(head, top = ctx.dp(18))
    val bar = SeekBar(ctx).apply {
        this.min = min
        this.max = max
        progressTintList = ColorStateList.valueOf(Palette.accent)
        thumbTintList = ColorStateList.valueOf(Palette.accent)
        progressBackgroundTintList = ColorStateList.valueOf(Palette.border)
        contentDescription = title
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) set(p)
                value.text = format(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }
    add(bar, MATCH, ctx.dp(48), top = ctx.dp(4))
    val refresh = {
        bar.progress = get()
        value.text = format(get())
    }
    refresh()
    return refresh
}
