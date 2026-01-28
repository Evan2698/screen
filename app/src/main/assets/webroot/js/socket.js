/**
 * WebSocket 封装类
 * 功能：心跳检测、超时管理、自动重连
 */
class WebSocketClient {
    /**
     * 构造函数
     * @param {Object} options 配置选项
     * @param {string} options.url WebSocket 服务器地址
     * @param {number} options.reconnectInterval 重连间隔(ms)，默认 3000
     * @param {number} options.maxReconnectAttempts 最大重连次数，默认无限重连
     * @param {number} options.heartbeatInterval 心跳间隔(ms)，默认 15000
     * @param {number} options.heartbeatTimeout 心跳超时时间(ms)，默认 10000
     * @param {Function} options.onOpen 连接成功回调
     * @param {Function} options.onMessage 消息接收回调
     * @param {Function} options.onClose 连接关闭回调
     * @param {Function} options.onError 错误回调
     * @param {Function} options.onReconnect 重连回调
     * @param {string} [options.name] 对象名称（用于调试）
     */
    constructor(options = {}) {
        // 配置参数
        this.url = options.url || '';
        this.binaryType = options.binaryType || 'arraybuffer';
        this.reconnectInterval = options.reconnectInterval || 3000;
        this.maxReconnectAttempts = options.maxReconnectAttempts || 0; // 0 表示无限重连
        this.alwaysRetry = !!options.alwaysRetry;
        this.heartbeatInterval = options.heartbeatInterval || 15000;
        this.heartbeatTimeout = options.heartbeatTimeout || 10000;
        this.name = options.name || '___';

        // 事件回调
        this.onOpen = options.onOpen || (() => { });
        this.onMessage = options.onMessage || (() => { });
        this.onClose = options.onClose || (() => { });
        this.onError = options.onError || (() => { });
        this.onReconnect = options.onReconnect || (() => { });

        // 内部状态
        this.ws = null;
        this.reconnectAttempts = 0;
        this.reconnectTimer = null;
        this.heartbeatTimer = null;
        this.heartbeatTimeoutTimer = null;
        this.isConnected = false;
        this.isManualClose = false; // 是否手动关闭
        this.messageQueue = []; // 消息队列（用于重连时缓存消息）
        this.pendingMessages = new Map(); // 等待确认的消息

        // 绑定方法
        this.connect = this.connect.bind(this);
        this.send = this.send.bind(this);
        this.close = this.close.bind(this);
        this.reconnect = this.reconnect.bind(this);

        // 自动连接
        if (this.url) {
            this.connect();
        }
    }

    /**
     * 建立 WebSocket 连接
     */
    connect() {
        if (this.isConnected) {
            return;
        }

        // Allow reconnect if previous socket is closed or closing; only bail out
        // when an active socket exists.
        if (this.ws && typeof this.ws.readyState === 'number') {
            const rs = this.ws.readyState;
            if (rs !== WebSocket.CLOSED && rs !== WebSocket.CLOSING) {
                return; // socket still active
            }
        }

        console.log(`[WebSocket] Connecting: ${this.url}`);

        try {
            if (this.protocols) {
                this.ws = new WebSocket(this.url, this.protocols);
            } else {
                this.ws = new WebSocket(this.url);
            }
            try { this.ws.binaryType = this.binaryType; } catch (e) { /* ignore */ }
            this.setupEventListeners();
        } catch (error) {
            console.error('[WebSocket] Connection creation failed:', error);
            this.handleError(error);
            this.scheduleReconnect();
        }
    }

    /**
     * 设置事件监听器
     */
    setupEventListeners() {
        this.ws.onopen = (event) => {
            console.log('[WebSocket] Connected');
            this.isConnected = true;
            this.isManualClose = false;
            this.reconnectAttempts = 0;

            // 启动心跳检测
            this.startHeartbeat();

            // 发送队列中的消息
            this.flushMessageQueue();

            // 调用用户回调
            this.onOpen(event);
        };

        this.ws.onmessage = (event) => {
            try {
                // 处理心跳响应
                if (this.isHeartbeatMessage(event.data)) {
                    this.handleHeartbeatResponse();
                    return;
                }

                // 调用用户回调
                this.onMessage(event.data, event);

                // 处理消息确认（如果有消息确认机制）
                this.handleMessageAck(event.data);
            } catch (error) {
                console.error('[WebSocket] Message handling error:', error);
            }
        };

        this.ws.onclose = (event) => {
            console.log(`[WebSocket] Connection closed, code: ${event.code}, reason: ${event.reason}`);
            this.isConnected = false;
            this.cleanup();

            // Clear reference to underlying ws so connect() can create a new one
            try { this.ws = null; } catch (e) { /* ignore */ }

            // 调用用户回调
            this.onClose(event);

            // 如果不是手动关闭，则尝试重连
            if (!this.isManualClose) {
                this.scheduleReconnect();
            }
        };

        this.ws.onerror = (error) => {
            console.error('[WebSocket] Connection error:', error);
            this.handleError(error);
            // If connection is not established, schedule a reconnect.
            if (!this.isManualClose && !this.isConnected) {
                this.scheduleReconnect();
            }
        };
    }

