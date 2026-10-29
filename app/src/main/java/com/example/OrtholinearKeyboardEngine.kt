package com.example

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Bare-metal 14x5 Symmetrical Ortholinear Workstation Keyboard Engine.
 *
 * Layout Philosophy (5 rows total — exactly 1 number/Fn row above the 3 character rows):
 * - Row 0: ESC, 1..0, -, =, Bksp  (with Fn layer: `, F1..F5, Mute, Vol-, Vol+, Play, F11, F12, Sleep, Off)
 * - Row 1: Tab, Q..P, [, ], \     (with Fn layer: CloseWin on Tab, F6..F10 on Y..P)
 * - Row 2: Caps, A..L, ;, ', 2U Enter  (tactile homing bars on F & J)
 * - Row 3: Shift, Z..M, comma, dot, /, Shift, Up(PgUp), Del(PrtSc)
 * - Row 4: Ctrl, Super, Alt, Fn, Lang(Super+Space), 4U Space, Alt, Fn, Left(Home), Down(PgDn), Right(End)
 *
 * Supports multi-touch hold-to-chord + single-tap sticky ("one-shot") latch + double-tap lock,
 * deterministic 26Hz typematic key-hold auto-repeat, and 2-tap safety arming on Sleep & Off.
 */
class OrtholinearKeyboardEngine(
    private val hostView: View,
    private val density: Float,
    private val onTelemetry: (gesture: String, detail: String) -> Unit
) {

    enum class KeyRole { ALPHA, NUMBER, SYMBOL, MODIFIER, ACTION, NAV, DANGER }

    class KeySpec(
        val row: Int,
        val startCol: Int,
        val colSpan: Int,
        val primary: String,
        val shiftLabel: String = "",
        val fnLabel: String = "",
        val baseCode: Short,
        val fnCode: Short = baseCode,
        val role: KeyRole = KeyRole.ALPHA,
        val modIndex: Int = -1,
        val repeatable: Boolean = true,
        val homingBar: Boolean = false
    ) {
        val rect = RectF()
    }

    companion object {
        const val COLS = 14
        const val ROWS = 5

        const val MOD_CTRL = 0
        const val MOD_SHIFT = 1
        const val MOD_ALT = 2
        const val MOD_SUPER = 3
        const val MOD_FN = 4
        private const val MOD_COUNT = 5

        private const val STICKY_OFF = 0
        private const val STICKY_LATCHED = 1
        private const val STICKY_LOCKED = 2

        private const val STICKY_TAP_MAX_MS = 260L
        private const val REPEAT_INITIAL_DELAY_MS = 360L
        private const val REPEAT_INTERVAL_MS = 38L
        private const val POWER_ARM_TIMEOUT_MS = 2500L
        private const val MAX_POINTERS = 10
    }

    var server: TrackpadServer? = null
    val bounds = RectF()

    // Modifier tracking arrays (zero-allocation primitive arrays)
    private val modHeldCount = IntArray(MOD_COUNT)
    private val modStickyState = IntArray(MOD_COUNT)
    private val modDownTime = LongArray(MOD_COUNT)
    private val modUsedInChord = BooleanArray(MOD_COUNT)
    private val osModSentDown = BooleanArray(4) // Ctrl, Shift, Alt, Super
    private var capsLocked = false

    // Active key index per pointerId (0..9), -1 if pointer is not on keyboard
    private val pointerKeyIndex = IntArray(MAX_POINTERS) { -1 }

    // Typematic repeat state
    private val handler = Handler(Looper.getMainLooper())
    private var repeatingPointerId = -1
    private var repeatingKeyIndex = -1

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val kIdx = repeatingKeyIndex
            if (kIdx in keys.indices && repeatingPointerId != -1 &&
                pointerKeyIndex[repeatingPointerId] == kIdx
            ) {
                fireNonModifierKey(keys[kIdx], isRepeat = true)
                hostView.invalidate()
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            } else {
                stopRepeat()
            }
        }
    }

    // 2-Tap safety guard for Sleep & Shutdown/Power
    private var armedPowerCode: Short = 0
    private var armedPowerExpireUptime: Long = 0L
    private val disarmRunnable = Runnable {
        armedPowerCode = 0
        hostView.invalidate()
    }

    // Pre-allocated Paints
    private val bgPaint = Paint().apply { color = Color.parseColor("#0D0F14") }
    private val dividerPaint = Paint().apply {
        color = Color.parseColor("#242936")
        strokeWidth = 1.5f * density
    }
    private val keyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val keyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val primaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
    }
    private val fnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val homingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val keyDrawRect = RectF()

    val keys: Array<KeySpec> = buildOrtholinearMatrix()

    private fun buildOrtholinearMatrix(): Array<KeySpec> {
        val list = ArrayList<KeySpec>(70)
        val K = LinuxKeyCodes

        // ROW 0: Single Number + Fn Strip above character rows (14 columns)
        list.add(KeySpec(0, 0, 1, "ESC", "~", "`", K.KEY_ESC, K.KEY_GRAVE, KeyRole.ACTION, repeatable = false))
        list.add(KeySpec(0, 1, 1, "1", "!", "F1", K.KEY_1, K.KEY_F1, KeyRole.NUMBER))
        list.add(KeySpec(0, 2, 1, "2", "@", "F2", K.KEY_2, K.KEY_F2, KeyRole.NUMBER))
        list.add(KeySpec(0, 3, 1, "3", "#", "F3", K.KEY_3, K.KEY_F3, KeyRole.NUMBER))
        list.add(KeySpec(0, 4, 1, "4", "$", "F4", K.KEY_4, K.KEY_F4, KeyRole.NUMBER))
        list.add(KeySpec(0, 5, 1, "5", "%", "F5", K.KEY_5, K.KEY_F5, KeyRole.NUMBER))
        list.add(KeySpec(0, 6, 1, "6", "^", "Mute", K.KEY_6, K.KEY_MUTE, KeyRole.NUMBER, repeatable = false))
        list.add(KeySpec(0, 7, 1, "7", "&", "Vol-", K.KEY_7, K.KEY_VOLUMEDOWN, KeyRole.NUMBER))
        list.add(KeySpec(0, 8, 1, "8", "*", "Vol+", K.KEY_8, K.KEY_VOLUMEUP, KeyRole.NUMBER))
        list.add(KeySpec(0, 9, 1, "9", "(", "Play", K.KEY_9, K.KEY_PLAYPAUSE, KeyRole.NUMBER, repeatable = false))
        list.add(KeySpec(0, 10, 1, "0", ")", "F11", K.KEY_0, K.KEY_F11, KeyRole.NUMBER, repeatable = false))
        list.add(KeySpec(0, 11, 1, "-", "_", "F12", K.KEY_MINUS, K.KEY_F12, KeyRole.SYMBOL))
        list.add(KeySpec(0, 12, 1, "=", "+", "Sleep", K.KEY_EQUAL, K.KEY_SLEEP, KeyRole.DANGER))
        list.add(KeySpec(0, 13, 1, "Bksp", "", "Off", K.KEY_BACKSPACE, K.KEY_POWER, KeyRole.DANGER))

        // ROW 1: Top Alpha Row (14 columns)
        list.add(KeySpec(1, 0, 1, "Tab", "", "✕Win", K.KEY_TAB, K.VKEY_CLOSE_WIN, KeyRole.ACTION))
        list.add(KeySpec(1, 1, 1, "Q", "", "", K.KEY_Q))
        list.add(KeySpec(1, 2, 1, "W", "", "", K.KEY_W))
        list.add(KeySpec(1, 3, 1, "E", "", "", K.KEY_E))
        list.add(KeySpec(1, 4, 1, "R", "", "", K.KEY_R))
        list.add(KeySpec(1, 5, 1, "T", "", "", K.KEY_T))
        list.add(KeySpec(1, 6, 1, "Y", "", "F6", K.KEY_Y, K.KEY_F6))
        list.add(KeySpec(1, 7, 1, "U", "", "F7", K.KEY_U, K.KEY_F7))
        list.add(KeySpec(1, 8, 1, "I", "", "F8", K.KEY_I, K.KEY_F8))
        list.add(KeySpec(1, 9, 1, "O", "", "F9", K.KEY_O, K.KEY_F9))
        list.add(KeySpec(1, 10, 1, "P", "", "F10", K.KEY_P, K.KEY_F10))
        list.add(KeySpec(1, 11, 1, "[", "{", "", K.KEY_LEFTBRACE, role = KeyRole.SYMBOL))
        list.add(KeySpec(1, 12, 1, "]", "}", "", K.KEY_RIGHTBRACE, role = KeyRole.SYMBOL))
        list.add(KeySpec(1, 13, 1, "\\", "|", "", K.KEY_BACKSLASH, role = KeyRole.SYMBOL))

        // ROW 2: Home Alpha Row (14 columns, 2U wide Enter on cols 12..13)
        list.add(KeySpec(2, 0, 1, "Caps", "", "", K.KEY_CAPSLOCK, role = KeyRole.ACTION, repeatable = false))
        list.add(KeySpec(2, 1, 1, "A", "", "", K.KEY_A))
        list.add(KeySpec(2, 2, 1, "S", "", "", K.KEY_S))
        list.add(KeySpec(2, 3, 1, "D", "", "", K.KEY_D))
        list.add(KeySpec(2, 4, 1, "F", "", "", K.KEY_F, homingBar = true))
        list.add(KeySpec(2, 5, 1, "G", "", "", K.KEY_G))
        list.add(KeySpec(2, 6, 1, "H", "", "", K.KEY_H))
        list.add(KeySpec(2, 7, 1, "J", "", "", K.KEY_J, homingBar = true))
        list.add(KeySpec(2, 8, 1, "K", "", "", K.KEY_K))
        list.add(KeySpec(2, 9, 1, "L", "", "", K.KEY_L))
        list.add(KeySpec(2, 10, 1, ";", ":", "", K.KEY_SEMICOLON, role = KeyRole.SYMBOL))
        list.add(KeySpec(2, 11, 1, "'", "\"", "", K.KEY_APOSTROPHE, role = KeyRole.SYMBOL))
        list.add(KeySpec(2, 12, 2, "Enter ↵", "", "", K.KEY_ENTER, role = KeyRole.ACTION))

        // ROW 3: Bottom Alpha + Up Arrow + Del (14 columns)
        list.add(KeySpec(3, 0, 1, "Shift", "", "", K.KEY_LEFTSHIFT, role = KeyRole.MODIFIER, modIndex = MOD_SHIFT, repeatable = false))
        list.add(KeySpec(3, 1, 1, "Z", "", "", K.KEY_Z))
        list.add(KeySpec(3, 2, 1, "X", "", "", K.KEY_X))
        list.add(KeySpec(3, 3, 1, "C", "", "", K.KEY_C))
        list.add(KeySpec(3, 4, 1, "V", "", "", K.KEY_V))
        list.add(KeySpec(3, 5, 1, "B", "", "", K.KEY_B))
        list.add(KeySpec(3, 6, 1, "N", "", "", K.KEY_N))
        list.add(KeySpec(3, 7, 1, "M", "", "", K.KEY_M))
        list.add(KeySpec(3, 8, 1, ",", "<", "", K.KEY_COMMA, role = KeyRole.SYMBOL))
        list.add(KeySpec(3, 9, 1, ".", ">", "", K.KEY_DOT, role = KeyRole.SYMBOL))
        list.add(KeySpec(3, 10, 1, "/", "?", "", K.KEY_SLASH, role = KeyRole.SYMBOL))
        list.add(KeySpec(3, 11, 1, "Shift", "", "", K.KEY_RIGHTSHIFT, role = KeyRole.MODIFIER, modIndex = MOD_SHIFT, repeatable = false))
        list.add(KeySpec(3, 12, 1, "↑", "", "PgUp", K.KEY_UP, K.KEY_PAGEUP, KeyRole.NAV))
        list.add(KeySpec(3, 13, 1, "Del", "", "PrtSc", K.KEY_DELETE, K.KEY_SYSRQ, KeyRole.ACTION))

        // ROW 4: Modifiers + Lang + Centered 4U Space + Inverted-T Bottom Arrows (14 columns)
        list.add(KeySpec(4, 0, 1, "Ctrl", "", "", K.KEY_LEFTCTRL, role = KeyRole.MODIFIER, modIndex = MOD_CTRL, repeatable = false))
        list.add(KeySpec(4, 1, 1, "Super", "", "", K.KEY_LEFTMETA, role = KeyRole.MODIFIER, modIndex = MOD_SUPER, repeatable = false))
        list.add(KeySpec(4, 2, 1, "Alt", "", "", K.KEY_LEFTALT, role = KeyRole.MODIFIER, modIndex = MOD_ALT, repeatable = false))
        list.add(KeySpec(4, 3, 1, "Fn", "", "", K.VKEY_FN, role = KeyRole.MODIFIER, modIndex = MOD_FN, repeatable = false))
        list.add(KeySpec(4, 4, 1, "Lang", "", "", K.VKEY_LANG_SWITCH, role = KeyRole.ACTION, repeatable = false))
        list.add(KeySpec(4, 5, 4, "SPACE", "", "", K.KEY_SPACE, role = KeyRole.ACTION))
        list.add(KeySpec(4, 9, 1, "Alt", "", "", K.KEY_RIGHTALT, role = KeyRole.MODIFIER, modIndex = MOD_ALT, repeatable = false))
        list.add(KeySpec(4, 10, 1, "Fn", "", "", K.VKEY_FN, role = KeyRole.MODIFIER, modIndex = MOD_FN, repeatable = false))
        list.add(KeySpec(4, 11, 1, "←", "", "Home", K.KEY_LEFT, K.KEY_HOME, KeyRole.NAV))
        list.add(KeySpec(4, 12, 1, "↓", "", "PgDn", K.KEY_DOWN, K.KEY_PAGEDOWN, KeyRole.NAV))
        list.add(KeySpec(4, 13, 1, "→", "", "End", K.KEY_RIGHT, K.KEY_END, KeyRole.NAV))

        return list.toTypedArray()
    }

    fun updateBounds(left: Float, top: Float, right: Float, bottom: Float) {
        bounds.set(left, top, right, bottom)
        val padH = 4f * density
        val padV = 4f * density
        val gridW = (bounds.width() - padH * 2f).coerceAtLeast(1f)
        val gridH = (bounds.height() - padV * 2f).coerceAtLeast(1f)
        val colW = gridW / COLS
        val rowH = gridH / ROWS

        for (k in keys) {
            val kl = bounds.left + padH + k.startCol * colW
            val kt = bounds.top + padV + k.row * rowH
            val kr = kl + k.colSpan * colW
            val kb = kt + rowH
            k.rect.set(kl, kt, kr, kb)
        }
    }

    fun isFnActive(): Boolean = modHeldCount[MOD_FN] > 0 || modStickyState[MOD_FN] != STICKY_OFF
    private fun isModActive(m: Int): Boolean = modHeldCount[m] > 0 || modStickyState[m] != STICKY_OFF

    fun ownsPointer(pointerId: Int): Boolean =
        pointerId in 0 until MAX_POINTERS && pointerKeyIndex[pointerId] != -1

    /**
     * Called when user clicks or scrolls on the trackpad so any held chord is recognized
     * and one-shot latched OS modifiers are cleanly released after the mouse action.
     */
    fun onTrackpadInteraction() {
        for (m in 0 until 4) {
            if (modHeldCount[m] > 0) modUsedInChord[m] = true
        }
        consumeLatchedOsModifiers()
    }

    fun handleTouchDown(pointerId: Int, x: Float, y: Float): Boolean {
        if (pointerId !in 0 until MAX_POINTERS || !bounds.contains(x, y)) return false
        val idx = hitTestKey(x, y)
        if (idx == -1) return false

        pointerKeyIndex[pointerId] = idx
        hostView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        val key = keys[idx]
        if (key.modIndex != -1) {
            onModifierDown(key.modIndex)
        } else {
            // Mark any currently held modifiers as used in a physical chord
            for (m in 0 until MOD_COUNT) {
                if (modHeldCount[m] > 0) modUsedInChord[m] = true
            }
            val shouldRepeat = fireNonModifierKey(key, isRepeat = false)
            if (shouldRepeat && key.repeatable) {
                startRepeat(pointerId, idx)
            }
        }
        hostView.invalidate()
        return true
    }

    fun handleTouchMove(event: MotionEvent) {
        for (i in 0 until event.pointerCount) {
            val pid = event.getPointerId(i)
            if (pid in 0 until MAX_POINTERS) {
                val heldIdx = pointerKeyIndex[pid]
                if (heldIdx != -1 && pid == repeatingPointerId) {
                    if (!keys[heldIdx].rect.contains(event.getX(i), event.getY(i))) {
                        stopRepeat()
                    }
                }
            }
        }
    }

    fun handleTouchUp(pointerId: Int) {
        if (pointerId !in 0 until MAX_POINTERS) return
        val idx = pointerKeyIndex[pointerId]
        if (idx == -1) return
        pointerKeyIndex[pointerId] = -1

        if (pointerId == repeatingPointerId) {
            stopRepeat()
        }

        val key = keys[idx]
        if (key.modIndex != -1) {
            onModifierUp(key.modIndex)
        }
        hostView.invalidate()
    }

    fun cancelAllPointers() {
        stopRepeat()
        for (i in 0 until MAX_POINTERS) pointerKeyIndex[i] = -1
        for (m in 0 until MOD_COUNT) {
            modHeldCount[m] = 0
            modStickyState[m] = STICKY_OFF
            modUsedInChord[m] = false
        }
        syncOsModifiers()
        hostView.invalidate()
    }

    private fun onModifierDown(m: Int) {
        val prevHeld = modHeldCount[m]
        modHeldCount[m] = prevHeld + 1
        if (prevHeld == 0) {
            modDownTime[m] = SystemClock.uptimeMillis()
            modUsedInChord[m] = false
        }
        syncOsModifiers()
        onTelemetry("Modifier", modifierLabel(m))
    }

    private fun onModifierUp(m: Int) {
        modHeldCount[m] = (modHeldCount[m] - 1).coerceAtLeast(0)
        if (modHeldCount[m] == 0) {
            val elapsed = SystemClock.uptimeMillis() - modDownTime[m]
            if (!modUsedInChord[m] && elapsed <= STICKY_TAP_MAX_MS) {
                // Single-tap transitions: OFF -> LATCHED -> LOCKED (for Shift/Fn) or OFF
                modStickyState[m] = when (modStickyState[m]) {
                    STICKY_OFF -> STICKY_LATCHED
                    STICKY_LATCHED -> if (m == MOD_SHIFT || m == MOD_FN) STICKY_LOCKED else STICKY_OFF
                    else -> STICKY_OFF
                }
            } else if (modUsedInChord[m]) {
                // Physical hold-to-chord finished; clear unless previously locked
                if (modStickyState[m] == STICKY_LATCHED) {
                    modStickyState[m] = STICKY_OFF
                }
            }
            syncOsModifiers()
            onTelemetry("Modifier", modifierLabel(m))
        }
    }

    private fun syncOsModifiers() {
        val codes = shortArrayOf(
            LinuxKeyCodes.KEY_LEFTCTRL,
            LinuxKeyCodes.KEY_LEFTSHIFT,
            LinuxKeyCodes.KEY_LEFTALT,
            LinuxKeyCodes.KEY_LEFTMETA
        )
        for (m in 0 until 4) {
            // Only hold physical OS key down while finger is physically holding the modifier;
            // sticky one-shot / locked modifiers are sent atomically in val2 on key tap so bare Super never misfires!
            val shouldHoldOs = modHeldCount[m] > 0
            if (shouldHoldOs && !osModSentDown[m]) {
                server?.sendKey(codes[m], TrackpadServer.STATE_PRESS, 0)
                osModSentDown[m] = true
            } else if (!shouldHoldOs && osModSentDown[m]) {
                server?.sendKey(codes[m], TrackpadServer.STATE_RELEASE, 0)
                osModSentDown[m] = false
            }
        }
    }

    private fun buildStickyModifierMask(): Short {
        var mask = 0
        if (modStickyState[MOD_CTRL] != STICKY_OFF) mask = mask or LinuxKeyCodes.MOD_CTRL.toInt()
        if (modStickyState[MOD_SHIFT] != STICKY_OFF) mask = mask or LinuxKeyCodes.MOD_SHIFT.toInt()
        if (modStickyState[MOD_ALT] != STICKY_OFF) mask = mask or LinuxKeyCodes.MOD_ALT.toInt()
        if (modStickyState[MOD_SUPER] != STICKY_OFF) mask = mask or LinuxKeyCodes.MOD_SUPER.toInt()
        return mask.toShort()
    }

    private fun consumeLatchedOsModifiers() {
        for (m in 0 until 4) {
            if (modStickyState[m] == STICKY_LATCHED) {
                modStickyState[m] = STICKY_OFF
            }
        }
        syncOsModifiers()
    }

    /**
     * Dispatches a non-modifier key tap. Returns true if typematic repeat is allowed.
     */
    private fun fireNonModifierKey(key: KeySpec, isRepeat: Boolean): Boolean {
        val fnActive = isFnActive()
        val shiftActive = isModActive(MOD_SHIFT)

        // Special handling for ESC/Tilde key on Row 0 Col 0: Shift + ESC -> '~' (KEY_GRAVE + Shift)
        val targetCode: Short = when {
            fnActive -> key.fnCode
            key.baseCode == LinuxKeyCodes.KEY_ESC && shiftActive -> LinuxKeyCodes.KEY_GRAVE
            else -> key.baseCode
        }

        // Disarm power safety guard if user tapped any unrelated key
        if (targetCode != armedPowerCode && armedPowerCode != 0.toShort()) {
            armedPowerCode = 0
            handler.removeCallbacks(disarmRunnable)
        }

        // 1. Handle dedicated Virtual Combos (Lang = Super+Space, CloseWin = Alt+F4)
        if (targetCode == LinuxKeyCodes.VKEY_LANG_SWITCH) {
            server?.sendKey(LinuxKeyCodes.KEY_SPACE, TrackpadServer.STATE_CLICK, LinuxKeyCodes.MOD_SUPER)
            consumeOneShotAfterKey(keepFn = false)
            onTelemetry("Language Switch", "Super + Space")
            return false
        }
        if (targetCode == LinuxKeyCodes.VKEY_CLOSE_WIN) {
            server?.sendKey(LinuxKeyCodes.KEY_F4, TrackpadServer.STATE_CLICK, LinuxKeyCodes.MOD_ALT)
            consumeOneShotAfterKey(keepFn = false)
            onTelemetry("Close Window", "Alt + F4")
            return false
        }

        // 2. Two-Tap Safety Guard on Sleep & Power/Shutdown
        if (targetCode == LinuxKeyCodes.KEY_SLEEP || targetCode == LinuxKeyCodes.KEY_POWER) {
            if (isRepeat) return false
            val now = SystemClock.uptimeMillis()
            val name = if (targetCode == LinuxKeyCodes.KEY_SLEEP) "Sleep" else "Power Off"
            if (armedPowerCode == targetCode && now < armedPowerExpireUptime) {
                armedPowerCode = 0
                handler.removeCallbacks(disarmRunnable)
                server?.sendKey(targetCode, TrackpadServer.STATE_CLICK, 0)
                consumeOneShotAfterKey(keepFn = false)
                onTelemetry("$name Sent", "Confirmed")
            } else {
                armedPowerCode = targetCode
                armedPowerExpireUptime = now + POWER_ARM_TIMEOUT_MS
                handler.removeCallbacks(disarmRunnable)
                handler.postDelayed(disarmRunnable, POWER_ARM_TIMEOUT_MS)
                // Keep Fn latched so 2nd confirmation tap hits the same Sleep/Off action
                consumeOneShotAfterKey(keepFn = true)
                onTelemetry("Arm $name", "Tap again within 2.5s")
            }
            return false
        }

        // 3. Caps Lock state toggle
        if (targetCode == LinuxKeyCodes.KEY_CAPSLOCK && !isRepeat) {
            capsLocked = !capsLocked
        }

        // 4. Standard Linux evdev keycode dispatch with any sticky modifiers attached
        val stickyMask = buildStickyModifierMask()
        server?.sendKey(targetCode, TrackpadServer.STATE_CLICK, stickyMask)

        val displayLegend = if (fnActive && key.fnLabel.isNotEmpty()) key.fnLabel else key.primary
        onTelemetry(if (isRepeat) "Repeat: $displayLegend" else "Key: $displayLegend", "code: $targetCode")

        if (!isRepeat) {
            consumeOneShotAfterKey(keepFn = false)
        }
        return true
    }

    private fun consumeOneShotAfterKey(keepFn: Boolean) {
        consumeLatchedOsModifiers()
        if (!keepFn && modStickyState[MOD_FN] == STICKY_LATCHED) {
            modStickyState[MOD_FN] = STICKY_OFF
        }
    }

    private fun startRepeat(pointerId: Int, keyIdx: Int) {
        stopRepeat()
        repeatingPointerId = pointerId
        repeatingKeyIndex = keyIdx
        handler.postDelayed(repeatRunnable, REPEAT_INITIAL_DELAY_MS)
    }

    private fun stopRepeat() {
        handler.removeCallbacks(repeatRunnable)
        repeatingPointerId = -1
        repeatingKeyIndex = -1
    }

    fun hitTestKey(x: Float, y: Float): Int {
        for (i in keys.indices) {
            if (keys[i].rect.contains(x, y)) return i
        }
        return -1
    }

    private fun isKeyPressedByFinger(idx: Int): Boolean {
        for (p in 0 until MAX_POINTERS) {
            if (pointerKeyIndex[p] == idx) return true
        }
        return false
    }

    private fun modifierLabel(m: Int): String {
        val name = when (m) {
            MOD_CTRL -> "Ctrl"
            MOD_SHIFT -> "Shift"
            MOD_ALT -> "Alt"
            MOD_SUPER -> "Super"
            else -> "Fn"
        }
        val state = when {
            modHeldCount[m] > 0 -> "HELD"
            modStickyState[m] == STICKY_LOCKED -> "LOCKED"
            modStickyState[m] == STICKY_LATCHED -> "LATCHED"
            else -> "OFF"
        }
        return "$name: $state"
    }

    fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return

        canvas.drawRect(bounds, bgPaint)
        canvas.drawLine(bounds.left, bounds.top, bounds.right, bounds.top, dividerPaint)

        val fnActive = isFnActive()
        val shiftActive = isModActive(MOD_SHIFT) || capsLocked
        val gap = 2.2f * density
        val radius = 6f * density

        for (i in keys.indices) {
            val k = keys[i]
            keyDrawRect.set(k.rect.left + gap, k.rect.top + gap, k.rect.right - gap, k.rect.bottom - gap)
            val minDim = minOf(keyDrawRect.width(), keyDrawRect.height())
            val pressed = isKeyPressedByFinger(i)

            val isArmedDanger = fnActive && k.fnCode == armedPowerCode && armedPowerCode != 0.toShort()
            val modState = if (k.modIndex != -1) modStickyState[k.modIndex] else STICKY_OFF
            val modHeld = k.modIndex != -1 && modHeldCount[k.modIndex] > 0
            val isCapsActive = k.baseCode == LinuxKeyCodes.KEY_CAPSLOCK && capsLocked

            // 1. Keycap background color
            keyFillPaint.color = when {
                isArmedDanger -> Color.parseColor("#4A151B")
                pressed || modHeld -> Color.parseColor("#133844")
                modState == STICKY_LOCKED || isCapsActive -> Color.parseColor("#3B2E10")
                modState == STICKY_LATCHED -> Color.parseColor("#182E3A")
                k.role == KeyRole.MODIFIER || k.role == KeyRole.ACTION || k.role == KeyRole.DANGER ->
                    Color.parseColor("#12151E")
                k.role == KeyRole.NAV -> Color.parseColor("#141926")
                else -> Color.parseColor("#181C26")
            }
            canvas.drawRoundRect(keyDrawRect, radius, radius, keyFillPaint)

            // 2. Keycap border color
            keyBorderPaint.color = when {
                isArmedDanger -> Color.parseColor("#FF5252")
                pressed || modHeld -> Color.parseColor("#00E5FF")
                modState == STICKY_LOCKED || isCapsActive -> Color.parseColor("#FFB300")
                modState == STICKY_LATCHED -> Color.parseColor("#00E5FF")
                fnActive && k.fnLabel.isNotEmpty() -> Color.parseColor("#3D4B66")
                else -> Color.parseColor("#262B38")
            }
            canvas.drawRoundRect(keyDrawRect, radius, radius, keyBorderPaint)

            // 3. Determine active primary & secondary legends
            val mainText = when {
                isArmedDanger -> "CONFIRM"
                fnActive && k.fnLabel.isNotEmpty() -> k.fnLabel
                shiftActive && k.role == KeyRole.ALPHA -> k.primary.uppercase()
                !shiftActive && k.role == KeyRole.ALPHA -> k.primary.lowercase()
                shiftActive && k.shiftLabel.isNotEmpty() -> k.shiftLabel
                else -> k.primary
            }

            primaryTextPaint.textSize = (if (mainText.length > 3) minDim * 0.27f else minDim * 0.36f)
                .coerceIn(9f * density, 16f * density)
            primaryTextPaint.color = when {
                isArmedDanger -> Color.parseColor("#FF8A80")
                pressed || modHeld -> Color.parseColor("#00E5FF")
                modState == STICKY_LOCKED || isCapsActive -> Color.parseColor("#FFD54F")
                modState == STICKY_LATCHED -> Color.parseColor("#40C4FF")
                fnActive && k.fnLabel.isNotEmpty() -> Color.parseColor("#00E5FF")
                k.baseCode == LinuxKeyCodes.KEY_ESC -> Color.parseColor("#FF8A65")
                else -> Color.parseColor("#EAEEF7")
            }

            val centerY = keyDrawRect.centerY() + primaryTextPaint.textSize * 0.35f
            canvas.drawText(mainText, keyDrawRect.centerX(), centerY, primaryTextPaint)

            // 4. Top-left Fn sub-legend (visible when not already promoted to mainText)
            if (!fnActive && k.fnLabel.isNotEmpty() && minDim >= 26f * density) {
                fnTextPaint.textSize = (minDim * 0.20f).coerceIn(7.5f * density, 10.5f * density)
                fnTextPaint.color = when {
                    k.fnCode == LinuxKeyCodes.KEY_SLEEP || k.fnCode == LinuxKeyCodes.KEY_POWER ->
                        Color.parseColor("#CF6679")
                    k.fnCode == LinuxKeyCodes.KEY_F4 -> Color.parseColor("#4DD0E1")
                    else -> Color.parseColor("#68738D")
                }
                canvas.drawText(
                    k.fnLabel,
                    keyDrawRect.left + 4f * density,
                    keyDrawRect.top + fnTextPaint.textSize + 2.5f * density,
                    fnTextPaint
                )
            }

            // 5. Top-right Shift sub-legend
            if (!fnActive && !shiftActive && k.shiftLabel.isNotEmpty() && minDim >= 26f * density) {
                subTextPaint.textSize = (minDim * 0.21f).coerceIn(8f * density, 11f * density)
                subTextPaint.color = Color.parseColor("#7E879E")
                canvas.drawText(
                    k.shiftLabel,
                    keyDrawRect.right - 4f * density,
                    keyDrawRect.top + subTextPaint.textSize + 2.5f * density,
                    subTextPaint
                )
            }

            // 6. Tactile homing bar under F and J
            if (k.homingBar) {
                val barHalf = keyDrawRect.width() * 0.18f
                val barY = keyDrawRect.bottom - 4.5f * density
                canvas.drawLine(
                    keyDrawRect.centerX() - barHalf,
                    barY,
                    keyDrawRect.centerX() + barHalf,
                    barY,
                    homingPaint
                )
            }
        }
    }
}
