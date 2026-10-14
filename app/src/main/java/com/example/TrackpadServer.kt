package com.example

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance, zero-latency TCP ServerSocket for streaming trackpad packets.
 * Runs on a dedicated background thread, enforces TCP_NODELAY, and streams
 * fixed 8-byte binary frames without per-packet heap allocations.
 */
class TrackpadServer(
    val port: Int = 8081,
    private val listener: Listener? = null
) {

    interface Listener {
        fun onServerStarted(port: Int)
        fun onClientConnected(clientAddress: String)
        fun onClientDisconnected()
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "TrackpadServer"
        const val MAGIC_BYTE: Byte = 0xAA.toByte()

        const val EVENT_PING: Byte = 0x00
        const val EVENT_MOVE: Byte = 0x01
        const val EVENT_BUTTON: Byte = 0x02
        const val EVENT_SCROLL: Byte = 0x03

        const val BTN_LEFT: Short = 1
        const val BTN_RIGHT: Short = 2
        const val BTN_MIDDLE: Short = 3

        const val STATE_RELEASE: Short = 0
        const val STATE_PRESS: Short = 1
        const val STATE_CLICK: Short = 2
    }

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null
    private var serverThread: Thread? = null

    // Lock and pre-allocated buffer for zero GC allocation during packet writes
    private val sendLock = Any()
    private val packetBuffer = ByteArray(8)

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isClientConnected = false
        private set

    @Volatile
    var connectedClientIp: String? = null
        private set

    @Volatile
    var packetsSentTotal: Long = 0L
        private set

    fun start() {
        if (isRunning.getAndSet(true)) return

        serverThread = Thread({
            runServerLoop()
        }, "TrackpadServerThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        isRunning.set(false)
        closeClient()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
    }

    private fun runServerLoop() {
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = server

            Log.i(TAG, "ServerSocket listening on port $port")
            mainHandler.post { listener?.onServerStarted(port) }

            while (isRunning.get()) {
                try {
                    val socket = server.accept()
                    // Disable Nagle's algorithm for instant packet dispatch
                    socket.tcpNoDelay = true
                    socket.sendBufferSize = 4096
                    socket.soTimeout = 0

                    synchronized(sendLock) {
                        closeClient()
                        clientSocket = socket
                        outputStream = socket.getOutputStream()
                        isClientConnected = true
                        connectedClientIp = socket.inetAddress.hostAddress ?: "127.0.0.1"
                    }

                    val clientInfo = connectedClientIp ?: "Host"
                    Log.i(TAG, "Client connected: $clientInfo")
                    mainHandler.post { listener?.onClientConnected(clientInfo) }

                    // Monitor client input stream for disconnection
                    val inStream = socket.getInputStream()
                    val dummyBuffer = ByteArray(64)
                    while (isRunning.get() && socket.isConnected && !socket.isClosed) {
                        val bytesRead = inStream.read(dummyBuffer)
                        if (bytesRead == -1) {
                            break // Client disconnected
                        }
                    }
                } catch (e: IOException) {
                    if (isRunning.get()) {
                        Log.d(TAG, "Client socket disconnected: ${e.message}")
                    }
                } finally {
                    synchronized(sendLock) {
                        closeClient()
                    }
                    mainHandler.post { listener?.onClientDisconnected() }
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Server error: ${e.message}", e)
                mainHandler.post { listener?.onError(e.message ?: "Server socket error") }
            }
        }
    }

    private fun closeClient() {
        isClientConnected = false
        connectedClientIp = null
        try {
            outputStream?.close()
        } catch (_: Exception) {
        }
        outputStream = null

        try {
            clientSocket?.close()
        } catch (_: Exception) {
        }
        clientSocket = null
    }

    /**
     * Sends an 8-byte binary packet instantly to the connected client.
     * Zero-allocation, synchronized, immediate flush.
     */
    fun sendPacket(eventType: Byte, code: Short, val1: Short, val2: Short): Boolean {
        synchronized(sendLock) {
            val out = outputStream ?: return false
            return try {
                packetBuffer[0] = MAGIC_BYTE
                packetBuffer[1] = eventType
                packetBuffer[2] = ((code.toInt() shr 8) and 0xFF).toByte()
                packetBuffer[3] = (code.toInt() and 0xFF).toByte()
                packetBuffer[4] = ((val1.toInt() shr 8) and 0xFF).toByte()
                packetBuffer[5] = (val1.toInt() and 0xFF).toByte()
                packetBuffer[6] = ((val2.toInt() shr 8) and 0xFF).toByte()
                packetBuffer[7] = (val2.toInt() and 0xFF).toByte()

                out.write(packetBuffer, 0, 8)
                out.flush()
                packetsSentTotal++
                true
            } catch (e: IOException) {
                closeClient()
                mainHandler.post { listener?.onClientDisconnected() }
                false
            }
        }
    }

    fun sendMove(dx: Short, dy: Short): Boolean =
        sendPacket(EVENT_MOVE, 0, dx, dy)

    fun sendButton(buttonCode: Short, state: Short): Boolean =
        sendPacket(EVENT_BUTTON, buttonCode, state, 0)

    fun sendScroll(vScroll: Short, hScroll: Short): Boolean =
        sendPacket(EVENT_SCROLL, 0, vScroll, hScroll)

    fun sendPing(): Boolean =
        sendPacket(EVENT_PING, 0, 0, 0)
}