    /**
     * 发送消息
     * @param {string|Object} data 要发送的数据
     * @param {Object} options 发送选项
     * @param {boolean} options.retryOnFail 发送失败时是否重试，默认 true
     * @param {number} options.retryCount 最大重试次数，默认 3
     * @param {boolean} options.requireAck 是否需要确认，默认 false
     * @param {number} options.ackTimeout 确认超时时间(ms)，默认 5000
     */
    send(data, options = {}) {
        const {
            retryOnFail = true,
            retryCount = 3,
            requireAck = false,
            ackTimeout = 5000
        } = options;

        const message = typeof data === 'object' ? JSON.stringify(data) : data;
        const messageId = requireAck ? this.generateMessageId() : null;


        const sendFunc = (retryLeft = retryCount) => {
            if (this.isConnected && this.ws && this.ws.readyState === WebSocket.OPEN) {
                try {
                    // 如果需要确认，先存储消息
                    if (requireAck && messageId) {
                        this.pendingMessages.set(messageId, {
                            data: message,
                            retriesLeft: retryLeft,
                            timestamp: Date.now(),
                            timeout: ackTimeout
                        });

                        // 设置确认超时
                        setTimeout(() => {
                            if (this.pendingMessages.has(messageId)) {
                                console.warn(`[WebSocket] Message ${messageId} ack timeout`);
                                this.pendingMessages.delete(messageId);

                                // 自动重试
                                if (retryLeft > 0) {
                                    console.log(`[WebSocket] Retrying message ${messageId}, retries left: ${retryLeft}`);
                                    sendFunc(retryLeft - 1);
                                }
                            }
                        }, ackTimeout);
                    }

                    this.ws.send(message);
                    //console.log(`[WebSocket] Message sent:`, data);
                } catch (error) {
                    console.error('[WebSocket] Failed to send message:', error);

                    if (retryOnFail && retryLeft > 0) {
                        console.log(`[WebSocket] Resending message, retries left: ${retryLeft}`);
                        setTimeout(() => sendFunc(retryLeft - 1), 1000);
                    } else {
                        this.queueMessage(data, options);
                    }
                }
            } else {
                console.warn('[WebSocket] Connection not ready, message queued');
                this.queueMessage(data, options);

                if (retryOnFail && !this.isConnected) {
                    this.connect();
                }
            }
        };

        sendFunc();

        return messageId; // 返回消息ID用于确认
    }

    /**
     * 确认消息接收
     * @param {string} messageId 消息ID
     */
    acknowledgeMessage(messageId) {
        if (this.pendingMessages.has(messageId)) {
            this.pendingMessages.delete(messageId);
            console.log(`[WebSocket] Message ${messageId} acknowledged`);
        }
    }

    /**
     * 处理消息确认
     * @param {string} data 接收到的数据
     */
    handleMessageAck(data) {
        try {
            const message = JSON.parse(data);
            if (message.type === 'ack' && message.messageId) {
                this.acknowledgeMessage(message.messageId);
            }
        } catch {
            // 不是JSON格式或不是确认消息，忽略
        }
    }

    /**
     * 队列消息（当连接断开时）
     */
    queueMessage(data, options) {
        this.messageQueue.push({ data, options });

        // 限制队列大小（防止内存泄漏）
        const MAX_QUEUE_SIZE = 100;
        if (this.messageQueue.length > MAX_QUEUE_SIZE) {
            console.warn(`[WebSocket] Message queue exceeded ${MAX_QUEUE_SIZE}, dropping oldest message`);
            this.messageQueue.shift();
        }
    }

    /**
     * 发送队列中的消息
     */
    flushMessageQueue() {
        while (this.messageQueue.length > 0) {
            const { data, options } = this.messageQueue.shift();
            this.send(data, options);
        }
    }

    /**
     * 关闭连接
     */
    close() {
        console.log('[WebSocket] Manual close');
        this.isManualClose = true;
        this.cleanup();

        if (this.ws) {
            this.ws.close(1000, 'Manual closure');
        }
    }

