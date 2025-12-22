package com.example.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.stop
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.minutes

@SuppressLint("AccessibilityPolicy")
class TouchAccessibilityService : AccessibilityService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var server = embeddedServer(Netty, port = TOUCH_SERVER_PORT) {}

    private var currentPath: Path? = null

    private var screenWidth: Int = 1
    private var screenHeight : Int = 1

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for this implementation
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted.")
        server.stop(1000, 2000)
        serviceScope.cancel()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service connected. Starting touch server.")
        startServer()
    }

    private fun queryScreenWithHeight(){
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
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
        serviceScope.launch {
            server = embeddedServer(Netty, port = TOUCH_SERVER_PORT) {
                install(WebSockets) {
                    pingPeriod = 10.minutes
                    timeout = 10.minutes
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }
                routing {
                    webSocket("/touch") {
                        Log.d(TAG, "Touch WebSocket client connected.")
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val command = frame.readText()
                                handleTouchCommand(command)
                            }
                        }
                    }
                }
            }.start(wait = true)
        }
    }

    private fun handleTouchCommand(command: String) {
        val parts = command.split(",")
        if (parts.size < 3) return

        val type = parts[0]
        val xPos = parts[1].toFloatOrNull() ?: return
        val yPos = parts[2].toFloatOrNull() ?: return

        val realWidth = screenWidth.toFloat() * ScreenCaptureService.SCREEN_RATIO
        val realHeight = screenHeight.toFloat() * ScreenCaptureService.SCREEN_RATIO

        val xFloat = xPos / realWidth *  screenWidth
        val yFloat = yPos / realHeight * screenHeight

        val x = xFloat
        val y = yFloat


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
        private const val TOUCH_SERVER_PORT = 8081
    }
}