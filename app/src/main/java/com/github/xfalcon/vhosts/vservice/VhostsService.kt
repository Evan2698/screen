package com.github.xfalcon.vhosts.vservice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.screen.MainActivity
import com.example.screen.R
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@SuppressLint("StaticFieldLeak")
class VhostsService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var executorService: ExecutorService? = null

    private val stateRequestReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REQUEST_STATE) {
                Log.d(TAG, "Received state request from UI")
                sendStateBroadcast()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VPN Service onCreate")
        createNotificationChannel()

        val intentFilter = IntentFilter(ACTION_REQUEST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateRequestReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateRequestReceiver, intentFilter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand with action: ${intent?.action}")
        return when (intent?.action) {
            ACTION_START -> {
                if (isRunning) {
                    Log.d(TAG, "VPN is already running.")
                    return START_STICKY
                }
                startVpn()
                START_STICKY
            }
            ACTION_STOP -> {
                stopVpn()
                START_NOT_STICKY
            }
            else -> START_STICKY
        }
    }

    private fun startVpn() {
        Log.d(TAG, "Starting VPN...")
        startForeground(NOTIFICATION_ID, createNotification())

        if (setupVpnInterface()) {
            try {
                startVpnThreads()
                isRunning = true
                sendStateBroadcast()
                Log.i(TAG, "VPN Started")
            } catch (e: IOException) {
                Log.e(TAG, "Error starting VPN threads", e)
                stopVpn()
            }
        } else {
            Log.e(TAG, "Failed to setup VPN interface.")
            stopVpn()
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN...")
        isRunning = false
        sendStateBroadcast()

        try {
            executorService?.shutdownNow()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down executor service", e)
        }
        executorService = null

        closeResources(vpnInterface)
        vpnInterface = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startVpnThreads() {
        val deviceToNetworkUDPQueue = ConcurrentLinkedQueue<Packet>()
        val deviceToNetworkTCPQueue = ConcurrentLinkedQueue<Packet>()
        val networkToDeviceQueue = ConcurrentLinkedQueue<ByteBuffer>()

        // Simplified to one main thread for packet routing
        executorService = Executors.newSingleThreadExecutor().also {
            it.submit(VPNRunnable(vpnInterface!!.fileDescriptor, deviceToNetworkUDPQueue, deviceToNetworkTCPQueue, networkToDeviceQueue))
        }
    }

    private fun setupVpnInterface(): Boolean {
        if (vpnInterface != null) return true

        val builder = Builder()
            .addAddress(ConstVariable.VPN_ADDRESS, 32)
            .addRoute(ConstVariable.VPN_ROUTE, 0)
            .addDnsServer(ConstVariable.VPN_DNS)
            .setSession(getString(R.string.app_name))

        val configureIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, configureIntent, pendingIntentFlags)
        builder.setConfigureIntent(pendingIntent)

        try {
            vpnInterface = builder.establish()
            return vpnInterface != null
        } catch (e: Exception) {
            Log.e(TAG, "Error establishing VPN interface", e)
            return false
        }
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN service revoked by user.")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.d(TAG, "VPN Service onDestroy")
        unregisterReceiver(stateRequestReceiver)
        if (isRunning) {
            stopVpn()
        }
        super.onDestroy()
    }

    private fun sendStateBroadcast() {
        val intent = Intent(ACTION_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_RUNNING, isRunning)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotification(): Notification {
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("VHosts VPN is running")
            .setContentText("Tap to manage.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VHosts VPN", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "VhostsService"

        const val ACTION_START = "com.github.xfalcon.vhosts.vservice.START"
        const val ACTION_STOP = "com.github.xfalcon.vhosts.vservice.STOP"
        const val ACTION_CONFIGURE = "com.github.xfalcon.vhosts.vservice.CONFIGURE"

        const val ACTION_STATE_CHANGED = "com.github.xfalcon.vhosts.vservice.STATE_CHANGED"
        const val ACTION_REQUEST_STATE = "com.github.xfalcon.vhosts.vservice.REQUEST_STATE"
        const val EXTRA_IS_RUNNING = "EXTRA_IS_RUNNING"


        private const val CHANNEL_ID = "VhostsVpnChannel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        private var isRunning = false

        fun closeResources(vararg resources: Closeable?) {
            for (resource in resources) {
                try {
                    resource?.close()
                } catch (e: IOException) {
                    // Ignored
                }
            }
        }
    }
}

private class VPNRunnable(
    private val vpnFileDescriptor: FileDescriptor,
    private val deviceToNetworkUDPQueue: ConcurrentLinkedQueue<Packet>,
    private val deviceToNetworkTCPQueue: ConcurrentLinkedQueue<Packet>,
    private val networkToDeviceQueue: ConcurrentLinkedQueue<ByteBuffer>
) : Runnable {

    companion object {
        private const val TAG = "VPNRunnable"
    }

    override fun run() {
        Log.d(TAG, "VPNRunnable started.")
        val vpnInput = FileInputStream(vpnFileDescriptor).channel
        val vpnOutput = FileOutputStream(vpnFileDescriptor).channel

        while (!Thread.interrupted()) {
            try {
                val bufferToNetwork = ByteBufferPool2.acquire()
                val readBytes = vpnInput.read(bufferToNetwork)
                if (readBytes > 0) {
                    bufferToNetwork.flip()
                    val packet = Packet(bufferToNetwork)

                    if (packet.isUDP()) {
                        deviceToNetworkUDPQueue.offer(packet)
                    } else if (packet.isTCP()) {
                        deviceToNetworkTCPQueue.offer(packet)
                    } else {
                        Log.w(TAG, "Unknown packet protocol: ${packet.protocol}")
                        ByteBufferPool2.release(bufferToNetwork)
                    }
                } else {
                    ByteBufferPool2.release(bufferToNetwork)
                    if (readBytes == 0) Thread.sleep(10) // Avoid busy-loop
                }

                val bufferFromNetwork = networkToDeviceQueue.poll()
                if (bufferFromNetwork != null) {
                    bufferFromNetwork.flip()
                    while (bufferFromNetwork.hasRemaining()) {
                        vpnOutput.write(bufferFromNetwork)
                    }
                    ByteBufferPool2.release(bufferFromNetwork)
                }

            } catch (e: InterruptedException) {
                Log.i(TAG, "VPNRunnable interrupted, stopping.")
                Thread.currentThread().interrupt()
            } catch (e: IOException) {
                Log.e(TAG, "VPNRunnable IO Error", e)
                break
            } catch (e: Exception) {
                Log.e(TAG, "VPNRunnable Error", e)
                break
            }
        }
        Log.d(TAG, "VPNRunnable stopped.")
    }
}