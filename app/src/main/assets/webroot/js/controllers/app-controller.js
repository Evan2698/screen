import { TouchController } from './touch-controller.js';

/**
 * Main application controller.
 * Manages UI, WebSocket connections, and rendering.
 */
export default class AppController {
    constructor() {
        this.joinButton = document.getElementById("join");
        this.fullScreenButton = document.getElementById("fullscreen");
        this.homeButton = document.getElementById("home");
        this.backButton = document.getElementById("back");
        this.toggleFpsButton = document.getElementById("toggle-fps");
        this.streamCanvas = document.getElementById("screen");

        this.canvasContext = this.streamCanvas.getContext("2d");
        this.imageSocket = null;
        this.touchSocket = null;
        this.touchController = null;
        this.imageQueue = [];
        this.imageWorker = null;
        this.animationFrameId = null;
        this.connectionState = 'disconnected'; // 'disconnected', 'connecting', 'connected', 'disconnecting'
        this._lastFrameTime = 0;
        this.intervalValue = 16; // < 16.67 , indicate use fps of api requestAnimationFrame,  about is 60 fps
        this.frameCount = 5;  // frame threshold
        // FPS tracking
        this._fps = 0;
        this._fpsLastTime = performance.now();
        this._fpsFrameCounter = 0;
        this.showFps = false;

        this.#registerEvents();
        this.#updateUI();
        this.#startDrawing();
    }

    #registerEvents() {
        this.joinButton.addEventListener("click", this.#onJoinClick.bind(this));
        this.fullScreenButton.addEventListener("click", this.#onFullScreenClick.bind(this));
        this.homeButton.addEventListener("click", () => this.#sendKey("H"));
        this.backButton.addEventListener("click", () => this.#sendKey("B"));
        if (this.toggleFpsButton) {
            this.toggleFpsButton.addEventListener('click', () => {
                this.showFps = !this.showFps;
                this.toggleFpsButton.textContent = this.showFps ? 'FPS: On' : 'FPS: Off';
            });
            this.toggleFpsButton.textContent = this.showFps ? 'FPS: On' : 'FPS: Off';
        }
        window.onbeforeunload = this.#destroy.bind(this);
    }

    #destroy() {
        this.#stopDrawing();
        this.#closeAllWebSockets();
    }

    #setConnectionState(state) {
        if (this.connectionState === state) return;
        this.connectionState = state;
        this.#updateUI();
    }

    #isSocketOpen(sock) {
        if (!sock) return false;
        try {
            if (typeof sock.readyState !== 'undefined') return sock.readyState === WebSocket.OPEN;
            if (typeof sock.isConnected !== 'undefined') return !!sock.isConnected;
            if (typeof sock.isAlive !== 'undefined') return !!sock.isAlive;
            if (typeof sock.getStatus === 'function') return !!sock.getStatus().isConnected;
        } catch (e) {
            return false;
        }
        return false;
    }

