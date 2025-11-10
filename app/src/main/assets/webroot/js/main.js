/**
 * @file main.js
 * @description Main application logic for screen mirroring.
 * Refactored into modular classes: AppController for main logic and TouchController for input handling.
 */

/**
 * Handles all touch and mouse interactions on a target element.
 * Calculates precise coordinates and dispatches events.
 */
class TouchController {
    constructor(targetElement, onEvent) {
        this.target = targetElement;
        this.onEvent = onEvent;
        this.isMouseDown = false;

        this.registerEvents();
    }

    registerEvents() {
        this.target.addEventListener('mousedown', this.#handleMouseDown.bind(this));
        this.target.addEventListener('mouseup', this.#handleMouseUp.bind(this));
        this.target.addEventListener('mousemove', this.#handleMouseMove.bind(this));
        this.target.addEventListener('touchstart', this.#handleTouchStart.bind(this));
        this.target.addEventListener('touchend', this.#handleTouchEnd.bind(this));
        this.target.addEventListener('touchmove', this.#handleTouchMove.bind(this));
    }

    destroy() {
        // In a more complex app, you'd remove listeners here.
    }

    #handleMouseDown(e) {
        this.isMouseDown = true;
        const pos = this.#getPosition(e.clientX, e.clientY);
        if (pos) this.onEvent('D', pos.x, pos.y); // D for Down
    }

    #handleMouseUp(e) {
        if (!this.isMouseDown) return;
        this.isMouseDown = false;
        const pos = this.#getPosition(e.clientX, e.clientY);
        if (pos) this.onEvent('U', pos.x, pos.y); // U for Up
    }

    #handleMouseMove(e) {
        if (!this.isMouseDown) return;
        const pos = this.#getPosition(e.clientX, e.clientY);
        if (pos) this.onEvent('M', pos.x, pos.y); // M for Move
    }

    #handleTouchStart(e) {
        e.preventDefault();
        const touch = e.changedTouches[0];
        const pos = this.#getPosition(touch.clientX, touch.clientY);
        if (pos) this.onEvent('D', pos.x, pos.y);
    }

    #handleTouchEnd(e) {
        e.preventDefault();
        const touch = e.changedTouches[0];
        const pos = this.#getPosition(touch.clientX, touch.clientY);
        if (pos) this.onEvent('U', pos.x, pos.y);
    }

    #handleTouchMove(e) {
        e.preventDefault();
        const touch = e.changedTouches[0];
        const pos = this.#getPosition(touch.clientX, touch.clientY);
        if (pos) this.onEvent('M', pos.x, pos.y);
    }

    #getPosition(clientX, clientY) {
        const rect = this.target.getBoundingClientRect();
        const imageWidth = this.target.width;
        const imageHeight = this.target.height;

        if (imageWidth === 0 || imageHeight === 0) return null;

        const clickX = clientX - rect.left;
        const clickY = clientY - rect.top;

        const displayWidth = rect.width;
        const displayHeight = rect.height;

        const imageAspect = imageWidth / imageHeight;
        const displayAspect = displayWidth / displayHeight;

        let scaledWidth, scaledHeight, offsetX, offsetY;

        if (displayAspect > imageAspect) {
            scaledHeight = displayHeight;
            scaledWidth = scaledHeight * imageAspect;
            offsetX = (displayWidth - scaledWidth) / 2;
            offsetY = 0;
        } else {
            scaledWidth = displayWidth;
            scaledHeight = scaledWidth / imageAspect;
            offsetX = 0;
            offsetY = (displayHeight - scaledHeight) / 2;
        }

        const xOnScaled = clickX - offsetX;
        const yOnScaled = clickY - offsetY;

        const finalX = Math.round((xOnScaled / scaledWidth) * imageWidth);
        const finalY = Math.round((yOnScaled / scaledHeight) * imageHeight);

        if (finalX < 0 || finalX > imageWidth || finalY < 0 || finalY > imageHeight) {
            return null;
        }
        return { x: finalX, y: finalY };
    }
}

/**
 * Main application controller.
 * Manages UI, WebSocket connections, and rendering.
 */
