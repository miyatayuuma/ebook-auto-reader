package io.github.ebookautoreader.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.Toast

class FixtureActivity : Activity() {
    private val fixtures = listOf("join-a", "join-b", "body-medium", "chapter")
    private lateinit var image: ImageView
    private var bitmap: Bitmap? = null
    private var page = 0
    private var down = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        setContentView(image)
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
