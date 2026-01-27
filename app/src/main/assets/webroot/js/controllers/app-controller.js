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
        if (this.imageSocket) return;
        const url = `ws://${window.location.host}/screen`;
        this.imageSocket = new WebsocketHeartbeatJs({ url, pingTimeout: 15000, pongTimeout: 15000, msgType: 'arraybuffer' });

        this.imageSocket.onopen = (e) => {
            console.log('Image WebSocket connection established.', e);
            if (this.touchSocket && this.touchSocket.readyState === WebSocket.OPEN) {
                this.#setConnectionState('connected');
            }
        };
        this.imageSocket.onmessage = (e) => {
            this.#handleImage(e);
        };
        this.imageSocket.onclose = (e) => {
            console.log('Image WebSocket closed:', e);
            this.#setConnectionState('disconnected');
        }
        this.imageSocket.onerror = (e) => {
            console.log('Image WebSocket error:', e);
            this.#setConnectionState('disconnected');
        };
    }

    #initTouchSocket() {
        if (this.touchSocket) return;
        const url = `ws://${window.location.hostname}:8081/touch`;
        this.touchSocket = new WebsocketHeartbeatJs({ url, pingTimeout: 15000, pongTimeout: 15000 });

        this.touchSocket.onopen = () => {
            console.log('Touch WebSocket connection established.');
            if (this.imageSocket && this.imageSocket.readyState === WebSocket.OPEN) {
                this.#setConnectionState('connected');
            }
        };
        this.touchSocket.onclose = () => this.#setConnectionState('disconnected');
        this.touchSocket.onerror = (e) => {
            console.log('Touch WebSocket error:', e);
            this.#setConnectionState('disconnected');
        };
        this.touchSocket.onmessage = (e)=> {
            console.log("Touch websocket: ", e.data);
        };
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
        this.#setConnectionState('disconnected');
    }

    #handleImage(event){
           const dataType = typeof event.data;
           if (dataType === 'string') {
               console.log("heart beat!", event.data);
           }else {
              this.#queueImage(event.data);
           }
    }

    #queueImage(data) {
        if (this.imageQueue.length > this.frameCount) {
            this.imageQueue = []; // Drop frames to reduce latency
            console.log(" Drop frames to reduce latency");
        }
        this.imageQueue.push(new Blob([data], { type: "image/jpeg" }));
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
        const blob = this.imageQueue.shift();
        if (!blob) return false;

        try {
            const imageBitmap = await createImageBitmap(blob);
            this.streamCanvas.width = imageBitmap.width;
            this.streamCanvas.height = imageBitmap.height;
            this.canvasContext.drawImage(imageBitmap, 0, 0);
            imageBitmap.close();

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
