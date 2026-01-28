// Worker: opens WebSocket (prefer `WebSocketClient`) to receive image frames,
// decodes to ImageBitmap when possible, and posts results back to the main thread.
// Main thread can instruct worker with messages: { type: 'init', url: 'ws://...' } and { type: 'close' }.

let socket = null;
let supportsImageBitmap = typeof createImageBitmap === 'function';

// Try to import the local socket helper so worker can use `WebSocketClient`.
try {
    importScripts('/js/socket.js');
    console.log('image-worker: imported /js/socket.js in worker');
} catch (e) {
    console.log('image-worker: could not import /js/socket.js in worker, will fallback to native WebSocket', e);
}

async function handleFrameBuffer(buffer) {
    const size = buffer && buffer.byteLength ? buffer.byteLength : (buffer && buffer.length) || 0;
    const t0 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
    try {
        if (supportsImageBitmap) {
            const blob = new Blob([buffer], { type: 'image/jpeg' });
            const bitmap = await createImageBitmap(blob);
            const t1 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
            try {
                self.postMessage({ type: 'bitmap', bitmap }, [bitmap]);
            } catch (err) {
                console.log('image-worker: transfer ImageBitmap failed, sending raw frame', err);
                self.postMessage({ type: 'frame', buffer }, [buffer]);
            }
            return;
        }
        // Fallback: send raw buffer back to main thread (transferred)
        self.postMessage({ type: 'frame', buffer }, [buffer]);
    } catch (err) {
        const t1 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
        self.postMessage({ type: 'error', error: err && err.message ? err.message : String(err), decodeTime: (t1 - t0) });
    }
}

function initSocket(url) {
    try {
        // Prefer `WebSocketClient` (from /js/socket.js via importScripts) if available in worker scope
        if (typeof WebSocketClient !== 'undefined') {
            console.log('image-worker: using WebSocketClient from socket.js');
            socket = new WebSocketClient({
                url,
                name: 'imageWorkerSocket',
                binaryType: 'arraybuffer',
                onOpen: (ev) => { self.postMessage({ type: 'open' }); console.log('image-worker: wsclient opened', ev); },
                onClose: (ev) => { self.postMessage({ type: 'close' }); console.log('image-worker: wsclient closed', ev); },
                onError: (err) => { self.postMessage({ type: 'error', error: 'websocket error (wsclient)'}); console.log('image-worker: wsclient error', err); },
                onMessage: async (data, ev) => {
                    try {
                        if (typeof data === 'string') {
                            self.postMessage({ type: 'text', data });
                            console.log('image heart beat!', data);
                        } else if (data instanceof ArrayBuffer) {
                            await handleFrameBuffer(data);
                        } else if (data && data.buffer) {
                            await handleFrameBuffer(data.buffer);
                        }
                    } catch (err) {
                        self.postMessage({ type: 'error', error: err && err.message ? err.message : String(err) });
                    }
                }
            });
            return;
        }

        // Fallback to native WebSocket
        socket = new WebSocket(url);
        socket.binaryType = 'arraybuffer';
        console.log('image-worker: using native WebSocket in worker');

        socket.onopen = (ev) => { self.postMessage({ type: 'open' }); };
        socket.onclose = (ev) => { self.postMessage({ type: 'close' }); };
        socket.onerror = (ev) => { self.postMessage({ type: 'error', error: 'websocket error (native)'}); };
        socket.onmessage = async (ev) => {
            try {
                if (typeof ev.data === 'string') {
                    self.postMessage({ type: 'text', data: ev.data });
                } else if (ev.data instanceof ArrayBuffer) {
                    const buffer = ev.data;
                    await handleFrameBuffer(buffer);
                } else if (ev.data && ev.data.buffer) {
                    const buffer = ev.data.buffer;
                    await handleFrameBuffer(buffer);
                }
            } catch (err) {
                self.postMessage({ type: 'error', error: err && err.message ? err.message : String(err) });
            }
        };
    } catch (err) {
        self.postMessage({ type: 'error', error: err && err.message ? err.message : String(err) });
    }
}

self.onmessage = (e) => {
    const msg = e.data;
    if (!msg || !msg.type) return;
    if (msg.type === 'init' && msg.url) {
        console.log('image-worker: worker init socket', msg.url);
        initSocket(msg.url);
    } else if (msg.type === 'frame' && msg.data) {
        // Allow main thread to send frames for decode (not used normally)
        handleFrameBuffer(msg.data);
    } else if (msg.type === 'close') {
        try {
            if (socket) { try { socket.close(); } catch (e) {} socket = null; }
        } catch (e) { }
        try { self.close(); } catch (e) { }
    }
};
