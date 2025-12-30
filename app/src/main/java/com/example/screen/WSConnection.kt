package com.example.screen

import io.ktor.websocket.DefaultWebSocketSession

class WSConnection(private val s : DefaultWebSocketSession) {
    public var session: DefaultWebSocketSession = s
}