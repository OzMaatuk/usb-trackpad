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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * High-precision, zero-latency touch surface.
 * Supports:
 * - 1-finger relative delta cursor movement
 * - 1-finger tap -> Left Click
 * - 2-finger tap -> Right Click
 * - 2-finger vertical drag -> Scroll Wheel
 * - 1-finger double-tap & hold -> Left Button Drag
 * - Real-time touch visualizer ring for interactive emulator feedback
 * - OLED Stealth Mode toggle (pure black #000000 for legacy tablet battery & zero GPU cost)
 * - Tactile bottom click buttons (Left, Middle, Right)
 */
class TouchTrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var server: TrackpadServer? = null

    private val density = resources.displayMetrics.density

    // Touch Tuning Constants
    private val tapTimeoutMs = 230L
    private val doubleTapTimeoutMs = 280L
    private val touchSlopPx = 18f * density
    private val scrollThresholdPx = 16f * density

    // Configurable Sensitivity
    var sensitivity: Float = 1.20f
        set(value) {
            field = value.coerceIn(0.25f, 4.0f)
            invalidate()
        }

    // UI Configuration
    var showButtonBar: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var isStealthBlackMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Gesture Tracking State
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var downTime = 0L
    private var totalMovement = 0f

    // Sub-pixel Accumulators
    private var accumDx = 0f
    private var accumDy = 0f

    // Double-tap & Drag State
    private var lastTapUpTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var isDraggingWithDoubleTap = false

    // Two-finger Scroll & Tap State
    private var twoFingerDownTime = 0L
    private var lastScrollY = 0f
    private var twoFingerMovement = 0f
    private var accumScrollY = 0f

    // Visual Touch Feedback Indicator
    private var isTouching = false
    private var touchIndicatorX = 0f
    private var touchIndicatorY = 0f
    private var lastGestureLabel = "Ready (Touch anywhere)"
    private var lastDeltaLabel = "dx: 0, dy: 0"

    // Layout Rectangles (Calculated in onSizeChanged)
    private val leftButtonRect = RectF()
    private val middleButtonRect = RectF()
    private val rightButtonRect = RectF()
    private val statusCardRect = RectF()
    private val stealthToggleRect = RectF()
    private val sensMinusRect = RectF()
    private val sensPlusRect = RectF()
    private val buttonBarToggleRect = RectF()

    private var activeBottomButtonPointerId = MotionEvent.INVALID_POINTER_ID
    private var activeBottomButtonCode: Short = 0

    // Paints (Pre-allocated for zero GC allocation in onDraw)
    private val bgPaint = Paint().apply { color = Color.parseColor("#0A0A0C") }
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#15171C")
        style = Paint.Style.FILL
    }
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#252830")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val statusDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f * density
        isFakeBoldText = true
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A0A5B5")
        textSize = 13f * density
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 12f * density
    }
    private val dimTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#606575")
        textSize = 11f * density
    }
    private val buttonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#282C37")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val buttonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#14171F")
        style = Paint.Style.FILL
    }
    private val buttonPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        alpha = 60
        style = Paint.Style.FILL
    }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E4F0")
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val touchRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fw = w.toFloat()
        val fh = h.toFloat()

        // Safe top inset to avoid notches / camera punch holes
        val topInset = 48f * density
        val cardHeight = 115f * density
        statusCardRect.set(16f * density, topInset, fw - 16f * density, topInset + cardHeight)

        // Control buttons inside / below status card
        val btnY = topInset + cardHeight + 10f * density
        val btnH = 34f * density
        stealthToggleRect.set(16f * density, btnY, 130f * density, btnY + btnH)
        buttonBarToggleRect.set(140f * density, btnY, 240f * density, btnY + btnH)
        sensMinusRect.set(fw - 95f * density, btnY, fw - 55f * density, btnY + btnH)
        sensPlusRect.set(fw - 50f * density, btnY, fw - 16f * density, btnY + btnH)

        // Bottom Button Bar
        val barHeight = 72f * density
        val barTop = fh - barHeight
        val midWidth = 70f * density
        val sideWidth = (fw - midWidth) / 2f

        leftButtonRect.set(0f, barTop, sideWidth, fh)
        middleButtonRect.set(sideWidth, barTop, sideWidth + midWidth, fh)
        rightButtonRect.set(sideWidth + midWidth, barTop, fw, fh)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        val pointerCount = event.pointerCount

        val currentX = event.getX(pointerIndex)
        val currentY = event.getY(pointerIndex)

        // 1. Interactive HUD button checks
        if (action == MotionEvent.ACTION_DOWN) {
            if (stealthToggleRect.contains(currentX, currentY)) {
                isStealthBlackMode = !isStealthBlackMode
                invalidate()
                return true
            }
            if (!isStealthBlackMode) {
                if (buttonBarToggleRect.contains(currentX, currentY)) {
                    showButtonBar = !showButtonBar
                    invalidate()
                    return true
                }
                if (sensMinusRect.contains(currentX, currentY)) {
                    sensitivity -= 0.10f
                    lastDeltaLabel = String.format("Speed: %.1fx", sensitivity)
                    invalidate()
                    return true
                }
                if (sensPlusRect.contains(currentX, currentY)) {
                    sensitivity += 0.10f
                    lastDeltaLabel = String.format("Speed: %.1fx", sensitivity)
                    invalidate()
                    return true
                }
            }

            // 2. Check for Bottom Button Bar touches
            if (showButtonBar && currentY >= leftButtonRect.top) {
                activeBottomButtonPointerId = pointerId
                activeBottomButtonCode = when {
                    leftButtonRect.contains(currentX, currentY) -> TrackpadServer.BTN_LEFT
                    middleButtonRect.contains(currentX, currentY) -> TrackpadServer.BTN_MIDDLE
                    rightButtonRect.contains(currentX, currentY) -> TrackpadServer.BTN_RIGHT
                    else -> 0
                }
                if (activeBottomButtonCode > 0) {
                    server?.sendButton(activeBottomButtonCode, TrackpadServer.STATE_PRESS)
                    lastGestureLabel = when (activeBottomButtonCode) {
                        TrackpadServer.BTN_LEFT -> "Left Button Pressed"
                        TrackpadServer.BTN_RIGHT -> "Right Button Pressed"
                        else -> "Middle Button Pressed"
                    }
                    invalidate()
                    return true
                }
            }
        }

        // Handle release of bottom button bar
        if (pointerId == activeBottomButtonPointerId) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                if (activeBottomButtonCode > 0) {
                    server?.sendButton(activeBottomButtonCode, TrackpadServer.STATE_RELEASE)
                    lastGestureLabel = "Button Released"
                    activeBottomButtonCode = 0
                }
                activeBottomButtonPointerId = MotionEvent.INVALID_POINTER_ID
                invalidate()
                return true
            }
            return true
        }

        // 3. Trackpad Touch Surface Logic
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = pointerId
                lastX = currentX
                lastY = currentY
                startX = currentX
                startY = currentY
                downTime = SystemClock.uptimeMillis()
                totalMovement = 0f
                accumDx = 0f
                accumDy = 0f

                isTouching = true
                touchIndicatorX = currentX
                touchIndicatorY = currentY

                val timeSinceLastTap = downTime - lastTapUpTime
                val distFromLastTap = distance(startX, lastTapX, startY, lastTapY)

                if (timeSinceLastTap < doubleTapTimeoutMs && distFromLastTap < touchSlopPx * 2) {
                    isDraggingWithDoubleTap = true
                    server?.sendButton(TrackpadServer.BTN_LEFT, TrackpadServer.STATE_PRESS)
                    lastGestureLabel = "Double-Tap Drag (Window/Select)"
                } else {
                    isDraggingWithDoubleTap = false
                    lastGestureLabel = "Tracking..."
                }
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount == 2) {
                    twoFingerDownTime = SystemClock.uptimeMillis()
                    lastScrollY = (event.getY(0) + event.getY(1)) / 2f
                    twoFingerMovement = 0f
                    accumScrollY = 0f
                    lastGestureLabel = "2-Finger Scroll"
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                isTouching = true
                touchIndicatorX = currentX
                touchIndicatorY = currentY

                if (pointerCount == 1 && activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx != -1) {
                        val px = event.getX(idx)
                        val py = event.getY(idx)

                        if (!showButtonBar || py < leftButtonRect.top) {
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
                                lastDeltaLabel = "dx: $stepDx, dy: $stepDy"
                                if (!isDraggingWithDoubleTap) {
                                    lastGestureLabel = "Move Cursor"
                                }
                            }
                        }
                    }
                } else if (pointerCount >= 2) {
                    val currentAvgY = (event.getY(0) + event.getY(1)) / 2f
                    val deltaY = currentAvgY - lastScrollY
                    lastScrollY = currentAvgY

                    accumScrollY += deltaY
                    twoFingerMovement += abs(deltaY)

                    while (accumScrollY >= scrollThresholdPx) {
                        server?.sendScroll(1.toShort(), 0.toShort())
                        accumScrollY -= scrollThresholdPx
                        lastGestureLabel = "Scroll Up ↑"
                        lastDeltaLabel = "wheel: +1"
                    }
                    while (accumScrollY <= -scrollThresholdPx) {
                        server?.sendScroll((-1).toShort(), 0.toShort())
                        accumScrollY += scrollThresholdPx
                        lastGestureLabel = "Scroll Down ↓"
                        lastDeltaLabel = "wheel: -1"
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerCount == 2) {
                    val upTime = SystemClock.uptimeMillis()
                    val duration = upTime - twoFingerDownTime
                    if (duration < tapTimeoutMs && twoFingerMovement < touchSlopPx) {
                        server?.sendButton(TrackpadServer.BTN_RIGHT, TrackpadServer.STATE_CLICK)
                        lastGestureLabel = "2-Finger Tap: Right Click"
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                isTouching = false
                val upTime = SystemClock.uptimeMillis()
                val duration = upTime - downTime

                if (isDraggingWithDoubleTap) {
                    server?.sendButton(TrackpadServer.BTN_LEFT, TrackpadServer.STATE_RELEASE)
                    isDraggingWithDoubleTap = false
                    lastTapUpTime = 0L
                    lastGestureLabel = "Drag Released"
                } else if (pointerCount == 1 && duration < tapTimeoutMs && totalMovement < touchSlopPx) {
                    server?.sendButton(TrackpadServer.BTN_LEFT, TrackpadServer.STATE_CLICK)
                    lastTapUpTime = upTime
                    lastTapX = startX
                    lastTapY = startY
                    lastGestureLabel = "1-Finger Tap: Left Click"
                } else {
                    lastTapUpTime = 0L
                    lastGestureLabel = "Ready"
                }

                activePointerId = MotionEvent.INVALID_POINTER_ID
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                if (isDraggingWithDoubleTap) {
                    server?.sendButton(TrackpadServer.BTN_LEFT, TrackpadServer.STATE_RELEASE)
                    isDraggingWithDoubleTap = false
                }
                activePointerId = MotionEvent.INVALID_POINTER_ID
                activeBottomButtonPointerId = MotionEvent.INVALID_POINTER_ID
                if (activeBottomButtonCode > 0) {
                    server?.sendButton(activeBottomButtonCode, TrackpadServer.STATE_RELEASE)
                    activeBottomButtonCode = 0
                }
                lastGestureLabel = "Ready"
                invalidate()
            }
        }

        return true
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
            // Pure #000000 OLED Stealth Mode
            canvas.drawColor(Color.BLACK)

            // Minimal single status indicator
            statusDotPaint.color = if (isConnected) Color.parseColor("#00E676") else Color.parseColor("#FF5252")
            canvas.drawCircle(24f * density, 36f * density, 6f * density, statusDotPaint)

            // Tiny hint to restore
            canvas.drawText("OLED Mode (Tap dot to exit)", 38f * density, 40f * density, dimTextPaint)
            stealthToggleRect.set(16f * density, 20f * density, 220f * density, 60f * density)
        } else {
            // Interactive UI mode with clear telemetry & instructions
            canvas.drawColor(Color.parseColor("#0A0A0C"))

            // 1. Status Card Header
            canvas.drawRoundRect(statusCardRect, 12f * density, 12f * density, cardBgPaint)
            canvas.drawRoundRect(statusCardRect, 12f * density, 12f * density, cardBorderPaint)

            val cardLeft = statusCardRect.left + 16f * density
            val cardTop = statusCardRect.top + 28f * density

            // Title & Dot
            statusDotPaint.color = if (isConnected) Color.parseColor("#00E676") else Color.parseColor("#FF5252")
            canvas.drawCircle(cardLeft + 6f * density, cardTop - 6f * density, 6f * density, statusDotPaint)
            canvas.drawText("USB Trackpad", cardLeft + 20f * density, cardTop, titlePaint)

            val statusText = if (isConnected) {
                "Connected: ${server?.connectedClientIp ?: "127.0.0.1"}"
            } else {
                "Waiting for Linux host (adb forward tcp:8081 tcp:8081)"
            }
            canvas.drawText(statusText, cardLeft, cardTop + 24f * density, textPaint)

            val stats = "Pkts: ${server?.packetsSentTotal ?: 0}  |  $lastGestureLabel  |  $lastDeltaLabel"
            canvas.drawText(stats, cardLeft, cardTop + 48f * density, monoPaint)

            val hint = "Gesture: 1-finger move | 1-tap left click | 2-tap right click | 2-drag scroll"
            canvas.drawText(hint, cardLeft, cardTop + 68f * density, dimTextPaint)

            // 2. Control Pill Buttons
            drawPillButton(canvas, stealthToggleRect, "Mode: OLED", false)
            drawPillButton(canvas, buttonBarToggleRect, if (showButtonBar) "Btns: ON" else "Btns: OFF", showButtonBar)

            // Sensitivity Minus / Plus
            drawPillButton(canvas, sensMinusRect, "-", false)
            drawPillButton(canvas, sensPlusRect, "+", false)

            // Surface center watermark hint
            if (!isTouching) {
                val centerHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#1C202A")
                    textSize = 15f * density
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("Touch surface to control Linux mouse cursor", width / 2f, height / 2f, centerHintPaint)
            }
        }

        // 3. Interactive Touch Feedback Ring (shows on emulator and tablet)
        if (isTouching) {
            canvas.drawCircle(touchIndicatorX, touchIndicatorY, 28f * density, touchRingPaint)
            canvas.drawCircle(touchIndicatorX, touchIndicatorY, 7f * density, touchDotPaint)
        }

        // 4. Bottom Button Bar (if enabled)
        if (showButtonBar && !isStealthBlackMode) {
            // Left Button
            val leftPressed = (activeBottomButtonCode == TrackpadServer.BTN_LEFT)
            canvas.drawRect(leftButtonRect, if (leftPressed) buttonPressedPaint else buttonFillPaint)
            canvas.drawRect(leftButtonRect, buttonBorderPaint)
            canvas.drawText("LEFT CLICK", leftButtonRect.centerX(), leftButtonRect.centerY() + 5f * density, buttonTextPaint)

            // Middle Button
            val midPressed = (activeBottomButtonCode == TrackpadServer.BTN_MIDDLE)
            canvas.drawRect(middleButtonRect, if (midPressed) buttonPressedPaint else buttonFillPaint)
            canvas.drawRect(middleButtonRect, buttonBorderPaint)
            canvas.drawText("MID", middleButtonRect.centerX(), middleButtonRect.centerY() + 5f * density, buttonTextPaint)

            // Right Button
            val rightPressed = (activeBottomButtonCode == TrackpadServer.BTN_RIGHT)
            canvas.drawRect(rightButtonRect, if (rightPressed) buttonPressedPaint else buttonFillPaint)
            canvas.drawRect(rightButtonRect, buttonBorderPaint)
            canvas.drawText("RIGHT CLICK", rightButtonRect.centerX(), rightButtonRect.centerY() + 5f * density, buttonTextPaint)
        }
    }

    private fun drawPillButton(canvas: Canvas, rect: RectF, text: String, active: Boolean) {
        val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active) Color.parseColor("#1B2A38") else Color.parseColor("#161820")
            style = Paint.Style.FILL
        }
        val pillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active) Color.parseColor("#00E5FF") else Color.parseColor("#282C38")
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (active) Color.parseColor("#00E5FF") else Color.parseColor("#A0A5B5")
            textSize = 12f * density
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        canvas.drawRoundRect(rect, 8f * density, 8f * density, pillBg)
        canvas.drawRoundRect(rect, 8f * density, 8f * density, pillBorder)
        canvas.drawText(text, rect.centerX(), rect.centerY() + 4f * density, pillText)
    }
}
