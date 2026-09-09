package com.example.screen

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

        override fun onOpen() {
            Log.d(TAG, "WebSocket opened")
            socketExecutor.submit {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val image = imageQueue.take()
                        send(image)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.d(TAG, "WebSocket thread interrupted", e)
                } catch (e: IOException) {
                    Log.e(TAG, "Error sending frame, closing connection", e)
                }
            }
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode,
            reason: String?,
            initiatedByRemote: Boolean
        ) {
            Log.d(TAG, "WebSocket closed. Code: $code, Reason: $reason")
        }

        override fun onMessage(message: WebSocketFrame) {
            if (message.textPayload == HEART_BEAT) {
                try {
                    send(HEART_BEAT)
                } catch (e: IOException) {
                    Log.e(TAG, "Error sending heartbeat", e)
                }
            }
        }

        override fun onPong(pong: WebSocketFrame) {
            Log.d(TAG, "Pong received from client $pong")
        }

        override fun onException(exception: IOException) {
            Log.e(TAG, "WebSocket exception", exception)
        }
    }

    companion object {
        private const val TAG = "WebServer"
        const val HEART_BEAT = "heartbeat"
    }
}