class AppController {
    constructor() {
        this.joinButton = document.getElementById("join");
        this.fullScreenButton = document.getElementById("fullscreen");
        this.homeButton = document.getElementById("home");
        this.backButton = document.getElementById("back");
        this.streamCanvas = document.getElementById("screen");

        this.canvasContext = this.streamCanvas.getContext("2d");
        this.imageSocket = null;
        this.touchSocket = null; // New socket for touch events
        this.touchController = null;
        this.imageQueue = [];
        this.drawInterval = null;

        this.#registerEvents();
        this.#startDrawing();
    }

    #registerEvents() {
        this.joinButton.addEventListener("click", this.#onJoinClick.bind(this));
        this.fullScreenButton.addEventListener("click", this.#onFullScreenClick.bind(this));
        this.homeButton.addEventListener("click", () => this.#sendKey("H"));
        this.backButton.addEventListener("click", () => this.#sendKey("B"));
        window.onbeforeunload = this.#destroy.bind(this);
    }

    #destroy() {
        this.#stopDrawing();
        this.#closeAllWebSockets();
    }

    #onJoinClick() {
        try {
            this.#closeAllWebSockets();
            this.#initImageSocket();
            this.#initTouchSocket(); // Initialize the new touch socket
            this.touchController = new TouchController(this.streamCanvas, this.#sendTouchEvent.bind(this));
            this.homeButton.style.visibility = "visible";
            this.backButton.style.visibility = "visible";
        } catch (e) {
            console.error("Failed to start mirroring:", e);
            alert(`Error starting connection: ${e.message}`);
        }
    }

    #onFullScreenClick() {
        if (this.streamCanvas.requestFullscreen) {
            this.streamCanvas.requestFullscreen();
        }
    }

    #initImageSocket() {
        if (this.imageSocket) return;
        const url = `ws://${window.location.host}/screen`;
        this.imageSocket = new WebsocketHeartbeatJs({ url, pingTimeout: 8000, pongTimeout: 8000, msgType: 'arraybuffer' });

        this.imageSocket.onopen = () => console.log('Image WebSocket connection established.');
        this.imageSocket.onmessage = (e) => this.#queueImage(e.data);
        this.imageSocket.onclose = () => console.log('Image WebSocket connection closed.');
        this.imageSocket.onerror = (e) => console.error('Image WebSocket error:', e);
    }

    #initTouchSocket() {
        if (this.touchSocket) return;
        const url = `ws://${window.location.hostname}:8081/touch`; // Use port 8081 for touch
        this.touchSocket = new WebsocketHeartbeatJs({ url, pingTimeout: 8000, pongTimeout: 8000 });

        this.touchSocket.onopen = () => console.log('Touch WebSocket connection established.');
        this.touchSocket.onclose = () => console.log('Touch WebSocket connection closed.');
        this.touchSocket.onerror = (e) => console.error('Touch WebSocket error:', e);
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
    }

    #queueImage(data) {
        if (this.imageQueue.length > 5) {
            this.imageQueue = []; // Drop frames to reduce latency
        }
        this.imageQueue.push(new Blob([data], { type: "image/jpeg" }));
    }

    #startDrawing() {
        this.drawInterval = setInterval(() => this.#drawImage(), 32); // ~30fps
    }

    #stopDrawing() {
        clearInterval(this.drawInterval);
        this.drawInterval = null;
    }

    async #drawImage() {
        const blob = this.imageQueue.shift();
        if (!blob) return;

        try {
            const imageBitmap = await createImageBitmap(blob);
            this.streamCanvas.width = imageBitmap.width;
            this.streamCanvas.height = imageBitmap.height;
            this.canvasContext.drawImage(imageBitmap, 0, 0);
            imageBitmap.close();
        } catch (e) {
            console.error("Failed to draw image:", e);
        }
    }

    #sendKey(key) {
        this.#sendMessageToTouchSocket(`K,${key},0`);
    }
    
    #sendTouchEvent(type, x, y) {
        this.#sendMessageToTouchSocket(`${type},${x},${y}`);
    }

    #sendMessageToTouchSocket(message) {
        if (this.touchSocket && this.touchSocket.readyState === WebSocket.OPEN) {
            this.touchSocket.send(message);
        }
    }
}

// Initialize the application when the DOM is fully loaded.
window.addEventListener('DOMContentLoaded', () => {
    new AppController();
});
