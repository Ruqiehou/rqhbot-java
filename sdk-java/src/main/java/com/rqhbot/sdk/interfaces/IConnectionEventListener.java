package com.rqhbot.sdk.interfaces;

import java.util.concurrent.CompletableFuture;

/**
 * 连接事件监听器接口 - 监听连接状态变化
 */
public interface IConnectionEventListener {
    
    /**
     * 连接建立时调用
     */
    CompletableFuture<Void> onConnected();
    
    /**
     * 连接断开时调用
     */
    CompletableFuture<Void> onDisconnected();
    
    /**
     * 开始重连时调用
     * @param attempt 重连尝试次数
     */
    CompletableFuture<Void> onReconnecting(int attempt);
    
    /**
     * 重连成功时调用
     */
    CompletableFuture<Void> onReconnectSuccess();
    
    /**
     * 重连失败时调用
     * @param error 错误信息
     */
    CompletableFuture<Void> onReconnectFailed(Exception error);
}
