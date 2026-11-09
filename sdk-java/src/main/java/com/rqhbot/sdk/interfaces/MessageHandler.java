package com.rqhbot.sdk.interfaces;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 消息处理器函数式接口
 */
@FunctionalInterface
public interface MessageHandler {
    
    /**
     * 处理消息
     * @param data 消息数据
     * @return CompletableFuture
     */
    CompletableFuture<Void> handle(Map<String, Object> data);
}
