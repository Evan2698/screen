package com.example.screen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.screen.ui.theme.ScreenTheme
import com.github.xfalcon.vhosts.vservice.VhostsService

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var screenCaptureResult: Intent? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startAllServices(screenCaptureResult)
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            screenCaptureResult = result.data
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                startAllServices(screenCaptureResult)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            ScreenTheme {
                var screenCaptureRunning by remember { mutableStateOf(false) }
                var vpnServiceRunning by remember { mutableStateOf(false) }
                var serverAddress by remember { mutableStateOf<String?>(null) }

                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(context, lifecycleOwner) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            when (intent.action) {
                                ScreenCaptureService.ACTION_STATE_CHANGED -> {
                                    screenCaptureRunning = intent.getBooleanExtra(ScreenCaptureService.EXTRA_IS_RUNNING, false)
                                    serverAddress = if (screenCaptureRunning) {
                                        intent.getStringExtra(ScreenCaptureService.EXTRA_SERVER_ADDRESS)
                                    } else {
                                        null
                                    }
                                }
                                VhostsService.ACTION_STATE_CHANGED -> {
                                    vpnServiceRunning = intent.getBooleanExtra(VhostsService.EXTRA_IS_RUNNING, false)
                                }
                            }
                        }
                    }
                    val intentFilter = IntentFilter().apply {
                        addAction(ScreenCaptureService.ACTION_STATE_CHANGED)
                        addAction(VhostsService.ACTION_STATE_CHANGED)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(receiver, intentFilter, RECEIVER_NOT_EXPORTED)
                    } else {
                        @Suppress("UnspecifiedRegisterReceiverFlag")
                        context.registerReceiver(receiver, intentFilter)
                    }

                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            requestServiceState(ScreenCaptureService.ACTION_REQUEST_STATE)
                            requestServiceState(VhostsService.ACTION_REQUEST_STATE)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)

                    onDispose {
                        context.unregisterReceiver(receiver)
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val isAnyServiceRunning = screenCaptureRunning || vpnServiceRunning
                    ControlScreen(
                        isServiceRunning = isAnyServiceRunning,
                        serverAddress = serverAddress,
                        onToggleClick = {
                            if (isAnyServiceRunning) {
                                stopAllServices()
                            } else {
                                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                            }
                        }
                    )
                }
            }
        }
    }

    private fun requestServiceState(action: String) {
        val intent = Intent(action).setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun startAllServices(screenCaptureIntentData: Intent?) {
        // Start ScreenCaptureService
        val screenCaptureIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            putExtra(ScreenCaptureService.EXTRA_DATA, screenCaptureIntentData)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(screenCaptureIntent)
        } else {
            startService(screenCaptureIntent)
        }

        // Start VhostsService
        val vpnIntent = Intent(this, VhostsService::class.java).apply {
            action = VhostsService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(vpnIntent)
        } else {
            startService(vpnIntent)
        }
    }

    private fun stopAllServices() {
        startService(Intent(this, ScreenCaptureService::class.java).apply { action = ScreenCaptureService.ACTION_STOP })
        startService(Intent(this, VhostsService::class.java).apply { action = VhostsService.ACTION_STOP })
    }
}

@Composable
fun ControlScreen(isServiceRunning: Boolean, serverAddress: String?, onToggleClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = onToggleClick) {
            Text(if (isServiceRunning) "Stop Capture" else "Start Capture")
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (isServiceRunning && serverAddress != null) {
            Text(text = "Server running at:")
            Text(text = serverAddress)
        }
    }
}