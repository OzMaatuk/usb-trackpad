package com.example

import org.junit.Assert.assertEquals
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
    }

    @Test
    fun testServerClientTransmission() {
        val testPort = 18081
        val server = TrackpadServer(port = testPort)
        server.start()

        Thread.sleep(150) // Allow server to bind

        val client = Socket("127.0.0.1", testPort)
        client.tcpNoDelay = true
        val inStream: InputStream = client.getInputStream()

        Thread.sleep(150)
        assertTrue(server.isClientConnected)

        // Send a Move event: dx=15, dy=-25
        server.sendMove(15.toShort(), (-25).toShort())

        val buffer = ByteArray(8)
        var totalRead = 0
        while (totalRead < 8) {
            val r = inStream.read(buffer, totalRead, 8 - totalRead)
            if (r == -1) break
            totalRead += r
        }

        assertEquals(8, totalRead)
        // Check Magic Byte
        assertEquals(0xAA.toByte(), buffer[0])
        // Check Event Type (EVENT_MOVE = 0x01)
        assertEquals(0x01.toByte(), buffer[1])
        // Check Code (0 for move)
        val code = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        assertEquals(0, code)
        // Check Val1 (dx = 15)
        val dx = (buffer[4].toInt() shl 8) or (buffer[5].toInt() and 0xFF)
        assertEquals(15, dx)
        // Check Val2 (dy = -25)
        val dy = ((buffer[6].toInt() shl 8) or (buffer[7].toInt() and 0xFF)).toShort().toInt()
        assertEquals(-25, dy)

        client.close()
        server.stop()
    }
}
