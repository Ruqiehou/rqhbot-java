package com.rqhbot.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.glassfish.tyrus.client.ClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rqhbot.sdk.adapter.AdapterResolver;
import com.rqhbot.sdk.interfaces.IClient;
import com.rqhbot.sdk.interfaces.IConnectionEventListener;
import com.rqhbot.sdk.interfaces.MessageHandler;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;

/**
 * OneBot 11 WebSocket 客户端（NapCat / SnowLuma 通用）
 */
public class NapCatClient extends Endpoint implements IClient {
    
    private static final Logger logger = LoggerFactory.getLogger(NapCatClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // 重连配置
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final int RECONNECT_DELAY = 3000; // 毫秒

    // API 调用超时（秒）
    private static final long API_TIMEOUT_SECONDS = 30;
    
    private final String wsUrl;
    private final String accessToken;
    private final String qqId;
    private Session session;
    private volatile boolean connected = false;
    
    // 消息处理器映射表
    private final Map<String, List<MessageHandler>> messageHandlers = new ConcurrentHashMap<>();
    
    // Echo 映射 - 用于 API 调用响应
    private final Map<String, CompletableFuture<Map<String, Object>>> echoMap = new ConcurrentHashMap<>();
    
    // 消息队列
    private BlockingQueue<String> msgQueue;
    private ExecutorService processingExecutor;
    private volatile boolean processing = false;
    
    // 性能统计
    private final AtomicLong totalMessages = new AtomicLong(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    private volatile double avgLatency = 0.0;
    
    // 重连控制
    private int reconnectAttempts = 0;
    private final ScheduledExecutorService reconnectScheduler = Executors.newScheduledThreadPool(1);
    private volatile CompletableFuture<Boolean> pendingConnectFuture;
    private volatile boolean manualDisconnect = false;
    
    // 连接事件监听器
    private final List<IConnectionEventListener> connectionListeners = new CopyOnWriteArrayList<>();
    
    public NapCatClient() {
        this(null, null, null);
    }
    
    public NapCatClient(String wsUrl, String accessToken) {
        this(wsUrl, accessToken, null);
    }
    
    public NapCatClient(String wsUrl, String accessToken, String qqId) {
        // 未显式传入的参数按 BACKEND 从 .env / 环境变量解析
        if (wsUrl == null || accessToken == null || qqId == null) {
            AdapterResolver defaults = AdapterResolver.fromEnvironment();
            if (wsUrl == null) {
                wsUrl = defaults.wsUrl();
            }
            if (accessToken == null) {
                accessToken = defaults.accessToken();
            }
            if (qqId == null) {
                qqId = defaults.qqId();
            }
        }
        this.wsUrl = wsUrl;
        this.accessToken = accessToken;
        this.qqId = qqId;
        this.msgQueue = new LinkedBlockingQueue<>(1000);
        this.processingExecutor = Executors.newFixedThreadPool(4);
    }
    
    @Override
    public boolean isConnected() {
        return connected && session != null && session.isOpen();
    }
    
    @Override
    public CompletableFuture<Boolean> connect(Integer maxRetries) {
        if (isConnected()) {
            return CompletableFuture.completedFuture(true);
        }
        
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingConnectFuture = future;
        manualDisconnect = false;
        int retries = maxRetries != null ? maxRetries : MAX_RECONNECT_ATTEMPTS;
        
        connectInternal(future, retries, 0);
        return future;
    }
    
    private void connectInternal(CompletableFuture<Boolean> future, int maxRetries, int attempt) {
        if (attempt >= maxRetries) {
            logger.error("连接失败，已达到最大重试次数 ({})", maxRetries);
            notifyReconnectFailed(new Exception("连接失败，已达到最大重试次数"));
            future.complete(false);
            return;
        }
        
        try {
            if (attempt > 0) {
                reconnectAttempts = attempt;
                logger.info("尝试重连 ({}/{})...", attempt, maxRetries);
                notifyReconnecting(attempt).join();
            }
            
            // 构建 WebSocket URL
            URI uri = new URI(wsUrl);
            
            // 创建 WebSocket 客户端管理器
            ClientManager client = ClientManager.createClient();
            ClientEndpointConfig config = buildEndpointConfig();
            
            // 同步连接，在 onOpen 中完成 future
            client.connectToServer(this, config, uri);
            
        } catch (URISyntaxException e) {
            logger.error("WebSocket URL 格式错误：{}", e.getMessage());
            future.completeExceptionally(e);
        } catch (IOException e) {
            logger.error("IO 错误导致连接失败 ({}): {}", attempt + 1, e.getMessage());
            
            if (attempt < maxRetries - 1) {
                int delay = RECONNECT_DELAY * (attempt + 1);
                logger.info("{}秒后重试...", delay / 1000);
                
                reconnectScheduler.schedule(() -> 
                    connectInternal(future, maxRetries, attempt + 1), 
                    delay, TimeUnit.MILLISECONDS
                );
            } else {
                future.completeExceptionally(e);
            }
        } catch (RuntimeException e) {
            // 捕获运行时异常，包括 Tyrus 的 DeploymentException
            logger.error("运行时错误导致连接失败 ({}): {}", attempt + 1, e.getMessage());
            future.completeExceptionally(e);
        } catch (Exception e) {
            // 捕获其他所有检查异常（如 Tyrus 的 DeploymentException）
            // 使用 Exception 而非具体类型以保持兼容性
            logger.error("未知错误导致连接失败 ({}): {}", attempt + 1, e.getMessage());
            future.completeExceptionally(e);
        }
    }
    
    private ClientEndpointConfig buildEndpointConfig() {
        ClientEndpointConfig.Builder builder = ClientEndpointConfig.Builder.create();
        Map<String, List<String>> headers = new HashMap<>();
        if (accessToken != null && !accessToken.isBlank()) {
            headers.put("Authorization", List.of("Bearer " + accessToken));
        }
        if (qqId != null && !qqId.isBlank()) {
            headers.put("X-Self-ID", List.of(qqId));
        }
        if (!headers.isEmpty()) {
            builder.configurator(new ClientEndpointConfig.Configurator() {
                @Override
                public void beforeRequest(Map<String, List<String>> requestHeaders) {
                    requestHeaders.putAll(headers);
                }
            });
        }
        return builder.build();
    }
    
    @Override
    public void onOpen(Session session, EndpointConfig endpointConfig) {
        session.addMessageHandler(String.class, (jakarta.websocket.MessageHandler.Whole<String>) this::onMessage);
        this.session = session;
        this.connected = true;
        this.reconnectAttempts = 0;
        
        startMessageProcessing();
        
        logger.info("成功连接到 WebSocket 服务器：{}", wsUrl);
        if (pendingConnectFuture != null && !pendingConnectFuture.isDone()) {
            pendingConnectFuture.complete(true);
        }
        notifyConnected().join();
    }
    
    public void onMessage(String message) {
        try {
            if (!msgQueue.offer(message, 100, TimeUnit.MILLISECONDS)) {
                logger.warn("消息队列已满，丢弃消息");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("消息入队被中断");
        }
    }
    
    @Override
    public void onClose(Session session, CloseReason reason) {
        logger.info("WebSocket 连接已关闭：code={} reason={}", reason.getCloseCode(), reason.getReasonPhrase());
        connected = false;
        session = null;
        
        if (pendingConnectFuture != null && !pendingConnectFuture.isDone()) {
            pendingConnectFuture.complete(false);
        }
        
        stopMessageProcessing();
        notifyDisconnected().join();
        
        // 尝试重连
        if (!manualDisconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            scheduleReconnect();
        }
    }
    
    @Override
    public void onError(Session session, Throwable throwable) {
        logger.error("WebSocket 错误：{}", throwable.getMessage(), throwable);
        connected = false;
        if (pendingConnectFuture != null && !pendingConnectFuture.isDone()) {
            pendingConnectFuture.completeExceptionally(throwable);
        }
    }
    
    private void startMessageProcessing() {
        if (processing) return;
        
        if (processingExecutor == null || processingExecutor.isShutdown() || processingExecutor.isTerminated()) {
            processingExecutor = Executors.newFixedThreadPool(4);
        }
        
        processing = true;
        processingExecutor.submit(this::processMessages);
    }
    
    private void stopMessageProcessing() {
        processing = false;
        if (processingExecutor != null) {
            processingExecutor.shutdownNow();
            try {
                if (!processingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    processingExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private void processMessages() {
        while (processing && connected) {
            try {
                String message = msgQueue.poll(1, TimeUnit.SECONDS);
                if (message == null) continue;
                
                long startTime = System.nanoTime();
                
                try {
                    handleRawMessage(message);
                } catch (Exception e) {
                    logger.error("处理消息时发生错误：{}", e.getMessage(), e);
                } finally {
                    long endTime = System.nanoTime();
                    long processingTime = endTime - startTime;
                    
                    totalMessages.incrementAndGet();
                    totalProcessingTime.addAndGet(processingTime);
                    avgLatency = totalProcessingTime.doubleValue() / totalMessages.doubleValue();
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("处理循环错误：{}", e.getMessage(), e);
            }
        }
    }
    
    private void handleRawMessage(String message) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = objectMapper.readValue(message, Map.class);
        
        // 检查 echo 字段（API 响应）
        if (data.containsKey("echo")) {
            Object echoValue = data.get("echo");
            if (echoValue != null) {
                String echoId = String.valueOf(echoValue);
                CompletableFuture<Map<String, Object>> future = echoMap.remove(echoId);
                if (future != null && !future.isDone()) {
                    future.complete(data);
                }
            }
            return;
        }
        
        // 处理 NapCat 消息格式
        String postType = (String) data.getOrDefault("post_type", "");
        String messageType = (String) data.getOrDefault("message_type", "");
        
        // 收集需要处理的事件类型
        Set<String> eventTypes = new HashSet<>();
        if (!postType.isEmpty()) eventTypes.add(postType);
        if (!messageType.isEmpty()) eventTypes.add(messageType);
        
        // 遍历事件类型，分发到处理器
        // 使用统一有界线程池，避免阻塞 ForkJoinPool.commonPool（处理器内部含阻塞 IO）
        for (String eventType : eventTypes) {
            List<MessageHandler> handlers = messageHandlers.get(eventType);
            if (handlers == null || handlers.isEmpty()) continue;

            for (MessageHandler handler : handlers) {
                BotExecutor.execute(() -> {
                    try {
                        handler.handle(data);
                    } catch (Exception e) {
                        logger.error("消息处理器执行出错：{}", e.getMessage());
                    }
                });
            }
        }
    }
    
    @Override
    public void onMessage(String messageType, MessageHandler handler) {
        messageHandlers.computeIfAbsent(messageType, k -> new CopyOnWriteArrayList<>()).add(handler);
    }
    
    @Override
    public CompletableFuture<Map<String, Object>> callApi(
            String action, 
            Map<String, Object> params,
            int maxRetries,
            long retryDelay
    ) {
        int maxAttempts = Math.max(1, maxRetries);
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        callApiAttempt(action, params, maxAttempts, Math.max(0, retryDelay), 1, result);
        return result;
    }

    /**
     * 单次 API 调用，失败时按 {@code retryDelay} 延迟重试，直到达到最大尝试次数。
     */
    private void callApiAttempt(
            String action,
            Map<String, Object> params,
            int maxAttempts,
            long retryDelay,
            int attempt,
            CompletableFuture<Map<String, Object>> result
    ) {
        callOnce(action, params).whenComplete((response, error) -> {
            if (error == null) {
                result.complete(response);
                return;
            }
            if (attempt >= maxAttempts) {
                logger.warn("API {} 调用失败，已尝试 {} 次：{}", action, attempt, error.getMessage());
                result.completeExceptionally(error);
                return;
            }
            logger.debug("API {} 第 {} 次调用失败，{}ms 后重试：{}", action, attempt, retryDelay, error.getMessage());
            try {
                reconnectScheduler.schedule(
                        () -> callApiAttempt(action, params, maxAttempts, retryDelay, attempt + 1, result),
                        Math.max(1, retryDelay), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                result.completeExceptionally(error);
            }
        });
    }

    /**
     * 发送一次 API 请求并等待 echo 响应（含超时）。
     */
    private CompletableFuture<Map<String, Object>> callOnce(String action, Map<String, Object> params) {
        if (!isConnected() || session == null) {
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            future.completeExceptionally(new ConnectionException("未连接到 NapCat 服务器"));
            return future;
        }

        String echoId = UUID.randomUUID().toString();
        Map<String, Object> message = new HashMap<>();
        message.put("action", action);
        message.put("params", params != null ? params : new HashMap<>());
        message.put("echo", echoId);

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        echoMap.put(echoId, future);

        // 发送消息
        try {
            session.getBasicRemote().sendText(objectMapper.writeValueAsString(message));
        } catch (IOException e) {
            echoMap.remove(echoId);
            future.completeExceptionally(e);
            return future;
        }

        // 超时处理（使用调度线程，避免占用 commonPool）
        try {
            reconnectScheduler.schedule(() -> {
                CompletableFuture<Map<String, Object>> pending = echoMap.remove(echoId);
                if (pending != null && !pending.isDone()) {
                    pending.completeExceptionally(new TimeoutException("API 调用超时"));
                }
            }, API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            echoMap.remove(echoId);
            future.completeExceptionally(e);
        }

        return future;
    }
    
    @Override
    public CompletableFuture<Map<String, Object>> sendPrivateMessage(long userId, String message) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        params.put("message", message);
        return callApi("send_private_msg", params, 3, 1000);
    }
    
    @Override
    public CompletableFuture<Map<String, Object>> sendGroupMessage(long groupId, String message) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("message", message);
        return callApi("send_group_msg", params, 3, 1000);
    }
    
    @Override
    public CompletableFuture<Void> disconnect(boolean force) {
        return BotExecutor.async(() -> {
            if (!connected && !force) return;
            
            manualDisconnect = true;
            connected = false;
            stopMessageProcessing();
            
            if (session != null) {
                try {
                    session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "User requested close"));
                } catch (IOException e) {
                    logger.error("关闭 WebSocket 连接时出错：{}", e.getMessage());
                } finally {
                    session = null;
                }
            }
            
            logger.info("WebSocket 连接已关闭");
            notifyDisconnected().join();
        });
    }
    
    private void scheduleReconnect() {
        int delay = RECONNECT_DELAY * (reconnectAttempts + 1);
        logger.info("计划 {}秒后重连...", delay / 1000);
        
        reconnectScheduler.schedule(() -> {
            connectInternal(new CompletableFuture<>(), MAX_RECONNECT_ATTEMPTS, reconnectAttempts + 1);
        }, delay, TimeUnit.MILLISECONDS);
    }
    
    @Override
    public void addConnectionListener(IConnectionEventListener listener) {
        if (!connectionListeners.contains(listener)) {
            connectionListeners.add(listener);
        }
    }
    
    @Override
    public void removeConnectionListener(IConnectionEventListener listener) {
        connectionListeners.remove(listener);
    }
    
    private CompletableFuture<Void> notifyConnected() {
        return CompletableFuture.allOf(
            connectionListeners.stream()
                .map(listener -> runListenerTask(() -> listener.onConnected()))
                .toArray(CompletableFuture[]::new)
        );
    }
    
    private CompletableFuture<Void> notifyDisconnected() {
        return CompletableFuture.allOf(
            connectionListeners.stream()
                .map(listener -> runListenerTask(() -> listener.onDisconnected()))
                .toArray(CompletableFuture[]::new)
        );
    }
    
    private CompletableFuture<Void> notifyReconnecting(int attempt) {
        return CompletableFuture.allOf(
            connectionListeners.stream()
                .map(listener -> runListenerTask(() -> listener.onReconnecting(attempt)))
                .toArray(CompletableFuture[]::new)
        );
    }
    
    private CompletableFuture<Void> notifyReconnectFailed(Exception error) {
        return CompletableFuture.allOf(
            connectionListeners.stream()
                .map(listener -> runListenerTask(() -> listener.onReconnectFailed(error)))
                .toArray(CompletableFuture[]::new)
        );
    }
    
    private CompletableFuture<Void> runListenerTask(CompletableFutureSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            logger.error("连接监听器执行失败：{}", e.getMessage());
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }
    
    @FunctionalInterface
    private interface CompletableFutureSupplier {
        CompletableFuture<Void> get() throws Exception;
    }
    
    @Override
    public Map<String, Object> getPerformanceStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_messages", totalMessages.get());
        stats.put("avg_latency_ns", avgLatency);
        stats.put("avg_latency_ms", avgLatency / 1_000_000.0);
        stats.put("reconnect_attempts", reconnectAttempts);
        stats.put("is_connected", connected);
        return stats;
    }
    
    /**
     * 自定义异常类
     */
    public static class ConnectionException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        
        public ConnectionException(String message) {
            super(message);
        }
    }
}
