package com.example.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class TouchAccessibilityService : AccessibilityService() {

    private var currentPath: Path? = null

    private val touchCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenCaptureService.ACTION_PERFORM_TOUCH_COMMAND) {
                val command = intent.getStringExtra(ScreenCaptureService.EXTRA_TOUCH_COMMAND)
                if (command != null) {
                    Log.d(TAG, "Received touch command via broadcast: $command")
                    handleTouchCommand(command)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for this implementation
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted.")
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service connected.")
        val intentFilter = IntentFilter(ScreenCaptureService.ACTION_PERFORM_TOUCH_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(touchCommandReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(touchCommandReceiver, intentFilter)
        }
    }

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
        unregisterReceiver(touchCommandReceiver)
    }

    companion object {
        private const val TAG = "TouchAccessibilitySvc"
    }
}