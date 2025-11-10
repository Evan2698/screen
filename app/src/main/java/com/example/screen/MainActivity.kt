package com.example.screen

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.screen.ui.theme.ScreenTheme
import com.github.xfalcon.vhosts.vservice.VhostsService

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var screenCaptureResultData: Intent? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startAllServices(screenCaptureResultData)
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            screenCaptureResultData = result.data
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                startAllServices(screenCaptureResultData)
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
                var isAccessibilityEnabled by remember { mutableStateOf(false) }
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
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                isAccessibilityEnabled = checkAccessibilityServiceEnabled(context)
                            }
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
                    MainScreen(
                        isAnyServiceRunning = isAnyServiceRunning,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        serverAddress = serverAddress,
                        onToggleMainServices = {
                            if (isAnyServiceRunning) {
                                stopAllServices()
                            } else {
                                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                            }
                        },
                        onEnableAccessibilityClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
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

    private fun startAllServices(screenCaptureData: Intent?) {
        val screenIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            putExtra(ScreenCaptureService.EXTRA_DATA, screenCaptureData)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(screenIntent)
        } else {
            startService(screenIntent)
        }

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

    private fun checkAccessibilityServiceEnabled(context: Context): Boolean {
        val serviceId = "${context.packageName}/${TouchAccessibilityService::class.java.canonicalName}"
        try {
            val settingValue = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return settingValue?.let { TextUtils.SimpleStringSplitter(':').apply { setString(it) }.any { s -> s.equals(serviceId, ignoreCase = true) } } ?: false
        } catch (e: Exception) {
            Log.d("TAG", e.toString())
            return false
        }
    }
}

@Composable
fun MainScreen(
    isAnyServiceRunning: Boolean,
    isAccessibilityEnabled: Boolean,
    serverAddress: String?,
    onToggleMainServices: () -> Unit,
    onEnableAccessibilityClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Section 1: Main Services Control
        Button(onClick = onToggleMainServices) {
            Text(if (isAnyServiceRunning) "Stop Services" else "Start Services")
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (isAnyServiceRunning && serverAddress != null) {
            Text(text = "Screen running at:")
            Text(text = serverAddress)
        } else {
            Text(text = "All services are stopped.")
        }

        Spacer(modifier = Modifier.height(32.dp))
        Divider(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))

        // Section 2: Accessibility Service Control
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!isAccessibilityEnabled) {
                Text(text = "Remote control is disabled.")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onEnableAccessibilityClick) {
                    Text("Enable Remote Control")
                }
            } else {
                Text(text = "Remote control is enabled.")
            }
        } else {
            Text(
                text = "Remote control is not supported on this device (requires Android 7.0+).",
                textAlign = TextAlign.Center
            )
        }
    }
}