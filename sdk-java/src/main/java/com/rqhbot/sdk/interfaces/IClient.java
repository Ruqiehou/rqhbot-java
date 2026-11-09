package com.rqhbot.sdk.interfaces;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 客户端接口 - 定义所有客户端必须实现的方法
 */
public interface IClient {
    
    /**
     * 是否已连接
     * @return 连接状态
     */
    boolean isConnected();
    
    /**
     * 建立连接
     * @param maxRetries 最大重试次数
     * @return 是否连接成功
     */
    CompletableFuture<Boolean> connect(Integer maxRetries);
    
    /**
     * 断开连接
     * @param force 是否强制断开
     */
    CompletableFuture<Void> disconnect(boolean force);
    
    /**
     * 调用 API
     * @param action API 动作名称
     * @param params 参数字典
     * @param maxRetries 最大重试次数
     * @param retryDelay 重试延迟（毫秒）
     * @return API 响应数据
     */
    CompletableFuture<Map<String, Object>> callApi(
        String action, 
        Map<String, Object> params,
        int maxRetries,
        long retryDelay
    );
    
    /**
     * 发送私聊消息
     * @param userId 用户 ID
     * @param message 消息内容
     * @return API 响应数据
     */
    CompletableFuture<Map<String, Object>> sendPrivateMessage(long userId, String message);
    
    /**
     * 发送群聊消息
     * @param groupId 群 ID
     * @param message 消息内容
     * @return API 响应数据
     */
    CompletableFuture<Map<String, Object>> sendGroupMessage(long groupId, String message);
    
    /**
     * 注册消息处理器
     * @param messageType 消息类型（如 "group", "private"）
     * @param handler 处理器函数
     */
    void onMessage(String messageType, MessageHandler handler);
    
    /**
     * 添加连接事件监听器
     * @param listener 监听器
     */
    void addConnectionListener(IConnectionEventListener listener);
    
    /**
     * 移除连接事件监听器
     * @param listener 监听器
     */
    void removeConnectionListener(IConnectionEventListener listener);
    
    /**
     * 获取性能统计信息
     * @return 性能统计 Map
     */
    Map<String, Object> getPerformanceStats();
}
