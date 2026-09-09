package com.example.screen

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.LinkedBlockingQueue

class ScreenCaptureService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var windowManager: WindowManager

    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var server: WebServer? = null
    private val imageQueue = LinkedBlockingQueue<ByteArray>(10)
    private var isStopping = false

    private val stateRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REQUEST_STATE) {
                Log.d(TAG, "Received state request from UI")
                sendStateBroadcast()
            }
        }
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped by system, stopping service.")
            stopServiceSafely()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        startBackgroundThread()
        createNotificationChannel()
        startWebServer()
        registerStateRequestReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                isStopping = false
                handleStartAction(intent)
            }
            ACTION_STOP -> stopServiceSafely()
        }

        return START_NOT_STICKY
    }

    private fun handleStartAction(intent: Intent) {
        startForeground(NOTIFICATION_ID, createNotification())

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)

        if (resultCode == Activity.RESULT_OK && data != null) {
            Log.d(TAG, "Permission granted, starting capture")
            restartWebServer()
            startCapture(resultCode, data)
            setRunningState(true)
            sendStateBroadcast()
        } else {
            Log.w(TAG, "Permission denied or data invalid. Code: $resultCode, Data is null: ${data == null}")
            stopServiceSafely()
        }
    }

    private fun registerStateRequestReceiver() {
        val intentFilter = IntentFilter(ACTION_REQUEST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateRequestReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateRequestReceiver, intentFilter)
        }
    }

    private fun startWebServer() {
        Log.d(TAG, "Starting Web server...")
        if (server != null) {
            server?.stop()
            server = null
        }
        server = WebServer(this, SERVER_PORT, imageQueue)
        try {
            server?.start(TIME_OUT, false)
            Log.d(TAG, "Web server started on port $SERVER_PORT")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start web server", e)
        }
    }

    private fun restartWebServer() {
        server?.stop()
        server = null
        startWebServer()
    }

    @Suppress("DEPRECATION")
    private fun startCapture(resultCode: Int, data: Intent) {
        Log.d(TAG, "startCapture called")

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(mediaProjectionCallback, backgroundHandler)

        val captureSize = calculateCaptureSize()
        Log.d(TAG, "Screen dimensions: ${captureSize.width} x ${captureSize.height} @ ${captureSize.density} dpi")

        imageReader = ImageReader.newInstance(
            captureSize.width,
            captureSize.height,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader?.setOnImageAvailableListener(this::onImageAvailable, backgroundHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            captureSize.width,
            captureSize.height,
            captureSize.density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )

        Log.d(TAG, "VirtualDisplay created")
    }

    private fun calculateCaptureSize(): CaptureSize {
        val width: Int
        val height: Int
        val density: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
            density = resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
            density = metrics.densityDpi
        }

        return CaptureSize(
            width = (width * SCREEN_RATIO).toInt(),
            height = (height * SCREEN_RATIO).toInt(),
            density = density
        )
    }

    private fun onImageAvailable(reader: ImageReader) {
        try {
            reader.acquireLatestImage()?.use { image ->
                processImage(image)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        }
    }

    private fun processImage(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        var finalBitmap: Bitmap? = null
        try {
            if (rowPadding == 0) {
                finalBitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                finalBitmap.copyPixelsFromBuffer(buffer)
            } else {
                var paddedBitmap: Bitmap? = null
                try {
                    val paddedWidth = width + rowPadding / pixelStride
                    paddedBitmap = createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    paddedBitmap.copyPixelsFromBuffer(buffer)
                    finalBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
                } finally {
                    paddedBitmap?.recycle()
                }
            }

            ByteArrayOutputStream().use { stream ->
                finalBitmap?.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                val frame = stream.toByteArray()
                if (!imageQueue.offer(frame)) {
                    imageQueue.poll()
                    imageQueue.offer(frame)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process and compress bitmap", e)
        } finally {
            finalBitmap?.recycle()
        }
    }

    private fun stopCapture() {
        Log.d(TAG, "stopCapture called: Releasing media projection resources.")
        backgroundHandler?.post {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()

            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            Log.d(TAG, "Capture resources released on background thread.")
        }
    }

    private fun startBackgroundThread() {
        handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)
    }

    private fun stopBackgroundThread() {
        handlerThread?.quitSafely()
        try {
            handlerThread?.join(500)
            handlerThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕捕获服务")
            .setContentText("正在捕获屏幕内容...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy: Beginning cleanup of all resources.")

        try {
            unregisterReceiver(stateRequestReceiver)
            Log.d(TAG, "State request receiver unregistered.")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "State request receiver was not registered or already unregistered.", e)
        }

        if (server != null) {
            server?.stop()
            server = null
        }

        stopCapture()
        stopBackgroundThread()
        isStopping = false
        Log.d(TAG, "Service fully destroyed.")
    }

    private fun stopServiceSafely() {
        if (isStopping) return
        isStopping = true

        setRunningState(false)
        currentServerAddress = null
        sendStateBroadcast()

        stopForeground(STOP_FOREGROUND_REMOVE)
        server?.stop()
        server = null
        Log.d(TAG, "Web server stopped.")

        stopCapture()
        stopBackgroundThread()
        Log.d(TAG, "Capture and background thread stopped.")
        stopSelf()
    }

    private fun setRunningState(running: Boolean) {
        isRunning = running
        if (!running) {
            currentServerAddress = null
        }
    }

    private fun sendStateBroadcast() {
        val intent = Intent(ACTION_STATE_CHANGED)
        intent.putExtra(EXTRA_IS_RUNNING, isRunning)

        val address = if (isRunning) {
            getLocalIpAddress()?.let { localIp ->
                val resolvedAddress = buildAddress(localIp)
                resolvedAddress
            }
        } else {
            null
        }

        currentServerAddress = address
        if (address != null) {
            intent.putExtra(EXTRA_SERVER_ADDRESS, address)
        }

        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun buildAddress(localIp: String): String {
        var resolvedAddress = "http://$localIp:$SERVER_PORT/"
        if (URL_ADDRESS.startsWith(resolvedAddress)) {
            resolvedAddress = URL_ADDRESS
        } else {
            resolvedAddress += " or $URL_ADDRESS"
        }
        return resolvedAddress
    }

    private fun getLocalIpAddress(): String? {
        try {
            return NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting IP address", ex)
        }
        return null
    }

    private data class CaptureSize(
        val width: Int,
        val height: Int,
        val density: Int
    )

    companion object {
        private const val TAG = "ScreenCaptureService"

        const val ACTION_START = "com.example.screen.ACTION_START"
        const val ACTION_STOP = "com.example.screen.ACTION_STOP"
        const val ACTION_REQUEST_STATE = "com.example.screen.ACTION_REQUEST_STATE"
        const val ACTION_STATE_CHANGED = "com.example.screen.ACTION_STATE_CHANGED"

        const val EXTRA_IS_RUNNING = "EXTRA_IS_RUNNING"
        const val EXTRA_SERVER_ADDRESS = "EXTRA_SERVER_ADDRESS"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"

        const val URL_ADDRESS = "http://9.9.9.9:8080/"
        const val SCREEN_RATIO = 0.30f
        private const val SERVER_PORT = 8080

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var currentServerAddress: String? = null
            private set

        private const val CHANNEL_ID = "ScreenCaptureChannel"
        private const val NOTIFICATION_ID = 1002
        private const val TIME_OUT = 30 * 1000
    }
}
