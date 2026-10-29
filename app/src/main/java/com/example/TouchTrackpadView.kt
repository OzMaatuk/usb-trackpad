package com.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Unified Workstation Input Surface combining:
 * 1. High-precision relative USB Trackpad (1F move, tap, double-tap drag, 2F right-click & scroll)
 * 2. Bare-metal 14x5 Ortholinear Pro Keyboard (OrtholinearKeyboardEngine)
 * 3. Three instant View Modes: SPLIT_VIEW, TRACKPAD_ONLY, and FULL_KEYBOARD (+ OLED Stealth Mode)
 *
 * Uses per-pointer (pointerId) zone isolation so users can simultaneously hold modifiers
 * (Ctrl, Alt, Shift, Super) on the keyboard while dragging, clicking, or scrolling the trackpad.
 */
class TouchTrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class ViewMode(val shortLabel: String) {
        SPLIT_VIEW("View: Split"),
        TRACKPAD_ONLY("View: Pad"),
        FULL_KEYBOARD("View: Keys");

        fun next(): ViewMode = when (this) {
            SPLIT_VIEW -> FULL_KEYBOARD
            FULL_KEYBOARD -> TRACKPAD_ONLY
            TRACKPAD_ONLY -> SPLIT_VIEW
        }
    }

    private val prefs = context.getSharedPreferences("usb_workstation_prefs", Context.MODE_PRIVATE)
    private val density = resources.displayMetrics.density

    var server: TrackpadServer? = null
        set(value) {
            field = value
            keyboardEngine.server = value
        }

    val keyboardEngine = OrtholinearKeyboardEngine(
        hostView = this,
        density = density,
        onTelemetry = { gesture, detail ->
            lastGestureLabel = gesture
            lastDeltaLabel = detail
        }
    )

    // Touch Tuning Constants
    private val tapTimeoutMs = 230L
    private val doubleTapTimeoutMs = 280L
    private val touchSlopPx = 18f * density
    private val scrollThresholdPx = 16f * density

    var viewMode: ViewMode = loadSavedViewMode()
        set(value) {
            if (field != value) {
                keyboardEngine.cancelAllPointers()
                resetTrackpadPointers()
                field = value
                prefs.edit().putString("view_mode", value.name).apply()
                if (width > 0 && height > 0) updateLayoutRects(width.toFloat(), height.toFloat())
                invalidate()
            }
        }

    var sensitivity: Float = prefs.getFloat("sensitivity", 1.20f).coerceIn(0.25f, 4.0f)
        set(value) {
            field = value.coerceIn(0.25f, 4.0f)
            prefs.edit().putFloat("sensitivity", field).apply()
            invalidate()
        }

    var showButtonBar: Boolean = prefs.getBoolean("show_buttons", true)
        set(value) {
            field = value
            prefs.edit().putBoolean("show_buttons", value).apply()
            if (width > 0 && height > 0) updateLayoutRects(width.toFloat(), height.toFloat())
            invalidate()
        }

    var isStealthBlackMode: Boolean = false
        set(value) {
            if (field != value) {
                keyboardEngine.cancelAllPointers()
                resetTrackpadPointers()
                field = value
                if (width > 0 && height > 0) updateLayoutRects(width.toFloat(), height.toFloat())
                invalidate()
            }
        }

    // Per-pointer Trackpad Ownership (up to 10 fingers)
    private val trackpadOwnsPointer = BooleanArray(10)
    private var primaryPadPointerId = MotionEvent.INVALID_POINTER_ID
    private var secondPadPointerId = MotionEvent.INVALID_POINTER_ID

    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var downTime = 0L
    private var totalMovement = 0f
    private var accumDx = 0f
    private var accumDy = 0f

    private var lastTapUpTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var isDraggingWithDoubleTap = false

    private var twoFingerDownTime = 0L
    private var lastScrollY = 0f
    private var twoFingerMovement = 0f
    private var accumScrollY = 0f

    // Visual Telemetry
    private var isPadTouching = false
    private var touchIndicatorX = 0f
    private var touchIndicatorY = 0f
    private var lastGestureLabel = "Ready"
    private var lastDeltaLabel = "dx: 0, dy: 0"

    // Layout Rectangles
    private val statusCardRect = RectF()
    private val modeToggleRect = RectF()
    private val buttonBarToggleRect = RectF()
    private val stealthToggleRect = RectF()
    private val sensMinusRect = RectF()
    private val sensPlusRect = RectF()

    private val trackpadRect = RectF()
    private val clickBarRect = RectF()
    private val leftButtonRect = RectF()
    private val middleButtonRect = RectF()
    private val rightButtonRect = RectF()

    private var activeBottomButtonPointerId = MotionEvent.INVALID_POINTER_ID
    private var activeBottomButtonCode: Short = 0

    // Pre-allocated Paints (zero allocation in onDraw)
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#141720")
        style = Paint.Style.FILL
    }
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#252A38")
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val statusDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14.5f * density
        isFakeBoldText = true
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 11.5f * density
    }
    private val dimTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#687086")
        textSize = 11f * density
    }
    private val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#232836")
        textSize = 13.5f * density
        textAlign = Paint.Align.CENTER
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val buttonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#262B38")
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val buttonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#12151E")
        style = Paint.Style.FILL
    }
    private val buttonPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#153B47")
        style = Paint.Style.FILL
    }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C5CCE0")
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val touchRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val touchDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
        alpha = 180
    }

    init {
        setBackgroundColor(Color.parseColor("#0A0A0C"))
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun loadSavedViewMode(): ViewMode {
        val raw = prefs.getString("view_mode", ViewMode.SPLIT_VIEW.name) ?: ViewMode.SPLIT_VIEW.name
        return try {
            ViewMode.valueOf(raw)
        } catch (_: Exception) {
            ViewMode.SPLIT_VIEW
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateLayoutRects(w.toFloat(), h.toFloat())
    }

    private fun updateLayoutRects(fw: Float, fh: Float) {
        if (isStealthBlackMode) {
            stealthToggleRect.set(12f * density, 16f * density, 220f * density, 56f * density)
            trackpadRect.set(0f, 0f, fw, fh)
            clickBarRect.setEmpty()
            keyboardEngine.updateBounds(0f, 0f, 0f, 0f)
            return
        }

        val isLandscape = fw > fh * 1.15f
        val pad = 10f * density
        val topInset = if (isLandscape) 14f * density else 36f * density
        val contentTop: Float

        if (isLandscape) {
            // Single ultra-compact 42dp workstation header bar in landscape
            val barH = 42f * density
            statusCardRect.set(pad, topInset, fw - pad, topInset + barH)
            val pillTop = topInset + 5f * density
            val pillBottom = topInset + barH - 5f * density
            val rightEdge = fw - pad - 6f * density

            sensPlusRect.set(rightEdge - 34f * density, pillTop, rightEdge, pillBottom)
            sensMinusRect.set(sensPlusRect.left - 38f * density, pillTop, sensPlusRect.left - 4f * density, pillBottom)
            stealthToggleRect.set(sensMinusRect.left - 58f * density, pillTop, sensMinusRect.left - 6f * density, pillBottom)
            buttonBarToggleRect.set(stealthToggleRect.left - 76f * density, pillTop, stealthToggleRect.left - 6f * density, pillBottom)
            modeToggleRect.set(buttonBarToggleRect.left - 96f * density, pillTop, buttonBarToggleRect.left - 6f * density, pillBottom)

            contentTop = statusCardRect.bottom + 4f * density
        } else {
            // Portrait: clean 50dp status strip + 34dp evenly-proportioned control pill row
            val cardH = 50f * density
            statusCardRect.set(pad, topInset, fw - pad, topInset + cardH)

            val pillTop = statusCardRect.bottom + 6f * density
            val pillH = 32f * density
            val pillBottom = pillTop + pillH
            val gap = 6f * density
            val totalAvail = fw - pad * 2f

            val plusW = 36f * density
            val minusW = 36f * density
            val oledW = 54f * density
            val btnsW = 72f * density
            val modeW = (totalAvail - plusW - minusW - oledW - btnsW - gap * 4f).coerceAtLeast(84f * density)

            var curX = pad
            modeToggleRect.set(curX, pillTop, curX + modeW, pillBottom)
            curX = modeToggleRect.right + gap
            buttonBarToggleRect.set(curX, pillTop, curX + btnsW, pillBottom)
            curX = buttonBarToggleRect.right + gap
            stealthToggleRect.set(curX, pillTop, curX + oledW, pillBottom)
            curX = stealthToggleRect.right + gap
            sensMinusRect.set(curX, pillTop, curX + minusW, pillBottom)
            curX = sensMinusRect.right + gap
            sensPlusRect.set(curX, pillTop, fw - pad, pillBottom)

            contentTop = pillBottom + 6f * density
        }

        val availH = (fh - contentTop).coerceAtLeast(100f)

        when (viewMode) {
            ViewMode.TRACKPAD_ONLY -> {
                keyboardEngine.updateBounds(0f, 0f, 0f, 0f)
                if (showButtonBar) {
                    val barH = 58f * density
                    layoutClickBar(0f, fh - barH, fw, fh)
                    trackpadRect.set(0f, contentTop, fw, fh - barH)
                } else {
                    clickBarRect.setEmpty()
                    trackpadRect.set(0f, contentTop, fw, fh)
                }
            }

            ViewMode.SPLIT_VIEW -> {
                val kbRatio = if (isLandscape) 0.57f else 0.46f
                val kbH = (availH * kbRatio).coerceIn(195f * density, availH * 0.68f)
                val kbTop = fh - kbH
                keyboardEngine.updateBounds(0f, kbTop, fw, fh)

                if (showButtonBar) {
                    val barH = 38f * density
                    layoutClickBar(0f, kbTop - barH, fw, kbTop)
                    trackpadRect.set(0f, contentTop, fw, kbTop - barH)
                } else {
                    clickBarRect.setEmpty()
                    trackpadRect.set(0f, contentTop, fw, kbTop)
                }
            }

            ViewMode.FULL_KEYBOARD -> {
                trackpadRect.setEmpty()
                if (showButtonBar) {
                    val barH = 40f * density
                    layoutClickBar(0f, contentTop, fw, contentTop + barH)
                    keyboardEngine.updateBounds(0f, contentTop + barH, fw, fh)
                } else {
                    clickBarRect.setEmpty()
                    keyboardEngine.updateBounds(0f, contentTop, fw, fh)
                }
            }
        }
    }

    private fun layoutClickBar(left: Float, top: Float, right: Float, bottom: Float) {
        clickBarRect.set(left, top, right, bottom)
        val w = right - left
        val midW = 68f * density
        val sideW = (w - midW) / 2f
        leftButtonRect.set(left, top, left + sideW, bottom)
        middleButtonRect.set(left + sideW, top, left + sideW + midW, bottom)
        rightButtonRect.set(left + sideW + midW, top, right, bottom)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val actionIdx = event.actionIndex
        val pointerId = event.getPointerId(actionIdx)
        val x = event.getX(actionIdx)
        val y = event.getY(actionIdx)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // 1. Top HUD Controls (on primary finger down)
                if (action == MotionEvent.ACTION_DOWN && handleHudTap(x, y)) {
                    return true
                }

                // 2. Ortholinear Keyboard Zone (independent multi-touch per pointerId)
                if (!isStealthBlackMode && viewMode != ViewMode.TRACKPAD_ONLY &&
                    keyboardEngine.bounds.contains(x, y)
                ) {
                    keyboardEngine.handleTouchDown(pointerId, x, y)
                    return true
                }

                // 3. Mouse Click Bar Zone
                if (!isStealthBlackMode && showButtonBar && !clickBarRect.isEmpty &&
                    clickBarRect.contains(x, y) && activeBottomButtonPointerId == MotionEvent.INVALID_POINTER_ID
                ) {
                    activeBottomButtonPointerId = pointerId
                    activeBottomButtonCode = when {
                        leftButtonRect.contains(x, y) -> TrackpadServer.BTN_LEFT
                        middleButtonRect.contains(x, y) -> TrackpadServer.BTN_MIDDLE
                        else -> TrackpadServer.BTN_RIGHT
                    }
                    server?.sendButton(activeBottomButtonCode, TrackpadServer.STATE_PRESS)
                    keyboardEngine.onTrackpadInteraction()
                    lastGestureLabel = when (activeBottomButtonCode) {
                        TrackpadServer.BTN_LEFT -> "Left Hold"
                        TrackpadServer.BTN_RIGHT -> "Right Hold"
                        else -> "Middle Hold"
                    }
                    invalidate()
                    return true
                }

                // 4. Trackpad Surface Zone
                if (!trackpadRect.isEmpty && trackpadRect.contains(x, y) && pointerId in 0..9) {
                    handleTrackpadPointerDown(event, pointerId, x, y)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                keyboardEngine.handleTouchMove(event)
                handleTrackpadMove(event)
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (keyboardEngine.ownsPointer(pointerId)) {
                    keyboardEngine.handleTouchUp(pointerId)
                } else if (pointerId == activeBottomButtonPointerId) {
                    if (activeBottomButtonCode > 0) {
                        server?.sendButton(activeBottomButtonCode, TrackpadServer.STATE_RELEASE)
                        activeBottomButtonCode = 0
                    }
                    activeBottomButtonPointerId = MotionEvent.INVALID_POINTER_ID
                    lastGestureLabel = "Ready"
                    invalidate()
                } else if (pointerId in 0..9 && trackpadOwnsPointer[pointerId]) {
                    handleTrackpadPointerUp(pointerId)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                keyboardEngine.cancelAllPointers()
                if (activeBottomButtonCode > 0) {
                    server?.sendButton(activeBottomButtonCode, TrackpadServer.STATE_RELEASE)
                    activeBottomButtonCode = 0
                }
                activeBottomButtonPointerId = MotionEvent.INVALID_POINTER_ID
                resetTrackpadPointers()
                invalidate()
            }
        }
        return true
    }

    private fun handleHudTap(x: Float, y: Float): Boolean {
        if (stealthToggleRect.contains(x, y)) {
            isStealthBlackMode = !isStealthBlackMode
            return true
        }
        if (isStealthBlackMode) return false

        if (modeToggleRect.contains(x, y)) {
            viewMode = viewMode.next()
            lastGestureLabel = viewMode.shortLabel
            return true
        }
        if (buttonBarToggleRect.contains(x, y)) {
            showButtonBar = !showButtonBar
            return true
        }
        if (sensMinusRect.contains(x, y)) {
            sensitivity -= 0.10f
            lastDeltaLabel = String.format(Locale.US, "Speed: %.1fx", sensitivity)
            return true
        }
        if (sensPlusRect.contains(x, y)) {
            sensitivity += 0.10f
            lastDeltaLabel = String.format(Locale.US, "Speed: %.1fx", sensitivity)
            return true
        }
        return false
    }

    private fun handleTrackpadPointerDown(event: MotionEvent, pointerId: Int, x: Float, y: Float) {
        trackpadOwnsPointer[pointerId] = true
        val padCount = activePadPointerCount()

        if (padCount == 1) {
            primaryPadPointerId = pointerId
            lastX = x
            lastY = y
            startX = x
            startY = y
            downTime = SystemClock.uptimeMillis()
            totalMovement = 0f
            accumDx = 0f
            accumDy = 0f

            isPadTouching = true
            touchIndicatorX = x
            touchIndicatorY = y

            val timeSinceLastTap = downTime - lastTapUpTime
            val distFromLastTap = distance(startX, lastTapX, startY, lastTapY)
            if (timeSinceLastTap < doubleTapTimeoutMs && distFromLastTap < touchSlopPx * 2f) {
                isDraggingWithDoubleTap = true
                server?.sendButton(TrackpadServer.BTN_LEFT, TrackpadServer.STATE_PRESS)
                lastGestureLabel = "Drag-Select"
            } else {
                isDraggingWithDoubleTap = false
                lastGestureLabel = "Tracking"
            }
        } else if (padCount == 2) {
            secondPadPointerId = pointerId
            twoFingerDownTime = SystemClock.uptimeMillis()
            lastScrollY = averagePadY(event)
            twoFingerMovement = 0f
            accumScrollY = 0f
            lastGestureLabel = "2F Scroll"
        }
        invalidate()
    }

    private fun handleTrackpadMove(event: MotionEvent) {
        val padCount = activePadPointerCount()
        if (padCount == 0) return

        if (padCount == 1 && primaryPadPointerId != MotionEvent.INVALID_POINTER_ID) {
            val idx = event.findPointerIndex(primaryPadPointerId)
            if (idx != -1) {
                val px = event.getX(idx)
                val py = event.getY(idx)
                touchIndicatorX = px
                touchIndicatorY = py

                val rawDx = (px - lastX) * sensitivity
                val rawDy = (py - lastY) * sensitivity
                lastX = px
                lastY = py

                accumDx += rawDx
                accumDy += rawDy

                val stepDx = accumDx.toInt()
                val stepDy = accumDy.toInt()
                if (stepDx != 0 || stepDy != 0) {
                    accumDx -= stepDx
                    accumDy -= stepDy
                    totalMovement += abs(rawDx) + abs(rawDy)
                    server?.sendMove(stepDx.toShort(), stepDy.toShort())
                    lastDeltaLabel = "dx:$stepDx dy:$stepDy"
                    if (!isDraggingWithDoubleTap) lastGestureLabel = "Cursor"
                }
                invalidate()
            }
        } else if (padCount >= 2) {
            val currentAvgY = averagePadY(event)
            val deltaY = currentAvgY - lastScrollY
            lastScrollY = currentAvgY

            accumScrollY += deltaY
            twoFingerMovement += abs(deltaY)

            while (accumScrollY >= scrollThresholdPx) {
                server?.sendScroll(1.toShort(), 0.toShort())
                keyboardEngine.onTrackpadInteraction()
                accumScrollY -= scrollThresholdPx
                lastGestureLabel = "Scroll ↑"
                lastDeltaLabel = "wheel: +1"
            }
            while (accumScrollY <= -scrollThresholdPx) {
                server?.sendScroll((-1).toShort(), 0.toShort())
                keyboardEngine.onTrackpadInteraction()
                accumScrollY += scrollThresholdPx
                lastGestureLabel = "Scroll ↓"
                lastDeltaLabel = "wheel: -1"
            }
            invalidate()
        }
    }

    private fun handleTrackpadPointerUp(pointerId: Int) {
        val padCountBefore = activePadPointerCount()
        trackpadOwnsPointer[pointerId] = false
        val upTime = SystemClock.uptimeMillis()

        if (padCountBefore == 2) {
            if (upTime - twoFingerDownTime < tapTimeoutMs && twoFingerMovement < touchSlopPx) {
                server?.sendButton(TrackpadServer.BTN_RIGHT, TrackpadServer.STATE_CLICK)
                keyboardEngine.onTrackpadInteraction()
                lastGestureLabel = "Right Click"
            }
            secondPadPointerId = MotionEvent.INVALID_POINTER_ID
            // Re-anchor remaining single finger so cursor doesn't jump
            primaryPadPointerId = firstActivePadPointer()
        } else if (padCountBefore == 1) {
            isPadTouching = false
            if (isDraggingWithDoubleTap) {
                server?.sendButton(TrackpadServer.BTN_LEFT, TrackpadServer.STATE_RELEASE)
                keyboardEngine.onTrackpadInteraction()
                isDraggingWithDoubleTap = false
                lastTapUpTime = 0L
                lastGestureLabel = "Drag Done"
            } else if (upTime - downTime < tapTimeoutMs && totalMovement < touchSlopPx) {
                server?.sendButton(TrackpadServer.BTN_LEFT, TrackpadServer.STATE_CLICK)
                keyboardEngine.onTrackpadInteraction()
                lastTapUpTime = upTime
                lastTapX = startX
                lastTapY = startY
                lastGestureLabel = "Left Click"
            } else {
                lastTapUpTime = 0L
                lastGestureLabel = "Ready"
            }
            primaryPadPointerId = MotionEvent.INVALID_POINTER_ID
        }
        invalidate()
    }

    private fun resetTrackpadPointers() {
        if (isDraggingWithDoubleTap) {
            server?.sendButton(TrackpadServer.BTN_LEFT, TrackpadServer.STATE_RELEASE)
            isDraggingWithDoubleTap = false
        }
        for (i in trackpadOwnsPointer.indices) trackpadOwnsPointer[i] = false
        primaryPadPointerId = MotionEvent.INVALID_POINTER_ID
        secondPadPointerId = MotionEvent.INVALID_POINTER_ID
        isPadTouching = false
    }

    private fun activePadPointerCount(): Int {
        var count = 0
        for (owns in trackpadOwnsPointer) if (owns) count++
        return count
    }

    private fun firstActivePadPointer(): Int {
        for (i in trackpadOwnsPointer.indices) if (trackpadOwnsPointer[i]) return i
        return MotionEvent.INVALID_POINTER_ID
    }

    private fun averagePadY(event: MotionEvent): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            val pid = event.getPointerId(i)
            if (pid in 0..9 && trackpadOwnsPointer[pid]) {
                sum += event.getY(i)
                count++
            }
        }
        return if (count > 0) sum / count else lastScrollY
    }

    private fun distance(x1: Float, x2: Float, y1: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val isConnected = server?.isClientConnected == true

        if (isStealthBlackMode) {
            canvas.drawColor(Color.BLACK)
            statusDotPaint.color = if (isConnected) Color.parseColor("#00E676") else Color.parseColor("#FF5252")
            canvas.drawCircle(24f * density, 34f * density, 5.5f * density, statusDotPaint)
            canvas.drawText("OLED Stealth (Tap to exit)", 38f * density, 38f * density, dimTextPaint)
            return
        }

        canvas.drawColor(Color.parseColor("#0A0A0C"))

        // 1. Workstation Header Card & Action Pills
        canvas.drawRoundRect(statusCardRect, 10f * density, 10f * density, cardBgPaint)
        canvas.drawRoundRect(statusCardRect, 10f * density, 10f * density, cardBorderPaint)

        val cLeft = statusCardRect.left + 12f * density
        statusDotPaint.color = if (isConnected) Color.parseColor("#00E676") else Color.parseColor("#FF5252")

        val isLandscape = width > height * 1.15f
        if (isLandscape) {
            val cy = statusCardRect.centerY()
            canvas.drawCircle(cLeft + 5f * density, cy, 5f * density, statusDotPaint)
            val hostTag = if (isConnected) "Host OK" else "Wait :8081"
            canvas.drawText(hostTag, cLeft + 16f * density, cy + 5f * density, titlePaint)
            val stats = "$lastGestureLabel ($lastDeltaLabel) | ${String.format(Locale.US, "%.1fx", sensitivity)}"
            canvas.drawText(stats, cLeft + 105f * density, cy + 4f * density, monoPaint)
        } else {
            val row1Y = statusCardRect.top + 20f * density
            canvas.drawCircle(cLeft + 5f * density, row1Y - 4f * density, 5f * density, statusDotPaint)
            val connStr = if (isConnected) "Connected (${server?.connectedClientIp ?: "USB"})" else "Waiting (:8081 ADB)"
            canvas.drawText("USB Workstation • $connStr", cLeft + 16f * density, row1Y, titlePaint)

            val stats = "Pkts:${server?.packetsSentTotal ?: 0} | $lastGestureLabel | $lastDeltaLabel | ${String.format(Locale.US, "%.1fx", sensitivity)}"
            canvas.drawText(stats, cLeft, statusCardRect.top + 39f * density, monoPaint)
        }

        drawPill(canvas, modeToggleRect, viewMode.shortLabel, viewMode != ViewMode.TRACKPAD_ONLY)
        drawPill(canvas, buttonBarToggleRect, if (showButtonBar) "Btns: ON" else "Btns: OFF", showButtonBar)
        drawPill(canvas, stealthToggleRect, "OLED", false)
        drawPill(canvas, sensMinusRect, "−", false)
        drawPill(canvas, sensPlusRect, "+", false)

        // 2. Trackpad Zone Watermark & Live Touch Ring
        if (!trackpadRect.isEmpty) {
            if (!isPadTouching && trackpadRect.height() > 70f * density) {
                canvas.drawText(
                    "Trackpad Surface  •  1F Move / Tap  •  2F Right-Click / Scroll",
                    trackpadRect.centerX(),
                    trackpadRect.centerY() + 5f * density,
                    watermarkPaint
                )
            }
            if (isPadTouching && trackpadRect.contains(touchIndicatorX, touchIndicatorY)) {
                canvas.drawCircle(touchIndicatorX, touchIndicatorY, 22f * density, touchRingPaint)
                canvas.drawCircle(touchIndicatorX, touchIndicatorY, 5.5f * density, touchDotPaint)
            }
        }

        // 3. Tactile Mouse Click Strip (Left | Mid | Right)
        if (showButtonBar && !clickBarRect.isEmpty) {
            drawMouseButton(canvas, leftButtonRect, "LEFT", activeBottomButtonCode == TrackpadServer.BTN_LEFT)
            drawMouseButton(canvas, middleButtonRect, "MID", activeBottomButtonCode == TrackpadServer.BTN_MIDDLE)
            drawMouseButton(canvas, rightButtonRect, "RIGHT", activeBottomButtonCode == TrackpadServer.BTN_RIGHT)
        }

        // 4. 14x5 Ortholinear Workstation Keyboard
        if (viewMode != ViewMode.TRACKPAD_ONLY) {
            keyboardEngine.draw(canvas)
        }
    }

    private fun drawMouseButton(canvas: Canvas, rect: RectF, label: String, pressed: Boolean) {
        canvas.drawRect(rect, if (pressed) buttonPressedPaint else buttonFillPaint)
        canvas.drawRect(rect, buttonBorderPaint)
        canvas.drawText(label, rect.centerX(), rect.centerY() + 4.5f * density, buttonTextPaint)
    }

    private fun drawPill(canvas: Canvas, rect: RectF, text: String, active: Boolean) {
        pillBgPaint.color = if (active) Color.parseColor("#162B38") else Color.parseColor("#161922")
        pillBorderPaint.color = if (active) Color.parseColor("#00E5FF") else Color.parseColor("#2A3040")
        pillTextPaint.color = if (active) Color.parseColor("#00E5FF") else Color.parseColor("#B0B8CC")

        canvas.drawRoundRect(rect, 7f * density, 7f * density, pillBgPaint)
        canvas.drawRoundRect(rect, 7f * density, 7f * density, pillBorderPaint)
        canvas.drawText(text, rect.centerX(), rect.centerY() + 4f * density, pillTextPaint)
    }
}
