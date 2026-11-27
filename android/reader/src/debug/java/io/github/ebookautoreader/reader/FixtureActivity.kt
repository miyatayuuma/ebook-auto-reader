package io.github.ebookautoreader.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast

class FixtureActivity : Activity() {
    private val fixtures = listOf("join-a", "join-b", "body-medium", "body-dense", "body-bottom", "chapter")
    private lateinit var image: ImageView
    private var bitmap: Bitmap? = null
    private var page = 0
    private var down = 0f
    private lateinit var menu: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = fixtures.indexOf(intent.getStringExtra("page")).coerceAtLeast(0)
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        menu = SeekBar(this).apply {
            visibility = android.view.View.GONE
            setBackgroundColor(Color.rgb(51, 54, 62))
            progress = 40
            contentDescription = "自作ページのメニュー"
        }
        setContentView(FrameLayout(this).apply {
            addView(image, FrameLayout.LayoutParams(-1, -1))
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
    }

    override fun onResume() {
        DebugPages.visible = true
        super.onResume()
    }

    override fun onPause() {
        DebugPages.visible = false
        super.onPause()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> down = event.x
            MotionEvent.ACTION_UP -> {
                val distance = event.x - down
                if (kotlin.math.abs(distance) > image.width * 0.15f) {
                    val next = (page + if (distance > 0) 1 else -1).coerceIn(fixtures.indices)
                    if (next != page) {
                        page = next
                        display()
                    }
                } else if (page == fixtures.indexOf("body-bottom")) {
                    menu.visibility = if (menu.visibility == android.view.View.VISIBLE)
                        android.view.View.GONE else android.view.View.VISIBLE
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
