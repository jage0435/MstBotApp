package com.mstbot

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 只在 debug 版：模擬「關卡結束」畫面，測試 OK 偵測用。
 * adb shell am start -n com.mstbot/.OkTestActivity --es mode top     # 上方 30% 有 OK（應該停）
 * adb shell am start -n com.mstbot/.OkTestActivity --es mode bottom  # 只有下方 85% 有 OK（像 SS，不該停）
 */
class OkTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "top"
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(20, 40, 70)) }
        setContentView(root)
        root.post {
            fun ok(yFrac: Float, size: Float, color: Int) = TextView(this).apply {
                text = "OK"
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                root.addView(this, FrameLayout.LayoutParams(-1, -2).apply { topMargin = (root.height * yFrac).toInt() })
            }
            if (mode == "top") ok(0.3f, 56f, Color.WHITE)
            ok(0.85f, 28f, Color.YELLOW)
        }
    }
}