    #updateUI() {
        switch (this.connectionState) {
            case 'disconnected':
                this.joinButton.textContent = 'Connect';
                this.joinButton.disabled = false;
                this.homeButton.style.visibility = 'hidden';
                this.backButton.style.visibility = 'hidden';
                break;
            case 'connecting':
                this.joinButton.textContent = 'Connecting...';
                this.joinButton.disabled = true;
                break;
            case 'connected':
                this.joinButton.textContent = 'Disconnect';
                this.joinButton.disabled = false;
                this.homeButton.style.visibility = 'visible';
                this.backButton.style.visibility = 'visible';
                break;
            case 'disconnecting':
                this.joinButton.textContent = 'Disconnecting...';
                this.joinButton.disabled = true;
                break;
        }
    }

    #onJoinClick() {
        if (this.connectionState === 'disconnected') {
            this.#connect();
        } else if (this.connectionState === 'connected') {
            this.#disconnect();
        }
    }

    #connect() {
        try {
            this.#setConnectionState('connecting');
            this.#initImageSocket();
            this.#initTouchSocket();
            this.touchController = new TouchController(this.streamCanvas, this.#sendTouchEvent.bind(this));
        } catch (e) {
            console.error("Failed to start mirroring:", e);
            alert(`Error starting connection: ${e.message}`);
            this.#setConnectionState('disconnected');
        }
    }

    #disconnect() {
        this.#setConnectionState('disconnecting');
        this.#closeAllWebSockets();
    }

    #onFullScreenClick() {
        if (this.streamCanvas.requestFullscreen) {
            this.streamCanvas.requestFullscreen();
        }
    }

    #initImageSocket() {
        if (this.imageSocket || this.imageWorker) return;
        const url = `ws://${window.location.host}/screen`;

        // Prefer worker to own the WebSocket and decoding
        try {
            if (window.Worker) {
                this.imageWorker = new Worker('/js/controllers/image-worker.js');
                this.imageWorker.onmessage = (evt) => {
                    const msg = evt.data;
                    if (!msg || !msg.type) return;
                    switch (msg.type) {
                        case 'open':
                            console.log('Image WebSocket (worker) opened.');
                            if (this.#isSocketOpen(this.touchSocket)) {
                                this.#setConnectionState('connected');
                            }
                            break;
                        case 'bitmap':
                            if (msg.bitmap) this.#queueBitmap(msg.bitmap);
                            break;
                        case 'frame':
                            if (msg.buffer) this.#queueImage(msg.buffer);
                            break;
                        case 'text':
                            console.log('Image worker:', msg.data);
                            break;
                        case 'log':
                            console.log('Image worker log:', msg.message);
                            break;
                        case 'close':
                            console.log('Image WebSocket (worker) closed.');
                            this.#setConnectionState('disconnected');
                            break;
                        case 'error':
                            console.warn('Image worker error:', msg.error);
                            this.#setConnectionState('disconnected');
                            break;
                    }
                };
                // instruct worker to init its websocket
                this.imageWorker.postMessage({ type: 'init', url });
                return;
            }
        } catch (err) {
            console.warn('Failed to initialize image worker, falling back to main-thread websocket.', err);
            this.imageWorker = null;
        }

        // Fallback: keep WebSocket in main thread using `WebSocketClient` if available
        if (window.WebSocketClient) {
            this.imageSocket = new window.WebSocketClient({
                url,
                name: 'imageSocket',
                heartbeatInterval: 15000,
                heartbeatTimeout: 15000,
                binaryType: 'arraybuffer',
                onOpen: (e) => {
                    console.log('Image WebSocket connection established.', e);
                    if (this.#isSocketOpen(this.touchSocket)) {
                        this.#setConnectionState('connected');
                    }
                },
                onMessage: (data, e) => {
                    // Wrap to match previous expectations (event-like)
                    this.#handleImage({ data });
                },
                onClose: (e) => {
                    console.log('Image WebSocket closed:', e);
                    this.#setConnectionState('disconnected');
                },
                onError: (e) => {
                    console.log('Image WebSocket error:', e);
                    this.#setConnectionState('disconnected');
                }
            });
        } else {
            // Last-resort native WebSocket
            try {
                this.imageSocket = new WebSocket(url);
                this.imageSocket.binaryType = 'arraybuffer';
                this.imageSocket.onopen = (e) => {
                    console.log('Image WebSocket connection established.', e);
                    if (this.#isSocketOpen(this.touchSocket)) {
                        this.#setConnectionState('connected');
                    }
                };
                this.imageSocket.onmessage = (e) => this.#handleImage(e);
                this.imageSocket.onclose = (e) => { console.log('Image WebSocket closed:', e); this.#setConnectionState('disconnected'); };
                this.imageSocket.onerror = (e) => { console.log('Image WebSocket error:', e); this.#setConnectionState('disconnected'); };
            } catch (err) {
                console.error('Failed to create fallback WebSocket:', err);
                this.#setConnectionState('disconnected');
            }
        }
    }

    #initTouchSocket() {
        if (this.touchSocket) return;
        const url = `ws://${window.location.hostname}:8081/touch`;
        if (window.WebSocketClient) {
            this.touchSocket = new window.WebSocketClient({
                url,
                name: 'touchSocket',
                heartbeatInterval: 15000,
                heartbeatTimeout: 25000,
                onOpen: () => {
                    console.log('Touch WebSocket connection established.');
                    if (this.#isSocketOpen(this.imageSocket)) {
                this.#setConnectionState('connected');
            }
                },
                onClose: () => this.#setConnectionState('disconnected'),
                onError: (e) => { console.log('Touch WebSocket error:', e); this.#setConnectionState('disconnected'); },
                onMessage: (data) => { console.log('Touch websocket: ', data); }
            });
        } else {
            try {
                this.touchSocket = new WebSocket(url);
                this.touchSocket.onopen = () => {
                    console.log('Touch WebSocket connection established.');
                    if (this.#isSocketOpen(this.imageSocket)) {
                        this.#setConnectionState('connected');
                    }
                };
                this.touchSocket.onclose = () => this.#setConnectionState('disconnected');
                this.touchSocket.onerror = (e) => { console.log('Touch WebSocket error:', e); this.#setConnectionState('disconnected'); };
                this.touchSocket.onmessage = (e)=> { console.log('Touch websocket: ', e.data); };
            } catch (err) {
                console.error('Failed to create touch socket:', err);
                this.#setConnectionState('disconnected');
            }
        }
    }

    #closeAllWebSockets() {
        if (this.imageSocket) {
            this.imageSocket.close();
            this.imageSocket = null;
        }
        if (this.touchSocket) {
            this.touchSocket.close();
            this.touchSocket = null;
        }
        if (this.imageWorker) {
            try {
                this.imageWorker.postMessage({ type: 'close' });
            } catch (e) { /* ignore */ }
            try { this.imageWorker.terminate(); } catch (e) { }
            this.imageWorker = null;
        }
        this.#setConnectionState('disconnected');
    }

    #handleImage(event){
           const dataType = typeof event.data;
           if (dataType === 'string') {
               console.log("image heart beat!", event.data);
           }else {
               this.#queueImage(event.data);
           }
    }

    #queueImage(data) {
        if (this.imageQueue.length > this.frameCount) {
            // Drop frames to reduce latency. If any queued bitmaps exist, close them.
            for (const item of this.imageQueue) {
                if (item && item.type === 'bitmap' && item.data && typeof item.data.close === 'function') {
                    try { item.data.close(); } catch (e) { /* ignore */ }
                }
            }
            this.imageQueue = [];
            console.log(" Drop frames to reduce latency");
        }
        this.imageQueue.push({ type: 'blob', data: new Blob([data], { type: "image/jpeg" }) });
    }

    #queueBitmap(bitmap) {
        if (this.imageQueue.length > this.frameCount) {
            for (const item of this.imageQueue) {
                if (item && item.type === 'bitmap' && item.data && typeof item.data.close === 'function') {
                    try { item.data.close(); } catch (e) { }
                }
            }
            this.imageQueue = [];
            console.log(" Drop frames to reduce latency");
        }
        this.imageQueue.push({ type: 'bitmap', data: bitmap });
    }

    #startDrawing() {
        if (!this.animationFrameId) {
            this._lastFrameTime = performance.now();
            this.animationFrameId = requestAnimationFrame(this.#rafLoop.bind(this));
        }
    }

    #stopDrawing() {
        if (this.animationFrameId) {
            cancelAnimationFrame(this.animationFrameId);
            this.animationFrameId = null;
        }
    }

    #rafLoop(timestamp) {
        const elapsed = timestamp - this._lastFrameTime;
        if (elapsed >= this.intervalValue) {
            this.#drawImage();
            // keep consistent timing
            this._lastFrameTime = timestamp - (elapsed % this.intervalValue);
        }
        this.animationFrameId = requestAnimationFrame(this.#rafLoop.bind(this));
    }

    async #drawImage() {
        const item = this.imageQueue.shift();
        if (!item) return false;

        try {
            let imageBitmap = null;
            if (item.type === 'bitmap' && item.data) {
                imageBitmap = item.data;
            } else if (item.type === 'blob' && item.data) {
                imageBitmap = await createImageBitmap(item.data);
            } else {
                return false;
            }

            this.streamCanvas.width = imageBitmap.width;
            this.streamCanvas.height = imageBitmap.height;
            this.canvasContext.drawImage(imageBitmap, 0, 0);
            if (typeof imageBitmap.close === 'function') imageBitmap.close();

            // FPS counting
            const now = performance.now();
            this._fpsFrameCounter = (this._fpsFrameCounter || 0) + 1;
            if (!this._fpsLastTime) this._fpsLastTime = now;
            if (now - this._fpsLastTime >= 1000) {
                this._fps = this._fpsFrameCounter;
                this._fpsFrameCounter = 0;
                this._fpsLastTime = now;
            }

            if (this.showFps) {
                this.canvasContext.save();
                this.canvasContext.fillStyle = 'lime';
                this.canvasContext.font = '16px sans-serif';
                this.canvasContext.fillText(`${this._fps} fps`, 10, 20);
                this.canvasContext.restore();
            }
            return true;
        } catch (e) {
            console.error("Failed to draw image:", e);
            return false;
        }
    }

    #sendKey(key) {
        this.#sendMessageToTouchSocket(`K,${key},0`);
    }
    
    #sendTouchEvent(type, x, y) {
        this.#sendMessageToTouchSocket(`${type},${x},${y}`);
    }

    #sendMessageToTouchSocket(message) {
        this.touchSocket?.send(message);
    }
}
