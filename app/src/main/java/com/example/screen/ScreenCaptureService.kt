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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer


@Suppress("DEPRECATION")
class ScreenCaptureService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var windowManager: WindowManager

    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val frameFlow = MutableSharedFlow<ByteArray>(replay = 1)
    private var server: NettyApplicationEngine? = null


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
            Log.d(TAG, "MediaProjection stopped by system")
            stopCapture()
        }
    }

    @SuppressLint("UnprotectedReceiver", "UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startBackgroundThread()
        createNotificationChannel()
        startKtorServer()

        val intentFilter = IntentFilter(ACTION_REQUEST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateRequestReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateRequestReceiver, intentFilter)
        }

        isRunning = true
        sendStateBroadcast()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand with action: ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                if (resultCode == Activity.RESULT_OK && data != null) {
                    Log.d(TAG, "Permission granted, starting capture")
                    startCapture(resultCode, data)
                } else {
                    Log.w(TAG, "Permission denied or data invalid. Stopping service. Result Code: $resultCode, Data is null: ${data == null}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        stopForeground(true)
                    }
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "Received stop action")
                stopCapture()
            }
        }
        return START_NOT_STICKY
    }

    private fun startKtorServer() {
        Log.d(TAG, "Starting Ktor server...")
        val assetManager = applicationContext.assets
        server = embeddedServer(Netty, port = SERVER_PORT) {
            install(WebSockets){
                pingPeriodMillis = 15000L // 15 秒
                timeoutMillis = 15000L     // 15秒
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            routing {
                // WebSocket for screen streaming
                webSocket("/screen") {
                    Log.d(TAG, "WebSocket client connected")
                    try {
                        frameFlow.collectLatest { frame ->
                            send(Frame.Binary(true, frame))
                        }
                    } finally {
                        Log.d(TAG, "WebSocket client disconnected")
                    }
                }

                // Catch-all route for static assets from the "webroot" folder
                get("/{path...}") {
                    var path = call.parameters.getAll("path")?.joinToString("/") ?: "index.html"
                    if (path.isBlank()) {
                        path = "index.html"
                    }
                    Log.d(TAG, "Request for static asset: $path")

                    try {
                        val fileContent = assetManager.open("webroot/$path").readBytes()
                        val contentType = when {
                            path.endsWith(".html") -> ContentType.Text.Html
                            path.endsWith(".js") -> ContentType.Text.JavaScript
                            path.endsWith(".css") -> ContentType.Text.CSS
                            path.endsWith(".ico") -> ContentType.Image.XIcon
                            path.endsWith(".png") -> ContentType.Image.PNG
                            path.endsWith(".jpg") -> ContentType.Image.JPEG
                            path.endsWith(".gif") -> ContentType.Image.GIF
                            path.endsWith(".svg") -> ContentType.Image.SVG
                            else -> ContentType.Application.OctetStream
                        }
                        call.respondBytes(fileContent, contentType)
                    } catch (e: IOException) {
                        Log.w(TAG, "Asset not found: webroot/$path")
                        call.respond(HttpStatusCode.NotFound, "Asset not found: $path")
                    }
                }

                get("/") {
                    try {
                        val fileContent = assetManager.open("webroot/index.html").readBytes()
                        call.respondBytes(fileContent, ContentType.Text.Html)
                    } catch (e: IOException) {
                        Log.e(TAG, "index.html not found in assets!")
                        call.respond(HttpStatusCode.NotFound, "index.html not found")
                    }
                }
            }
        }.start()
        Log.d(TAG, "Ktor server started on port $SERVER_PORT")
    }

    @SuppressLint("DEPRECATION")
    private fun startCapture(resultCode: Int, data: Intent) {
        Log.d(TAG, "startCapture called")
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(mediaProjectionCallback, backgroundHandler)

        var width: Int
        var height: Int
        val density: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
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

        // 这个地方可以做文章
        //可以从网页获取图片大小，这样可以适应屏幕
        val realWidth = width.toFloat() * 0.518
        val realHeight = height.toFloat() * 0.518
        width = realWidth.toInt()
        height = realHeight.toInt()

        Log.d(TAG, "Screen dimensions: $realWidth x $realHeight @ $density dpi")

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener(this::onImageAvailable, backgroundHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )
        Log.d(TAG, "VirtualDisplay created")
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image: Image? = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring image: ", e)
            null
        }

        if (image != null) {
            processImage(image)
            image.close()
        }
    }

    @SuppressLint("UseKtx")
    private fun processImage(image: Image) {
        val planes: Array<Image.Plane> = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride: Int = planes[0].pixelStride
        val rowStride: Int = planes[0].rowStride
        val rowPadding: Int = rowStride - pixelStride * image.width
        val imgWidth: Int = image.width + rowPadding / pixelStride
        val imgHeight: Int = image.height

        val bitmap: Bitmap = Bitmap.createBitmap(
            imgWidth,
            imgHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)

        ByteArrayOutputStream().use { stream ->
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
            frameFlow.tryEmit(stream.toByteArray())
        }

        croppedBitmap.recycle()
        bitmap.recycle()
    }

    fun stopCapture() {
        Log.d(TAG, "stopCapture called")
        backgroundHandler?.post {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()

            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            Log.d(TAG, "Capture resources released")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startBackgroundThread() {
        handlerThread = HandlerThread("ScreenCaptureThread")
        handlerThread?.start()
        backgroundHandler = Handler(handlerThread!!.looper)
        Log.d(TAG, "Background thread started")
    }

    private fun stopBackgroundThread() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            handlerThread?.quitSafely()
        } else {
            handlerThread?.quit()
        }
        try {
            handlerThread?.join(500)
            handlerThread = null
            backgroundHandler = null
            Log.d(TAG, "Background thread stopped")
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕捕获服务")
            .setContentText("正在捕获屏幕内容...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        unregisterReceiver(stateRequestReceiver)
        stopCapture()
        server?.stop(1_000, 2_000)
        stopBackgroundThread()

        isRunning = false
        sendStateBroadcast()
    }

    private fun sendStateBroadcast() {
        val intent = Intent(ACTION_STATE_CHANGED)
        intent.putExtra(EXTRA_IS_RUNNING, isRunning)
        if (isRunning) {
            getLocalIpAddress()?.let {
                val address = "ws://$it:$SERVER_PORT/screen"
                intent.putExtra(EXTRA_SERVER_ADDRESS, address)
            }
        }
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun getLocalIpAddress(): String? {
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces().toList()
            for (networkInterface in networkInterfaces) {
                val addresses = networkInterface.inetAddresses.toList()
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting IP address", ex)
        }
        return null
    }

    companion object {
        private const val TAG = "ScreenCaptureService"

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"

        const val ACTION_STATE_CHANGED = "com.example.screen.ACTION_STATE_CHANGED"
        const val ACTION_REQUEST_STATE = "com.example.screen.ACTION_REQUEST_STATE"
        const val EXTRA_IS_RUNNING = "EXTRA_IS_RUNNING"
        const val EXTRA_SERVER_ADDRESS = "EXTRA_SERVER_ADDRESS"

        const val SERVER_PORT = 8080

        @Volatile
        var isRunning = false
            private set

        private const val CHANNEL_ID = "ScreenCaptureChannel"
        private const val NOTIFICATION_ID = 1002
    }
}