package io.github.ebookautoreader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Insets
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.roundToInt

internal class FloatingControls(
    context: Context,
    private val manager: WindowManager,
    primaryAction: () -> Unit,
    endAction: () -> Unit,
    probeAction: () -> Unit,
    turnTestAction: () -> Unit,
    adjustRate: (Int) -> Unit,
    manualTouch: () -> Unit,
) {
    private val theme = ContextThemeWrapper(context, android.R.style.Theme_Material_Light)
    private val placement = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private var right = placement.getBoolean("panel_right", true)
    private var heightFraction = placement.getFloat("panel_y", 0.35f).coerceIn(0f, 1f)
    private var expanded = false
    private val surface = Color.rgb(239, 247, 243)
    private val ink = Color.rgb(25, 55, 47)
    private val panel = LinearLayout(theme).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(14).toFloat()
        }
    }
    private val handle = object : TextView(theme) {
        override fun performClick(): Boolean = super.performClick()
    }.apply {
        gravity = Gravity.CENTER
        setTextColor(ink)
        textSize = 22f
        isFocusable = true
    }
    private val body = LinearLayout(theme).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), 0, dp(10), dp(6))
        visibility = View.GONE
    }
    private val label = TextView(theme).apply {
        setTextColor(ink)
        textSize = 14f
    }
    private val primary = button(primaryAction)
    private val end = button(endAction)
    private val probe = button(probeAction)
    private val turnTest = button(turnTestAction)
    private val slower = button { adjustRate(-1) }.apply {
        text = "−"
        contentDescription = "読み上げ速度を下げる"
    }
    private val faster = button { adjustRate(1) }.apply {
        text = "＋"
        contentDescription = "読み上げ速度を上げる"
    }
    private val speed = TextView(theme).apply {
        gravity = Gravity.CENTER
        setTextColor(ink)
        textSize = 16f
    }
    private val params = overlay(dp(48), dp(48)).apply { gravity = Gravity.TOP or Gravity.START }
    private val guardParams = overlay(-1, -1).apply { flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE }
    // The guard consumes ACTION_DOWN during reading, and does not represent a clickable control.
    @SuppressLint("ClickableViewAccessibility")
    private val guard = View(theme).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) manualTouch()
            true
        }
    }
    private var attached = false
    private var suspended = false
    private val placed = Rect()
    private var insets: Insets? = null
    private var pressed = false
    private var dragged = false
    private var startX = 0f
    private var startY = 0f
    private var initialX = 0
    private var initialY = 0
    private var status = ""
    private var active = false

    private val longPress = Runnable {
        dragged = true
        handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    init {
        panel.addView(handle, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(body)
        body.addView(label)
        val actions = LinearLayout(theme)
        actions.addView(primary, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(end, LinearLayout.LayoutParams(0, dp(48), 1f))
        body.addView(actions)
        val rateControls = LinearLayout(theme)
        rateControls.addView(slower, LinearLayout.LayoutParams(dp(52), dp(48)))
        rateControls.addView(speed, LinearLayout.LayoutParams(0, dp(48), 1f))
        rateControls.addView(faster, LinearLayout.LayoutParams(dp(52), dp(48)))
        body.addView(rateControls)
        body.addView(probe, LinearLayout.LayoutParams(-1, dp(48)))
        body.addView(turnTest, LinearLayout.LayoutParams(-1, dp(48)))
        panel.setOnApplyWindowInsetsListener { view, received ->
            val safe = received.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            if (insets != safe) {
                insets = safe
                view.post { if (attached) reflow() }
            }
            received
        }
        handle.setOnClickListener { expand(!expanded) }
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    dragged = false
                    startX = event.rawX
                    startY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    handle.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (dragged) {
                        position(initialX + dx.roundToInt(), initialY + dy.roundToInt())
                    } else if (hypot(dx, dy) > ViewConfiguration.get(theme).scaledTouchSlop) {
                        pressed = false
                        handle.removeCallbacks(longPress)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handle.removeCallbacks(longPress)
                    if (dragged) {
                        val bounds = manager.currentWindowMetrics.bounds
                        right = event.rawX >= bounds.exactCenterX()
                        val maxY = (availableHeight() - params.height).coerceAtLeast(0)
                        if (maxY > 0) heightFraction = params.y.toFloat() / maxY
                        placement.edit().putBoolean("panel_right", right).putFloat("panel_y", heightFraction).apply()
                        dragged = false
                        reflow()
                    } else if (pressed && event.actionMasked == MotionEvent.ACTION_UP) {
                        handle.performClick()
                    }
                    pressed = false
                }
            }
            true
        }
        updateHandle()
    }

    fun attach() {
        reflow()
        addWindows()
        attached = true
    }

    private fun addWindows() {
        manager.addView(guard, guardParams)
        try {
            manager.addView(panel, params)
            placed.set(params.x, params.y, params.x + params.width, params.y + params.height)
        } catch (error: Exception) {
            manager.removeViewImmediate(guard)
            throw error
        }
    }

    private fun removeWindows() {
        handle.removeCallbacks(longPress)
        pressed = false
        dragged = false
        manager.removeViewImmediate(panel)
        manager.removeViewImmediate(guard)
    }

    fun suspendWindows() {
        if (!attached || suspended) return
        removeWindows()
        suspended = true
    }

    fun windowsDetached() = !panel.isAttachedToWindow && !guard.isAttachedToWindow

    fun resumeWindows() {
        if (!attached || !suspended) return
        addWindows()
        suspended = false
    }

    fun detach() {
        if (!attached) return
        attached = false
        if (!suspended) removeWindows()
    }

    fun expand(show: Boolean) {
        if (expanded == show) return
        expanded = show
        body.visibility = if (show) View.VISIBLE else View.GONE
        updateHandle()
        reflow()
    }

    fun render(
        message: String, playing: Boolean, action: String, canAct: Boolean, canEnd: Boolean,
        canProbe: Boolean, canTestTurn: Boolean, confirmingTurn: Boolean, rate: Float,
    ) {
        val turnLabel = if (confirmingTurn) "次へ1ページ進んだ・復元" else "ページ送り・復元テスト"
        val formatted = String.format(java.util.Locale.JAPAN, "%.2f倍", rate)
        if (status == message && active == playing && primary.text == action && primary.isEnabled == canAct &&
            end.isEnabled == canEnd && probe.isEnabled == canProbe && turnTest.isEnabled == canTestTurn &&
            speed.text == formatted && turnTest.text == turnLabel) return
        status = message
        active = playing
        label.text = message
        primary.text = action
        primary.isEnabled = canAct
        end.text = "終了"
        end.isEnabled = canEnd
        probe.text = "画面取得テスト"
        probe.isEnabled = canProbe
        turnTest.text = turnLabel
        turnTest.isEnabled = canTestTurn
        probe.visibility = if (canProbe) View.VISIBLE else View.GONE
        turnTest.visibility = if (canTestTurn) View.VISIBLE else View.GONE
        speed.text = formatted
        slower.isEnabled = rate > MIN_SPEECH_RATE
        faster.isEnabled = rate < MAX_SPEECH_RATE
        updateHandle()
        if (expanded && !dragged) reflow()
    }

    fun guardTouches(enabled: Boolean, keepScreenOn: Boolean) {
        panel.keepScreenOn = keepScreenOn
        val flags = if (enabled) guardParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else guardParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (flags == guardParams.flags) return
        guardParams.flags = flags
        if (attached && !suspended) manager.updateViewLayout(guard, guardParams)
    }

    fun reflow() {
        if (dragged) return
        val sides = (handle.layoutParams as LinearLayout.LayoutParams)
        sides.gravity = if (right) Gravity.END else Gravity.START
        handle.layoutParams = sides
        params.width = minOf(dp(if (expanded) 244 else 48), availableWidth())
        panel.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availableHeight(), View.MeasureSpec.AT_MOST))
        params.height = panel.measuredHeight
        position(if (right) availableWidth() - params.width else 0,
            ((availableHeight() - params.height) * heightFraction).roundToInt())
    }

    private fun position(x: Int, y: Int) {
        params.x = x.coerceIn(0, (availableWidth() - params.width).coerceAtLeast(0))
        params.y = y.coerceIn(0, (availableHeight() - params.height).coerceAtLeast(0))
        val right = params.x + params.width
        val bottom = params.y + params.height
        if (attached && !suspended && (placed.left != params.x || placed.top != params.y || placed.right != right ||
                placed.bottom != bottom)) {
            manager.updateViewLayout(panel, params)
            placed.set(params.x, params.y, right, bottom)
        }
    }

    private fun updateHandle() {
        handle.text = when {
            expanded -> "−"
            active -> "Ⅱ"
            else -> "▶"
        }
        handle.contentDescription = if (expanded) "操作パネルを折りたたむ" else "操作パネルを開く。$status"
    }

    private fun button(action: () -> Unit) = Button(theme).apply {
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun availableWidth(): Int {
        val metrics = manager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(1)
    }

    private fun availableHeight(): Int {
        val metrics = manager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
    }

    private fun dp(value: Int) = (value * theme.resources.displayMetrics.density).roundToInt()

    private fun overlay(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { fitInsetsTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() }
}
