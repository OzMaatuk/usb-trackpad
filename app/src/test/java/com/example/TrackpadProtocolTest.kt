package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackpadProtocolTest {

    @Test
    fun testPacketEncoding() {
        assertEquals(8081, TrackpadServer(port = 8081).port)
        assertEquals(0xAA.toByte(), TrackpadServer.MAGIC_BYTE)
        assertEquals(0x01.toByte(), TrackpadServer.EVENT_MOVE)
        assertEquals(0x02.toByte(), TrackpadServer.EVENT_BUTTON)
        assertEquals(0x03.toByte(), TrackpadServer.EVENT_SCROLL)
        assertEquals(0x04.toByte(), TrackpadServer.EVENT_KEY)
    }

    @Test
    fun testPacketBitPackingMoveAndKey() {
        val dest = ByteArray(8)

        // 1. Test Move Packet
        val packedMove = TrackpadServer.packPacket(
            TrackpadServer.MAGIC_BYTE,
            TrackpadServer.EVENT_MOVE,
            0,
            (-42).toShort(),
            105.toShort()
        )
        TrackpadServer.unpackPacket(packedMove, dest)
        assertEquals(0xAA.toByte(), dest[0])
        assertEquals(0x01.toByte(), dest[1])
        val unpackedDx = ((dest[4].toInt() shl 8) or (dest[5].toInt() and 0xFF)).toShort()
        val unpackedDy = ((dest[6].toInt() shl 8) or (dest[7].toInt() and 0xFF)).toShort()
        assertEquals((-42).toShort(), unpackedDx)
        assertEquals(105.toShort(), unpackedDy)

        // 2. Test Key Packet (Alt + F4 atomic click)
        val packedKey = TrackpadServer.packPacket(
            TrackpadServer.MAGIC_BYTE,
            TrackpadServer.EVENT_KEY,
            LinuxKeyCodes.KEY_F4,
            TrackpadServer.STATE_CLICK,
            LinuxKeyCodes.MOD_ALT
        )
        TrackpadServer.unpackPacket(packedKey, dest)
        assertEquals(0xAA.toByte(), dest[0])
        assertEquals(TrackpadServer.EVENT_KEY, dest[1])
        val unpackedCode = ((dest[2].toInt() shl 8) or (dest[3].toInt() and 0xFF)).toShort()
        val unpackedState = ((dest[4].toInt() shl 8) or (dest[5].toInt() and 0xFF)).toShort()
        val unpackedMods = ((dest[6].toInt() shl 8) or (dest[7].toInt() and 0xFF)).toShort()
        assertEquals(LinuxKeyCodes.KEY_F4, unpackedCode)
        assertEquals(TrackpadServer.STATE_CLICK, unpackedState)
        assertEquals(LinuxKeyCodes.MOD_ALT, unpackedMods)
    }

    @Test
    fun testServerClientMoveAndKeyTransmission() {
        val testPort = 18082
        val server = TrackpadServer(port = testPort)
        server.start()

        Thread.sleep(150)

        val client = Socket("127.0.0.1", testPort)
        client.tcpNoDelay = true
        val inStream: InputStream = client.getInputStream()

        Thread.sleep(150)
        assertTrue(server.isClientConnected)

        // 1. Send Move event: dx=15, dy=-25
        server.sendMove(15.toShort(), (-25).toShort())
        val moveBuf = readExactFrame(inStream)
        assertEquals(0xAA.toByte(), moveBuf[0])
        assertEquals(TrackpadServer.EVENT_MOVE, moveBuf[1])
        assertEquals(15, (moveBuf[4].toInt() shl 8) or (moveBuf[5].toInt() and 0xFF))
        assertEquals(-25, ((moveBuf[6].toInt() shl 8) or (moveBuf[7].toInt() and 0xFF)).toShort().toInt())

        // 2. Send Key event: Super + Space (Language Switch)
        server.sendKey(LinuxKeyCodes.KEY_SPACE, TrackpadServer.STATE_CLICK, LinuxKeyCodes.MOD_SUPER)
        val keyBuf = readExactFrame(inStream)
        assertEquals(0xAA.toByte(), keyBuf[0])
        assertEquals(TrackpadServer.EVENT_KEY, keyBuf[1])
        val keyCode = ((keyBuf[2].toInt() shl 8) or (keyBuf[3].toInt() and 0xFF)).toShort()
        val keyState = ((keyBuf[4].toInt() shl 8) or (keyBuf[5].toInt() and 0xFF)).toShort()
        val modMask = ((keyBuf[6].toInt() shl 8) or (keyBuf[7].toInt() and 0xFF)).toShort()
        assertEquals(LinuxKeyCodes.KEY_SPACE, keyCode)
        assertEquals(TrackpadServer.STATE_CLICK, keyState)
        assertEquals(LinuxKeyCodes.MOD_SUPER, modMask)

        client.close()
        server.stop()
    }

    @Test
    fun testViewModeCyclingAndOrtholinearKeyboardEngine() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TouchTrackpadView(context)
        view.layout(0, 0, 1280, 800)

        // Verify 3-mode cycle: SPLIT_VIEW -> FULL_KEYBOARD -> TRACKPAD_ONLY -> SPLIT_VIEW
        view.viewMode = TouchTrackpadView.ViewMode.SPLIT_VIEW
        assertFalse(view.keyboardEngine.bounds.isEmpty)
        view.viewMode = view.viewMode.next()
        assertEquals(TouchTrackpadView.ViewMode.FULL_KEYBOARD, view.viewMode)
        assertFalse(view.keyboardEngine.bounds.isEmpty)
        view.viewMode = view.viewMode.next()
        assertEquals(TouchTrackpadView.ViewMode.TRACKPAD_ONLY, view.viewMode)
        assertTrue(view.keyboardEngine.bounds.isEmpty)
        view.viewMode = view.viewMode.next()
        assertEquals(TouchTrackpadView.ViewMode.SPLIT_VIEW, view.viewMode)

        // Verify strictly 5 rows (0..4) in OrtholinearKeyboardEngine
        val engine = view.keyboardEngine
        val maxRow = engine.keys.maxOf { it.row }
        assertEquals(4, maxRow)

        // Verify ESC on Row 0 Col 0, F4 on Fn+4, Sleep on Fn+=, Power on Fn+Bksp
        val escSpec = engine.keys.first { it.row == 0 && it.startCol == 0 }
        assertEquals(LinuxKeyCodes.KEY_ESC, escSpec.baseCode)

        val key4Spec = engine.keys.first { it.row == 0 && it.startCol == 4 }
        assertEquals(LinuxKeyCodes.KEY_4, key4Spec.baseCode)
        assertEquals(LinuxKeyCodes.KEY_F4, key4Spec.fnCode)

        val sleepSpec = engine.keys.first { it.row == 0 && it.startCol == 12 }
        assertEquals(LinuxKeyCodes.KEY_EQUAL, sleepSpec.baseCode)
        assertEquals(LinuxKeyCodes.KEY_SLEEP, sleepSpec.fnCode)

        val offSpec = engine.keys.first { it.row == 0 && it.startCol == 13 }
        assertEquals(LinuxKeyCodes.KEY_BACKSPACE, offSpec.baseCode)
        assertEquals(LinuxKeyCodes.KEY_POWER, offSpec.fnCode)
    }

    @Test
    fun testStickyModifierAndTwoTapPowerSafetyGuard() {
        val testPort = 18083
        val server = TrackpadServer(port = testPort)
        server.start()
        Thread.sleep(120)

        val client = Socket("127.0.0.1", testPort)
        client.tcpNoDelay = true
        val inStream = client.getInputStream()
        Thread.sleep(120)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TouchTrackpadView(context)
        view.server = server
        view.viewMode = TouchTrackpadView.ViewMode.FULL_KEYBOARD
        view.layout(0, 0, 1280, 800)
        val engine = view.keyboardEngine

        // 1. Single-tap Alt + Single-tap Fn -> Sticky latch -> Tap '4' emits KEY_F4 with MOD_ALT!
        val altKey = engine.keys.first { it.modIndex == OrtholinearKeyboardEngine.MOD_ALT }
        val fnKey = engine.keys.first { it.modIndex == OrtholinearKeyboardEngine.MOD_FN }
        val fourKey = engine.keys.first { it.baseCode == LinuxKeyCodes.KEY_4 }

        // Tap Alt (< 260ms)
        engine.handleTouchDown(0, altKey.rect.centerX(), altKey.rect.centerY())
        // Release Alt (sends press+release while held, then latches one-shot)
        engine.handleTouchUp(0)
        // Drain the physical press & release frames from Alt tap
        readExactFrame(inStream)
        readExactFrame(inStream)

        // Tap Fn (< 260ms) — client-only virtual layer modifier, no host packet
        engine.handleTouchDown(0, fnKey.rect.centerX(), fnKey.rect.centerY())
        engine.handleTouchUp(0)
        assertTrue(engine.isFnActive())

        // Tap '4' -> should send KEY_F4 + STATE_CLICK + MOD_ALT and clear one-shot latches
        engine.handleTouchDown(0, fourKey.rect.centerX(), fourKey.rect.centerY())
        engine.handleTouchUp(0)
        assertFalse(engine.isFnActive())

        val f4Frame = readExactFrame(inStream)
        val f4Code = ((f4Frame[2].toInt() shl 8) or (f4Frame[3].toInt() and 0xFF)).toShort()
        val f4State = ((f4Frame[4].toInt() shl 8) or (f4Frame[5].toInt() and 0xFF)).toShort()
        val f4Mods = ((f4Frame[6].toInt() shl 8) or (f4Frame[7].toInt() and 0xFF)).toShort()
        assertEquals(LinuxKeyCodes.KEY_F4, f4Code)
        assertEquals(TrackpadServer.STATE_CLICK, f4State)
        assertEquals(LinuxKeyCodes.MOD_ALT, f4Mods)

        // 2. Two-tap safety guard on Sleep (Fn + '=')
        val sleepKey = engine.keys.first { it.fnCode == LinuxKeyCodes.KEY_SLEEP }
        // Latch Fn
        engine.handleTouchDown(0, fnKey.rect.centerX(), fnKey.rect.centerY())
        engine.handleTouchUp(0)

        // 1st tap on Sleep: arms safety guard, keeps Fn latched, sends NO network packet
        val packetsBefore = server.packetsSentTotal
        engine.handleTouchDown(0, sleepKey.rect.centerX(), sleepKey.rect.centerY())
        engine.handleTouchUp(0)
        Thread.sleep(60)
        assertEquals(packetsBefore, server.packetsSentTotal)
        assertTrue(engine.isFnActive())

        // 2nd tap on Sleep within 2.5s: confirms and sends KEY_SLEEP!
        engine.handleTouchDown(0, sleepKey.rect.centerX(), sleepKey.rect.centerY())
        engine.handleTouchUp(0)
        val sleepFrame = readExactFrame(inStream)
        val sleepCode = ((sleepFrame[2].toInt() shl 8) or (sleepFrame[3].toInt() and 0xFF)).toShort()
        assertEquals(LinuxKeyCodes.KEY_SLEEP, sleepCode)

        client.close()
        server.stop()
    }

    private fun readExactFrame(inStream: InputStream): ByteArray {
        val buffer = ByteArray(8)
        var totalRead = 0
        while (totalRead < 8) {
            val r = inStream.read(buffer, totalRead, 8 - totalRead)
            if (r == -1) break
            totalRead += r
        }
        assertEquals(8, totalRead)
        return buffer
    }
}