    /**
     * 清理资源
     */
    cleanup() {
        this.stopHeartbeat();

        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
    }

    /**
     * 启动心跳检测
     */
    startHeartbeat() {
        this.stopHeartbeat();
        // 发送心跳（纯文本 'heartbeat'）
        this.heartbeatTimer = setInterval(() => {
            if (this.isConnected && this.ws && this.ws.readyState === WebSocket.OPEN) {
                const heartbeatMsg = 'heartbeat';
                try {
                    this.ws.send(heartbeatMsg);
                    // Log which underlying WebSocket implementation is being used and the client name
                    const wsType = this.ws && this.ws.constructor && this.ws.constructor.name ? this.ws.constructor.name : typeof this.ws;
                    console.log(`[WebSocket] Sent heartbeat - ws: ${wsType}, client: ${this.name}`);

                    // 设置心跳响应超时
                    this.heartbeatTimeoutTimer = setTimeout(() => {
                        console.warn('[WebSocket] Heartbeat response timeout, connection may be closed');
                        this.ws.close();
                    }, this.heartbeatTimeout);
                } catch (error) {
                    console.error('[WebSocket] Failed to send heartbeat:', error);
                }
            }
        }, this.heartbeatInterval);
    }

    /**
     * 停止心跳检测
     */
    stopHeartbeat() {
        if (this.heartbeatTimer) {
            clearInterval(this.heartbeatTimer);
            this.heartbeatTimer = null;
        }

        if (this.heartbeatTimeoutTimer) {
            clearTimeout(this.heartbeatTimeoutTimer);
            this.heartbeatTimeoutTimer = null;
        }
    }

    /**
     * 处理心跳响应
     */
    handleHeartbeatResponse() {
        console.log('[WebSocket] Received heartbeat response');

        if (this.heartbeatTimeoutTimer) {
            clearTimeout(this.heartbeatTimeoutTimer);
            this.heartbeatTimeoutTimer = null;
        }
    }

    /**
     * 判断是否为心跳消息
     */
    isHeartbeatMessage(data) {
        // Expect plain text 'heartbeat' or 'heartbeat_ack'
        try {
            return data === 'heartbeat' || data === 'heartbeat_ack';
        } catch {
            return false;
        }
    }

    /**
     * 安排重连
     */
    scheduleReconnect() {
        if (this.isManualClose) {
            return;
        }

        if (!this.alwaysRetry && this.maxReconnectAttempts > 0 && this.reconnectAttempts >= this.maxReconnectAttempts) {
            console.error(`[WebSocket] Reached max reconnect attempts (${this.maxReconnectAttempts}), stopping reconnects`);
            return;
        }

        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
        }

        this.reconnectAttempts++;
        const delay = this.calculateReconnectDelay();

        console.log(`[WebSocket] Attempting reconnect #${this.reconnectAttempts} in ${delay}ms`);

        this.reconnectTimer = setTimeout(() => {
            this.reconnect();
        }, delay);
    }

    /**
     * 执行重连
     */
    reconnect() {
        console.log(`[WebSocket] Trying to reconnect (${this.reconnectAttempts})`);
        this.onReconnect(this.reconnectAttempts);
        this.connect();
    }

    /**
     * 计算重连延迟（指数退避算法）
     */
    calculateReconnectDelay() {
        const baseDelay = this.reconnectInterval;
        const maxDelay = 30000; // 最大延迟 30 秒
        const delay = Math.min(baseDelay * Math.pow(1.5, this.reconnectAttempts - 1), maxDelay);

        // 添加随机抖动避免多个客户端同时重连
        const jitter = delay * 0.1 * (Math.random() * 2 - 1);
        return delay + jitter;
    }

    /**
     * 处理错误
     */
    handleError(error) {
        this.onError(error);
    }



    /**
     * 获取连接状态
     */
    getStatus() {
        return {
            isConnected: this.isConnected,
            reconnectAttempts: this.reconnectAttempts,
            pendingMessages: this.pendingMessages.size,
            queuedMessages: this.messageQueue.length,
            url: this.url
        };
    }
}

// Expose on globalThis for importScripts compatibility in workers
try {
    if (typeof globalThis !== 'undefined') globalThis.WebSocketClient = WebSocketClient;
} catch (e) {
    if (typeof window !== 'undefined') window.WebSocketClient = WebSocketClient;
}

// CommonJS export (for bundlers/tests). Avoid top-level ES Module `export` so this file
// remains usable via `importScripts` in workers and as a plain script tag.
if (typeof module !== 'undefined' && typeof module.exports !== 'undefined') {
    module.exports = WebSocketClient;
}