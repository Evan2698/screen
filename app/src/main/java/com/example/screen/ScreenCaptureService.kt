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
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.Frame.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect

class ScreenCaptureService : Service() {

    private val serviceJob = SupervisorJob()


    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var windowManager: WindowManager

    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null


    private var server: WebServer? = null

    private val _imageQueue =  MutableSharedFlow<ByteArray>(replay = 0, 3, BufferOverflow.DROP_OLDEST)
    private val frameFlow = _imageQueue.asSharedFlow()


    private var interruptThread: Boolean = false



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
            stopSelf()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
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
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)

                if (resultCode == Activity.RESULT_OK && data != null) {
                    Log.d(TAG, "Permission granted, starting capture")
                    startCapture(resultCode, data)
                } else {
                    Log.w(TAG, "Permission denied or data invalid. Code: $resultCode, Data is null: ${data == null}")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "Received stop action")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }





    private fun startKtorServer() {
        Log.d(TAG, "Starting Ktor server...")
        server = WebServer(onConnect = { incoming, outgoing ->
            handleWebSocketConnection(incoming, outgoing)
        })
        server!!.start()
        Log.d(TAG, "Ktor server started on port $SERVER_PORT")
    }


    private suspend fun handleWebSocketConnection(incoming: ReceiveChannel<Frame>, outgoing: SendChannel<Frame>) {

        coroutineScope {
            // job 1
            launch {
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            // Directly send the heartbeat; send is a suspend function
                            outgoing.send(Frame.Text(WebServer.HEART_BEAT))
                        }
                    }
                } catch (e: Exception) {
                    // Handle connection resets or closures gracefully
                    Log.d(TAG, "exception in job1", e)
                }
            }

            // job2
            launch {
                try {
                    frameFlow.collect{
                        image -> outgoing.send(Frame.Binary(true, image))
                    }
                }catch (e: Exception){
                    Log.d(TAG, "exception in job2", e)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startCapture(resultCode: Int, data: Intent) {
        Log.d(TAG, "startCapture called")
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(mediaProjectionCallback, backgroundHandler)

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

        val realWidth = (width * SCREEN_RATIO).toInt()
        val realHeight = (height * SCREEN_RATIO).toInt()

        Log.d(TAG, "Screen dimensions: $realWidth x $realHeight @ $density dpi")

        imageReader = ImageReader.newInstance(realWidth, realHeight, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener(this::onImageAvailable, backgroundHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            realWidth, realHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )
        Log.d(TAG, "VirtualDisplay created")
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
                // Fast path: No padding, create one bitmap and copy directly.
                finalBitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                finalBitmap.copyPixelsFromBuffer(buffer)
            } else {
                // Slow path: Padding exists. Create a temporary larger bitmap, then crop.
                var paddedBitmap: Bitmap? = null
                try {
                    val paddedWidth = width + rowPadding / pixelStride
                    paddedBitmap = createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    paddedBitmap.copyPixelsFromBuffer(buffer)
                    paddedBitmap?.let { // Use a null-safe let to handle the nullable Bitmap
                        finalBitmap = Bitmap.createBitmap(it, 0, 0, width, height)
                    }
                } finally {
                    paddedBitmap?.recycle()
                }
            }

            ByteArrayOutputStream().use { stream ->
                finalBitmap?.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                _imageQueue.tryEmit(stream.toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process and compress bitmap", e)
        } finally {
            finalBitmap?.recycle()
        }
    }

    private fun stopCapture() {
        Log.d(TAG, "stopCapture called: Releasing media projection resources.")
        this.interruptThread = true
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

        isRunning = false
        sendStateBroadcast()

        stopForeground(Service.STOP_FOREGROUND_REMOVE)

        // Cancel all coroutines BEFORE stopping the components they might be using.
        serviceJob.cancel()
        Log.d(TAG, "Service coroutine scope cancelled.")

        server?.stop()
        Log.d(TAG, "Ktor server stopped.")
        
        stopCapture()
        stopBackgroundThread()
        Log.d(TAG, "Capture and background thread stopped.")

        try {
            unregisterReceiver(stateRequestReceiver)
            Log.d(TAG, "State request receiver unregistered.")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "State request receiver was not registered or already unregistered.")
        }

        Log.d(TAG, "Service fully destroyed.")
    }

    private fun sendStateBroadcast() {
        val intent = Intent(ACTION_STATE_CHANGED)
        intent.putExtra(EXTRA_IS_RUNNING, isRunning)
        if (isRunning) {
            getLocalIpAddress()?.let {
                var address = "http://$it:$SERVER_PORT/"
                if (URL_ADDRESS.startsWith(address)){
                    address = URL_ADDRESS
                } else {
                    address  += " or $URL_ADDRESS"
                }
                intent.putExtra(EXTRA_SERVER_ADDRESS, address)
            }
        }
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun getLocalIpAddress(): String? {
        try {
            return NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting IP address", ex)
        }
        return null
    }

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

        public const val SCREEN_RATIO = 0.30f
        private const val SERVER_PORT = 8080

        @Volatile
        var isRunning = false
            private set

        private const val CHANNEL_ID = "ScreenCaptureChannel"
        private const val NOTIFICATION_ID = 1002
    }
}