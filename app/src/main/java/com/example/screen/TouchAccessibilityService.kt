package com.example.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class TouchAccessibilityService : AccessibilityService() , Dispatcher{

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
        DispatcherHolder.register(this)
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
        DispatcherHolder.unregister()
        super.onDestroy()
        Log.d(TAG, "Accessibility Service destroyed.")
        server.stop(1000, 2000)
        serviceScope.cancel()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun dispatch(action: String):Int {
        handleTouchCommand(action)

        return 0
    }

    companion object {
        private const val TAG = "TouchAccessibilitySvc"
    }
}