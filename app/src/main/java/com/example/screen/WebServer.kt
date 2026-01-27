package com.example.screen

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors

class WebServer(
    private val context: Context,
    port: Int,
    private val imageQueue: BlockingQueue<ByteArray>
) : NanoWSD(port) {

    override fun serve(session: IHTTPSession): Response {
        return if (isWebsocketRequested(session)) {
            super.serve(session)
        } else {
            serveStaticFiles(session.uri)
        }
    }

    private fun serveStaticFiles(uri: String): Response {
        Log.d(TAG, "Serving static file: $uri")
        val assetManager = context.assets
        val finalUri = if (uri == "/") "/index.html" else uri
        return try {
            val inputStream: InputStream = assetManager.open("webroot$finalUri")
            val mimeType = when {
                finalUri.endsWith(".html") -> "text/html"
                finalUri.endsWith(".js") -> "application/javascript"
                finalUri.endsWith(".css") -> "text/css"
                finalUri.endsWith(".png") -> "image/png"
                finalUri.endsWith(".jpeg") -> "image/jpeg"
                finalUri.endsWith(".svg") -> "image/svg+xml"
                finalUri.endsWith(".ico") -> "image/x-icon"
                else -> "application/octet-stream"
            }
            newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        } catch (e: IOException) {
            Log.e(TAG, "File not found: webroot$finalUri", e)
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    override fun openWebSocket(session: IHTTPSession): WebSocket {
        return ScreenWebSocket(session, imageQueue)
    }

    private class ScreenWebSocket(
        session: IHTTPSession,
        private val imageQueue: BlockingQueue<ByteArray>
    ) : WebSocket(session) {

        private val executor = Executors.newSingleThreadExecutor()

        override fun onOpen() {
            Log.d(TAG, "WebSocket opened")
            executor.submit {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val image = imageQueue.take()
                        send(image)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
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
            if (!executor.isShutdown) {
                executor.shutdownNow()
            }
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
            Log.d(TAG, "Pong received from client")
        }

        override fun onException(exception: IOException) {
            Log.e(TAG, "WebSocket exception", exception)
            if (!executor.isShutdown) {
                executor.shutdownNow()
            }
        }
    }

    companion object {
        private const val TAG = "WebServer"
        const val HEART_BEAT = "heartbeat"
    }
}