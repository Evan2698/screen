package com.example.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TouchAccessibilityService : AccessibilityService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var server = embeddedServer(Netty, port = 8081) {}

    private var currentPath: Path? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for this implementation
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service connected.")
        startServer()
    }

    private fun startServer() {
        serviceScope.launch {
            server = embeddedServer(Netty, port = 8081) {
                install(WebSockets)
                {
                    pingPeriodMillis = 15000L // 15 秒
                    timeoutMillis = 15000L     // 15秒
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }
                routing {
                    webSocket("/touch") {
                        Log.d(TAG, "Touch WebSocket client connected.")
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                Log.d(TAG, "Received touch command: $text")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    handleTouchCommand(text)
                                } else {
                                    Log.w(TAG, "Gesture dispatching is not supported on this API level.")
                                }
                            }
                        }
                    }
                }
            }.start(wait = true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun handleTouchCommand(command: String) {
        val parts = command.split(",")
        if (parts.size < 3) return

        val type = parts[0]
        val x = parts[1].toFloatOrNull() ?: return
        val y = parts[2].toFloatOrNull() ?: return

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
                currentPath?.let {
                    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(it, 0, 100)).build()
                    dispatchGesture(gesture, null, null)
                    Log.d(TAG, "Dispatched gesture.")
                }
                currentPath = null
            }
            "K" -> { // Key Event (Home/Back)
                val action = when(parts[1]){
                    "H" -> GLOBAL_ACTION_HOME
                    "B" -> GLOBAL_ACTION_BACK
                    else -> -1
                }
                if (action != -1) {
                    performGlobalAction(action)
                    Log.d(TAG, "Dispatched global action: $action")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility Service destroyed.")
        server.stop(1000, 2000)
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "TouchAccessibilitySvc"
    }
}