package io.github.ebookautoreader.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast

class FixtureActivity : Activity() {
    private val fixtures = listOf("join-a", "join-b", "short-a", "short-b", "short-c", "body-narrow",
        "body-medium", "body-dense", "body-bottom", "chapter")
    private lateinit var image: ImageView
    private var bitmap: Bitmap? = null
    private var page = 0
    private var down = 0f
    private lateinit var menu: SeekBar
    private lateinit var pageLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = fixtures.indexOf(intent.getStringExtra("page")).coerceAtLeast(0)
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT)
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
                    when (action) {
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id -> turn(-1)
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id -> turn(1)
                        else -> super.performAccessibilityAction(host, action, args)
                    }
            }
        }
        pageLabel = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            textSize = 12f
        }
        menu = SeekBar(this).apply {
            visibility = android.view.View.GONE
            setBackgroundColor(Color.rgb(51, 54, 62))
            progress = 40
            contentDescription = "自作ページのメニュー"
        }
        setContentView(FrameLayout(this).apply {
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(pageLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            val menuHeight = (resources.displayMetrics.density * 120).toInt()
            addView(menu, FrameLayout.LayoutParams(-1, menuHeight, Gravity.BOTTOM))
        })
        display()
    }

    private fun display() {
        val next = try {
            assets.open("pages/${fixtures[page]}.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: java.io.IOException) {
            Toast.makeText(this, "自作ページがAPKに含まれていません。", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        image.setImageBitmap(next)
        bitmap?.recycle()
        bitmap = next
        pageLabel.text = "${page + 1}/${fixtures.size}"
        DebugPages.page = page
        ReaderDiagnostics.event("fixture display page=$page")
    }

    private fun turn(delta: Int): Boolean {
        val next = page + delta
        if (next !in fixtures.indices) return false
        page = next
        display()
        return true
    }

    override fun onResume() {
        DebugPages.page = page
        DebugPages.visible = true
        super.onResume()
    }

    override fun onPause() {
        DebugPages.visible = false
        DebugPages.page = null
        super.onPause()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) {
            ReaderDiagnostics.event("fixture touch action=${MotionEvent.actionToString(event.action)}" +
                " x=${event.rawX.toInt()} y=${event.rawY.toInt()} pointers=${event.pointerCount}" +
                " durationMs=${event.eventTime - event.downTime} page=$page")
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> down = event.x
            MotionEvent.ACTION_UP -> {
                val distance = event.x - down
                val x = event.x - image.left
                when {
                    kotlin.math.abs(distance) > image.width * 0.15f -> turn(if (distance > 0) 1 else -1)
                    x < image.width * 0.17f -> turn(-1)
                    x > image.width * 0.83f -> turn(1)
                    page == fixtures.indexOf("body-bottom") -> {
                        menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                }
            }
        }
        return true
    }

    override fun onDestroy() {
        image.setImageDrawable(null)
        bitmap?.recycle()
        super.onDestroy()
    }
}
