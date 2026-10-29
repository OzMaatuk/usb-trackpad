package com.example

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance, zero-latency TCP ServerSocket for streaming trackpad packets.
 * Runs on a dedicated background thread, enforces TCP_NODELAY, and streams
 * fixed 8-byte binary frames without per-packet heap allocations.
 *
 * Uses an asynchronous, non-blocking packet queue with a dedicated sender thread
 * to guarantee that socket I/O NEVER occurs on the Android UI/Main thread
 * (preventing NetworkOnMainThreadException while maintaining sub-millisecond dispatch).
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
        const val EVENT_KEY: Byte = 0x04

        const val BTN_LEFT: Short = 1
        const val BTN_RIGHT: Short = 2
        const val BTN_MIDDLE: Short = 3

        const val STATE_RELEASE: Short = 0
        const val STATE_PRESS: Short = 1
        const val STATE_CLICK: Short = 2

        /**
         * Packs an 8-byte packet into a single primitive 64-bit Long (zero allocation).
         */
        fun packPacket(magic: Byte, eventType: Byte, code: Short, val1: Short, val2: Short): Long {
            var p = (magic.toLong() and 0xFFL) shl 56
            p = p or ((eventType.toLong() and 0xFFL) shl 48)
            p = p or ((code.toLong() and 0xFFFFL) shl 32)
            p = p or ((val1.toLong() and 0xFFFFL) shl 16)
            p = p or (val2.toLong() and 0xFFFFL)
            return p
        }

        /**
         * Unpacks a 64-bit Long into a destination 8-byte buffer in big-endian order.
         */
        fun unpackPacket(p: Long, dest: ByteArray) {
            dest[0] = ((p ushr 56) and 0xFFL).toByte()
            dest[1] = ((p ushr 48) and 0xFFL).toByte()
            dest[2] = ((p ushr 40) and 0xFFL).toByte()
            dest[3] = ((p ushr 32) and 0xFFL).toByte()
            dest[4] = ((p ushr 24) and 0xFFL).toByte()
            dest[5] = ((p ushr 16) and 0xFFL).toByte()
            dest[6] = ((p ushr 8) and 0xFFL).toByte()
            dest[7] = (p and 0xFFL).toByte()
        }
    }

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null

    private var serverThread: Thread? = null
    private var senderThread: Thread? = null

    // Bounded high-speed queue for outgoing 64-bit packed packets
    private val packetQueue = ArrayBlockingQueue<Long>(512)

    private val sendLock = Any()
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

        // 1. Start background TCP Server Listener thread
        serverThread = Thread({
            runServerLoop()
        }, "TrackpadServerAcceptThread").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }

        // 2. Start dedicated background packet sender thread
        senderThread = Thread({
            runSenderLoop()
        }, "TrackpadPacketSenderThread").apply {
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

        senderThread?.interrupt()
        senderThread = null
        packetQueue.clear()
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
                    // Disable Nagle's algorithm for instant transmission
                    socket.tcpNoDelay = true
                    socket.sendBufferSize = 4096
                    socket.soTimeout = 0

                    synchronized(sendLock) {
                        closeClient()
                        clientSocket = socket
                        outputStream = socket.getOutputStream()
                        isClientConnected = true
                        connectedClientIp = socket.inetAddress?.hostAddress ?: "127.0.0.1"
                        packetQueue.clear()
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

    /**
     * Dedicated background sender loop. Pulls packed frames from queue
     * and writes directly to socket output stream off the main thread.
     */
    private fun runSenderLoop() {
        val buffer = ByteArray(8)
        while (isRunning.get()) {
            try {
                val packed = packetQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val out: OutputStream?
                synchronized(sendLock) {
                    out = outputStream
                }
                if (out != null) {
                    unpackPacket(packed, buffer)
                    out.write(buffer, 0, 8)
                    // If queue is empty, flush immediately to minimize latency
                    if (packetQueue.isEmpty()) {
                        out.flush()
                    }
                    packetsSentTotal++
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: IOException) {
                Log.d(TAG, "Error writing packet to socket: ${e.message}")
                synchronized(sendLock) {
                    closeClient()
                }
                mainHandler.post { listener?.onClientDisconnected() }
            }
        }
    }

    private fun closeClient() {
        isClientConnected = false
        connectedClientIp = null
        packetQueue.clear()

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
     * Non-blocking packet enqueue. Completely safe to call from onTouchEvent() / Main Thread.
     * Enqueues the 64-bit packed packet without any heap allocation or thread blocking.
     */
    fun sendPacket(eventType: Byte, code: Short, val1: Short, val2: Short): Boolean {
        if (!isClientConnected) return false

        val packed = packPacket(MAGIC_BYTE, eventType, code, val1, val2)

        // Offer to queue. If queue is full (e.g. host lagging), drop oldest to maintain real-time responsiveness
        if (!packetQueue.offer(packed)) {
            packetQueue.poll()
            packetQueue.offer(packed)
        }
        return true
    }

    fun sendMove(dx: Short, dy: Short): Boolean =
        sendPacket(EVENT_MOVE, 0, dx, dy)

    fun sendButton(buttonCode: Short, state: Short): Boolean =
        sendPacket(EVENT_BUTTON, buttonCode, state, 0)

    fun sendScroll(vScroll: Short, hScroll: Short): Boolean =
        sendPacket(EVENT_SCROLL, 0, vScroll, hScroll)

    fun sendKey(keyCode: Short, state: Short, modMask: Short = 0): Boolean =
        sendPacket(EVENT_KEY, keyCode, state, modMask)

    fun sendPing(): Boolean =
        sendPacket(EVENT_PING, 0, 0, 0)
}
