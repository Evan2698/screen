package com.example.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import fi.iki.elonen.NanoWSD
import java.io.IOException

@SuppressLint("AccessibilityPolicy")
class TouchAccessibilityService : AccessibilityService() {

    private var server: TouchWebServer? = null

    private var currentPath: Path? = null

    private var screenWidth: Int = 1
    private var screenHeight: Int = 1

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for this implementation
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted.")
        server?.stop()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service connected. Starting touch server.")
        startServer()
    }

    @Suppress("DEPRECATION")
    private fun queryScreenWithHeight() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
    }

    private fun startServer() {
        queryScreenWithHeight()
        server = TouchWebServer(TOUCH_SERVER_PORT)
        try {
            server?.start(0, false)
            Log.d(TAG, "Touch server started on port $TOUCH_SERVER_PORT")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start touch server", e)
        }
    }

    private inner class TouchWebServer(port: Int) : NanoWSD(port) {
        override fun openWebSocket(session: IHTTPSession): WebSocket {
            return TouchSocket(session)
        }

        private inner class TouchSocket(session: IHTTPSession) : WebSocket(session) {
            override fun onOpen() {
                Log.d(TAG, "Touch WebSocket client connected.")
            }

            override fun onClose(
                code: WebSocketFrame.CloseCode,
                reason: String?,
                initiatedByRemote: Boolean
            ) {
                Log.d(TAG, "Touch WebSocket client disconnected.")
            }

            override fun onMessage(message: WebSocketFrame) {
                val command = message.textPayload
                if (command.startsWith(WebServer.HEART_BEAT)) {
                    try {
                        send(WebServer.HEART_BEAT)
                    } catch (e: IOException) {
                        Log.e(TAG, "Error sending heartbeat", e)
                    }
                } else {
                    handleTouchCommand(command)
                }
            }

            override fun onPong(pong: WebSocketFrame) {
                Log.d(TAG, "Pong received from client:${pong}")
            }

            override fun onException(exception: IOException) {
                Log.e(TAG, "Touch WebSocket exception", exception)
            }
        }
    }

    private fun handleTouchCommand(command: String) {
        val parts = command.split(",")
        if (parts.size < 3) return

        var x = 0.0f
        var y = 0.0f

        val type = parts[0]
        if (type != "K") {
            val xPos = parts[1].toFloatOrNull() ?: return
            val yPos = parts[2].toFloatOrNull() ?: return

            val xFloat = 1.0f / ScreenCaptureService.SCREEN_RATIO
            val yFloat = 1.0f / ScreenCaptureService.SCREEN_RATIO

            val realWidth = xPos * xFloat
            val realHeight = yPos * yFloat

            x = realWidth
            y = realHeight
        }

        when (type) {
            "D" -> { // Down
                currentPath = Path().apply {
                    moveTo(x, y)
                }
            }
            "M" -> { // Move
                currentPath?.lineTo(x, y)
            }
            "U" -> { // Up
                currentPath?.lineTo(x, y)
                val gestureDescription = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(currentPath!!, 0, 10))
                    .build()

                dispatchGesture(gestureDescription, null, null)
                currentPath = null
            }
            "K" -> { // Key Event (Home/Back)
                Log.d(TAG, parts[1])
                when (parts[1]) {
                    "H" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "B" -> performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility Service destroyed.")
        server?.stop()
    }

    companion object {
        private const val TAG = "TouchAccessibilitySvc"
        private const val TOUCH_SERVER_PORT = 8081
    }
}
