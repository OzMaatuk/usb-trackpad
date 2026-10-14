package com.example

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Lightweight ComponentActivity managing full-screen immersive mode,
 * screen wake-lock, and the background TrackpadServer TCP lifecycle.
 */
class MainActivity : ComponentActivity(), TrackpadServer.Listener {

    private lateinit var trackpadView: TouchTrackpadView
    private lateinit var server: TrackpadServer

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (::trackpadView.isInitialized) {
                trackpadView.postInvalidate()
            }
            refreshHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep screen awake permanently while app is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        applyImmersiveStickyMode()

        // Initialize server on port 8081
        server = TrackpadServer(port = 8081, listener = this)

        // Initialize custom touch surface
        trackpadView = TouchTrackpadView(this).apply {
            server = this@MainActivity.server
        }

        setContentView(trackpadView)

        // Start background TCP ServerSocket
        server.start()
        refreshHandler.postDelayed(refreshRunnable, 500L)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveStickyMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveStickyMode()
        }
    }

    private fun applyImmersiveStickyMode() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onServerStarted(port: Int) {
        if (::trackpadView.isInitialized) {
            trackpadView.postInvalidate()
        }
    }

    override fun onClientConnected(clientAddress: String) {
        if (::trackpadView.isInitialized) {
            trackpadView.postInvalidate()
        }
    }

    override fun onClientDisconnected() {
        if (::trackpadView.isInitialized) {
            trackpadView.postInvalidate()
        }
    }

    override fun onError(message: String) {
        if (::trackpadView.isInitialized) {
            trackpadView.postInvalidate()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshHandler.removeCallbacks(refreshRunnable)
        server.stop()
    }
}
