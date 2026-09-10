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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.screen.ui.theme.ScreenTheme

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var screenCaptureResultData: Intent? = null
    private var screenCaptureExpected = false
    private var screenCapturePermissionRequestActive = false
    private var screenCaptureRecoveryPending = false
    private var screenCaptureRecoveryScheduled = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        screenCapturePermissionRequestActive = false
        if (result.resultCode == Activity.RESULT_OK) {
            screenCaptureResultData = result.data
            screenCaptureExpected = true
            screenCaptureRecoveryPending = false
            saveScreenCaptureExpected(true)
            startAllServices(screenCaptureResultData)
        } else {
            Log.w(TAG, "Screen capture permission was denied.")
            screenCaptureExpected = false
            screenCaptureRecoveryPending = false
            saveScreenCaptureExpected(false)
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Log.w(TAG, "VPN permission was denied; stopping screen capture.")
            screenCaptureExpected = false
            screenCaptureRecoveryPending = false
            saveScreenCaptureExpected(false)
            stopScreenCaptureService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureExpected = getPreferences(Context.MODE_PRIVATE)
            .getBoolean(PREF_SCREEN_CAPTURE_EXPECTED, false)

        setContent {
            ScreenTheme {
                var screenCaptureRunning by remember { mutableStateOf(false) }
                var isAccessibilityEnabled by remember { mutableStateOf(false) }
                var serverAddress by remember { mutableStateOf<String?>(null) }

                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(context, lifecycleOwner) {
                    val syncScreenState: () -> Unit = {
                        screenCaptureRunning = ScreenCaptureService.isRunning
                        serverAddress = if (screenCaptureRunning) {
                            ScreenCaptureService.currentServerAddress
                        } else {
                            null
                        }
                    }

                    val refreshAllStates: () -> Unit = {
                        syncScreenState()
                        requestServiceState(ScreenCaptureService.ACTION_REQUEST_STATE)
                        isAccessibilityEnabled = checkAccessibilityServiceEnabled(context)
                    }

                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            when (intent.action) {
                                ScreenCaptureService.ACTION_STATE_CHANGED -> {
                                    val running = intent.getBooleanExtra(ScreenCaptureService.EXTRA_IS_RUNNING, ScreenCaptureService.isRunning)
                                    screenCaptureRunning = running
                                    serverAddress = if (running) {
                                        intent.getStringExtra(ScreenCaptureService.EXTRA_SERVER_ADDRESS)
                                            ?: ScreenCaptureService.currentServerAddress
                                    } else {
                                        null
                                    }
                                    if (!running && screenCaptureExpected) {
                                        screenCaptureRecoveryPending = true
                                        stopVpnService()
                                        scheduleScreenCaptureRecovery()
                                    }
                                }
                            }
                        }
                    }
                    val intentFilter = IntentFilter().apply {
                        addAction(ScreenCaptureService.ACTION_STATE_CHANGED)
                    }
                    // Since minSdk is 29, which is lower than Tiramisu (33), this check is still valid and necessary.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(receiver, intentFilter, RECEIVER_NOT_EXPORTED)
                    } else {
                        @Suppress("UnspecifiedRegisterReceiverFlag")
                        context.registerReceiver(receiver, intentFilter)
                    }

                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START,
                            Lifecycle.Event.ON_RESUME -> {
                                refreshAllStates()
                                requestScreenCaptureRecoveryIfNeeded()
                            }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)

                    onDispose {
                        context.unregisterReceiver(receiver)
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LaunchedEffect(screenCaptureRunning) {
                        setScreenKeepOn(screenCaptureRunning)
                    }

                    val isAnyServiceRunning = screenCaptureRunning
                    MainScreen(
                        isAnyServiceRunning = isAnyServiceRunning,
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        serverAddress = serverAddress,
                        onToggleMainServices = {
                            if (isAnyServiceRunning) {
                                stopAllServices()
                            } else {
                                requestScreenCapturePermission()
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
        if (screenCaptureData == null) {
            Log.w(TAG, "No screen capture permission data, requesting fresh projection permission.")
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            return
        }

        val screenIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            putExtra(ScreenCaptureService.EXTRA_DATA, screenCaptureData)
        }
        startForegroundService(screenIntent)
        startOrRequestVpn()
    }

    private fun requestScreenCapturePermission() {
        if (screenCapturePermissionRequestActive) return
        screenCapturePermissionRequestActive = true
        screenCaptureExpected = true
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun requestScreenCaptureRecoveryIfNeeded() {
        if (screenCaptureExpected
            && screenCaptureRecoveryPending
            && !ScreenCaptureService.isRunning
            && !screenCapturePermissionRequestActive
            && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            Log.i(TAG, "Screen capture stopped while the device was locked; requesting fresh permission.")
            requestScreenCapturePermission()
        }
    }

    private fun scheduleScreenCaptureRecovery() {
        if (screenCaptureRecoveryScheduled) return
        screenCaptureRecoveryScheduled = true
        mainHandler.postDelayed({
            screenCaptureRecoveryScheduled = false
            requestScreenCaptureRecoveryIfNeeded()
        }, RECOVERY_DELAY_MS)
    }

    private fun stopAllServices() {
        screenCaptureExpected = false
        screenCapturePermissionRequestActive = false
        screenCaptureRecoveryPending = false
        screenCaptureRecoveryScheduled = false
        mainHandler.removeCallbacksAndMessages(null)
        saveScreenCaptureExpected(false)
        screenCaptureResultData = null
        stopVpnService()
        stopScreenCaptureService()
    }

    private fun startOrRequestVpn() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent == null) {
            startVpnService()
        } else {
            vpnPermissionLauncher.launch(vpnIntent)
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, com.github.xfalcon.vhosts.vservice.VhostsService::class.java).apply {
            action = com.github.xfalcon.vhosts.vservice.VhostsService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        startService(Intent(this, com.github.xfalcon.vhosts.vservice.VhostsService::class.java).apply {
            action = com.github.xfalcon.vhosts.vservice.VhostsService.ACTION_STOP
        })
    }

    private fun stopScreenCaptureService() {
        startService(Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        })
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

    private fun saveScreenCaptureExpected(expected: Boolean) {
        getPreferences(Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_SCREEN_CAPTURE_EXPECTED, expected)
            .apply()
    }

    private fun setScreenKeepOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onDestroy() {
        setScreenKeepOn(false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREF_SCREEN_CAPTURE_EXPECTED = "screen_capture_expected"
        private const val RECOVERY_DELAY_MS = 500L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isAnyServiceRunning: Boolean,
    isAccessibilityEnabled: Boolean,
    serverAddress: String?,
    onToggleMainServices: () -> Unit,
    onEnableAccessibilityClick: () -> Unit
) {
    val statusColor = if (isAnyServiceRunning) Color(0xFF7AE7A1) else Color(0xFFE8E1F8)
    val statusLabel = if (isAnyServiceRunning) "Active" else "Standby"
    val primaryButtonText = if (isAnyServiceRunning) "Stop screen" else "Start screen"
    val accessibilityLabel = if (isAccessibilityEnabled) "Accessibility enabled" else "Accessibility disabled"
    val accessibilityButtonText = if (isAccessibilityEnabled) "Open Accessibility settings" else "Enable Accessibility"
    val heroGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF7E57FF),
            Color(0xFF5E67F6),
            Color(0xFF00B8D9),
            Color(0xFF4DD0E1)
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F4FB)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Screen Remote",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = Color(0xFFF5F4FB),
                titleContentColor = MaterialTheme.colorScheme.onBackground
            )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(heroGradient, RoundedCornerShape(32.dp))
                    .shadow(22.dp, RoundedCornerShape(32.dp))
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Broadcast",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White.copy(alpha = 0.82f)
                            )
                            Text(
                                text = "Screen capture",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color.White.copy(alpha = 0.14f),
                            shadowElevation = 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Text(
                                    text = statusLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    Text(
                        text = if (isAnyServiceRunning) "Remote viewing is active and ready." else "Screen sharing is off. Start it when ready.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.9f)
                    )

                    ElevatedButton(
                        onClick = onToggleMainServices,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAnyServiceRunning) Color(0xFFE53935) else Color.White,
                            contentColor = if (isAnyServiceRunning) Color.White else Color(0xFF5C6DFF)
                        )
                    ) {
                        Text(primaryButtonText, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "投屏地址",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (serverAddress != null) Color(0xFFEAF7FF) else Color(0xFFF3F2F8)
                    ) {
                        Text(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            text = serverAddress ?: "未启动",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (serverAddress != null) Color(0xFF1565C0) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF2ECFF)),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Android Accessibility",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (isAccessibilityEnabled) Color(0xFFDCFCE7) else Color(0xFFEDE7F8)
                    ) {
                        Text(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            text = accessibilityLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isAccessibilityEnabled) Color(0xFF166534) else Color(0xFF5B3F9B)
                        )
                    }

                    OutlinedButton(
                        onClick = onEnableAccessibilityClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF5C6DFF))
                    ) {
                        Text(accessibilityButtonText, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
