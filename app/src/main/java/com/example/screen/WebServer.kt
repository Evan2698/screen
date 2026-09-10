package com.example.screen

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class WebServer(
    private val context: Context,
    port: Int,
    private val imageQueue: BlockingQueue<ByteArray>
) : NanoWSD(port) {

    private val socketExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun serve(session: IHTTPSession): Response {
        return if (isWebsocketRequested(session)) {
            super.serve(session)
        } else {
            serveStaticFiles(session.uri)
        }
    }

    private fun serveStaticFiles(uri: String): Response {
        val assetPath = if (uri == "/") "/index.html" else uri
        Log.d(TAG, "Serving static file: $assetPath")

        return try {
            val inputStream: InputStream = context.assets.open("webroot$assetPath")
            val mimeType = mimeTypeFor(assetPath)
            newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        } catch (e: IOException) {
            Log.e(TAG, "File not found: webroot$assetPath", e)
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    override fun openWebSocket(session: IHTTPSession): WebSocket {
        return ScreenWebSocket(session, imageQueue, socketExecutor)
    }

    override fun stop() {
        socketExecutor.shutdownNow()
        super.stop()
    }

    private fun mimeTypeFor(path: String): String {
        return when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpeg") || path.endsWith(".jpg") -> "image/jpeg"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".ico") -> "image/x-icon"
            else -> "application/octet-stream"
        }
    }

    private class ScreenWebSocket(
        session: IHTTPSession,
        private val imageQueue: BlockingQueue<ByteArray>,
        private val socketExecutor: ExecutorService
    ) : WebSocket(session) {

        @Volatile
        private var closed = false
        @Volatile
        private var senderTask: Future<*>? = null

        override fun onOpen() {
            Log.d(TAG, "WebSocket opened")
            imageQueue.clear()
            senderTask = socketExecutor.submit {
                try {
                    while (!closed && !Thread.currentThread().isInterrupted) {
                        val image = imageQueue.poll(FRAME_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            ?: continue
                        send(image)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    if (!closed) {
                        Log.d(TAG, "WebSocket sender thread interrupted.")
                    }
                } catch (e: IOException) {
                    if (isExpectedSocketClose(e)) {
                        Log.d(TAG, "WebSocket closed normally while sending frame.")
                    } else {
                        Log.e(TAG, "Error sending frame, closing connection", e)
                    }
                } finally {
                    senderTask = null
                }
            }
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode,
            reason: String?,
            initiatedByRemote: Boolean
        ) {
            closeSender()
            Log.d(TAG, "WebSocket closed. Code: $code, Reason: $reason")
        }

        override fun onMessage(message: WebSocketFrame) {
            if (message.textPayload == HEART_BEAT) {
                try {
                    send(HEART_BEAT)
                } catch (e: IOException) {
                    if (isExpectedSocketClose(e)) {
                        Log.d(TAG, "WebSocket closed normally while sending heartbeat.")
                    } else {
                        Log.e(TAG, "Error sending heartbeat", e)
                    }
                }
            }
        }

        override fun onPong(pong: WebSocketFrame) {
            Log.d(TAG, "Pong received from client $pong")
        }

        override fun onException(exception: IOException) {
            closeSender()
            if (isExpectedSocketClose(exception)) {
                Log.d(TAG, "WebSocket closed normally.")
            } else {
                Log.e(TAG, "WebSocket exception", exception)
            }
        }

        private fun closeSender() {
            closed = true
            senderTask?.cancel(true)
            senderTask = null
        }

        private fun isExpectedSocketClose(exception: IOException): Boolean {
            val message = exception.message ?: ""
            return message.contains("Socket closed", ignoreCase = true)
                || message.contains("Socket is closed", ignoreCase = true)
                || message.contains("Software caused connection abort", ignoreCase = true)
                || message.contains("Connection reset", ignoreCase = true)
                || message.contains("Broken pipe", ignoreCase = true)
                || exception is java.net.SocketException && (
                    message.contains("closed", ignoreCase = true)
                        || message.contains("abort", ignoreCase = true)
                        || message.contains("reset", ignoreCase = true)
                )
        }
    }

    companion object {
        private const val TAG = "WebServer"
        private const val FRAME_POLL_TIMEOUT_MS = 500L
        const val HEART_BEAT = "heartbeat"
    }
}