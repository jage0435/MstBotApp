package com.mstbot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/** 拖曳方向。 */
enum class Direction(val label: String) {
    RIGHT_UP("右上"), LEFT_UP("左上"), ALTERNATE("交替"), RANDOM("隨機")
}

class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("mst", Context.MODE_PRIVATE)

    var direction: Direction
        get() = Direction.entries.getOrElse(p.getInt("direction", 0)) { Direction.RIGHT_UP }
        set(v) = p.edit().putInt("direction", v.ordinal).apply()
    /** 手指按住到放開的總時間（含移動與停在終點）。 */
    var durationMs: Int
        get() = p.getInt("duration_ms", 500)
        set(v) = p.edit().putInt("duration_ms", v).apply()
    /** 每次在 ± 這個範圍內隨機加減，避免每發時間都一模一樣。 */
    var jitterMs: Int
        get() = p.getInt("jitter_ms", 30)
        set(v) = p.edit().putInt("jitter_ms", v).apply()
    /** 拖曳長度 = 螢幕寬度 × 這個比例。 */
    var length: Float
        get() = p.getFloat("length", 0.3f)
        set(v) = p.edit().putFloat("length", v).apply()
    /** 每幾秒滑一次；0 = 只滑一次。 */
    var intervalSec: Float
        get() = p.getFloat("interval_sec", 6f)
        set(v) = p.edit().putFloat("interval_sec", v).apply()
    /** 畫面上方出現「OK」（關卡結束）就自動停止。 */
    var autoStop: Boolean
        get() = p.getBoolean("auto_stop", true)
        set(v) = p.edit().putBoolean("auto_stop", v).apply()
    /** 只在畫面上方這個比例的範圍找「OK」（避開下方 SS 準備好的「OK」）。 */
    var roi: Float
        get() = p.getFloat("roi", 0.6f)
        set(v) = p.edit().putFloat("roi", v).apply()

    /** 滑動相關設定恢復預設（浮動面板位置不動）。 */
    fun resetSwipe() {
        p.edit().remove("direction").remove("duration_ms").remove("jitter_ms")
            .remove("length").remove("interval_sec").remove("auto_stop").remove("roi").apply()
    }

    var overlayX: Int
        get() = p.getInt("ov_x", 0)
        set(v) = p.edit().putInt("ov_x", v).apply()
    var overlayY: Int
        get() = p.getInt("ov_y", 400)
        set(v) = p.edit().putInt("ov_y", v).apply()
}

fun log(msg: String) = Log.i("MstBot", msg)
