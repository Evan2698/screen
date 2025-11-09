/**
 * A modern WebSocket client with heartbeat and auto-reconnection features.
 * Refactored to use ES6 classes for better structure and readability.
 */
class WebsocketHeartbeatJs {
    // Public user-overridable event handlers
    onopen = () => {};
    onmessage = () => {};
    onclose = () => {};
    onerror = () => {};
    onreconnect = () => {};

    // Private instance variables
    #opts;
    #ws = null;
    #repeat = 0;
    #lockReconnect = false;
    #forbidReconnect = false;
    #pingTimeoutId = null;
    #pongTimeoutId = null;

    constructor(opts) {
        this.#opts = {
            url: opts.url,
            protocols: opts.protocols || null, // Default to null instead of ''
            pingTimeout: opts.pingTimeout || 15000,
            pongTimeout: opts.pongTimeout || 10000,
            reconnectTimeout: opts.reconnectTimeout || 2000,
            pingMsg: opts.pingMsg || 'heartbeat',
            repeatLimit: opts.repeatLimit || null,
            msgType: opts.msgType || 'arraybuffer',
        };
        this.#createWebSocket();
    }

    /**
     * Creates and initializes a new WebSocket instance.
     * @private
     */
    #createWebSocket() {
        try {
            // CORRECTED: Only pass the protocols argument if it's valid and non-empty.
            if (this.#opts.protocols) {
                this.#ws = new WebSocket(this.#opts.url, this.#opts.protocols);
            } else {
                this.#ws = new WebSocket(this.#opts.url);
            }
            this.#ws.binaryType = this.#opts.msgType;
            this.#initEventHandlers();
        } catch (e) {
            console.error("WebSocket creation failed:", e);
            this.#reconnect();
        }
    }

    /**
     * Initializes WebSocket event handlers.
     * @private
     */
    #initEventHandlers() {
        this.#ws.onclose = (e) => {
            this.onclose(e);
            this.#reconnect();
        };
        this.#ws.onerror = (e) => {
            this.onerror(e);
        };
        this.#ws.onopen = (e) => {
            this.#repeat = 0;
            this.onopen(e);
            this.#heartCheck();
        };
        this.#ws.onmessage = (event) => {
            this.onmessage(event);
            this.#heartCheck(); // Reset heartbeat on any message
        };
    }

    /**
     * Sends a message through the WebSocket.
     * @param {string|ArrayBuffer} msg - The message to send.
     */
    send(msg) {
        if (this.#ws && this.#ws.readyState === WebSocket.OPEN) {
            this.#ws.send(msg);
        }
    }

    /**
     * Manually closes the WebSocket connection.
     * Prevents automatic reconnection.
     */
    close() {
        this.#forbidReconnect = true;
        this.#heartReset();
        if (this.#ws) {
            this.#ws.close();
        }
    }

    /**
     * Initiates a reconnection attempt.
     * @private
     */
    #reconnect() {
        if (this.#opts.repeatLimit !== null && this.#opts.repeatLimit <= this.#repeat) {
            return; // Exceeded reconnection limit
        }
        if (this.#lockReconnect || this.#forbidReconnect) {
            return;
        }

        this.#lockReconnect = true;
        this.#repeat++;
        this.onreconnect();

        setTimeout(() => {
            this.#createWebSocket();
            this.#lockReconnect = false;
        }, this.#opts.reconnectTimeout);
    }

    /**
     * Resets and starts the heartbeat check.
     * @private
     */
    #heartCheck() {
        this.#heartReset();
        this.#heartStart();
    }

    /**
     * Starts the ping/pong heartbeat mechanism.
     * @private
     */
    #heartStart() {
        if (this.#forbidReconnect) return;

        this.#pingTimeoutId = setTimeout(() => {
            const ping = typeof this.#opts.pingMsg === 'function' ? this.#opts.pingMsg() : this.#opts.pingMsg;
            this.send(ping);

            this.#pongTimeoutId = setTimeout(() => {
                this.#ws.close();
            }, this.#opts.pongTimeout);
        }, this.#opts.pingTimeout);
    }

    /**
     * Clears any existing heartbeat timers.
     * @private
     */
    #heartReset() {
        clearTimeout(this.#pingTimeoutId);
        clearTimeout(this.#pongTimeoutId);
    }

    /**
     * Gets the current state of the WebSocket.
     */
    get readyState() {
        return this.#ws ? this.#ws.readyState : WebSocket.CLOSED;
    }
}

// Expose the class to the global window object for backward compatibility
if (typeof window !== 'undefined') {
    window.WebsocketHeartbeatJs = WebsocketHeartbeatJs;
}
