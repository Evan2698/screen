package com.example.screen

import android.util.Log

import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlin.time.Duration.Companion.seconds


class WebServer(private val onConnect: suspend (ReceiveChannel<Frame>, SendChannel<Frame>) -> Unit) {

    private val server = embeddedServer(Netty, port = 8080) {
        install(WebSockets) {
            pingPeriod = 25.seconds
            timeout = 25.seconds
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        routing {
            staticResources("/", "assets/webroot") {
                default("index.html")
            }
            webSocket("/screen") {
                Log.d(TAG, "WebSocket client connected to /screen")
                onConnect(incoming, outgoing)
            }
        }
    }

    fun start() {
        server.start(wait = false)
    }

    fun stop() {
        server.stop(1000, 2000)
    }

    companion object {
        private const val TAG = "WebServer"
        public const val HEART_BEAT="heartbeat"
    }
}
