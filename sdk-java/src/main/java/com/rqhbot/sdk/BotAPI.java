package com.rqhbot.sdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 机器人 API 封装类
 */
public class BotAPI {
    
    private static final Logger logger = LoggerFactory.getLogger(BotAPI.class);
    
    private final NapCatClient client;
    
    public BotAPI(NapCatClient client) {
        this.client = client;
    }
    
    /**
     * 发送私聊消息
     */
    public CompletableFuture<Map<String, Object>> sendPrivateMessage(long userId, String message) {
        return client.sendPrivateMessage(userId, message);
    }
    
    /**
     * 发送群消息
     */
    public CompletableFuture<Map<String, Object>> sendGroupMessage(long groupId, String message) {
        return client.sendGroupMessage(groupId, message);
    }
    
    /**
     * 发送群图片
     */
    public CompletableFuture<Map<String, Object>> sendGroupImage(
            long groupId, 
            String imagePath, 
            String imageUrl, 
            String imageBase64, 
            String message
    ) {
        String cqCode = buildImageCQCode(imagePath, imageUrl, imageBase64);
        if (cqCode == null) {
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("必须提供 image_path、image_url 或 image_base64 其中之一"));
            return future;
        }
        
        String fullMessage = buildFullMessage(message, cqCode);
        return client.sendGroupMessage(groupId, fullMessage);
    }
    
    /**
     * 发送私聊图片
     */
    public CompletableFuture<Map<String, Object>> sendPrivateImage(
            long userId, 
            String imagePath, 
            String imageUrl, 
            String imageBase64, 
            String message
    ) {
        String cqCode = buildImageCQCode(imagePath, imageUrl, imageBase64);
        if (cqCode == null) {
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("必须提供 image_path、image_url 或 image_base64 其中之一"));
            return future;
        }
        
        String fullMessage = buildFullMessage(message, cqCode);
        return client.sendPrivateMessage(userId, fullMessage);
    }
    
    /**
     * 撤回消息
     */
    public CompletableFuture<Map<String, Object>> deleteMessage(long messageId) {
        Map<String, Object> params = new HashMap<>();
        params.put("message_id", messageId);
        return client.callApi("delete_msg", params, 3, 1000);
    }
    
    /**
     * 构建图片 CQ 码
     */
    private String buildImageCQCode(String imagePath, String imageUrl, String imageBase64) {
        if (imagePath != null && !imagePath.isEmpty()) {
            Path path = Path.of(imagePath);
            if (Files.exists(path)) {
                try {
                    byte[] imageBytes = Files.readAllBytes(path);
                    String base64 = Base64.getEncoder().encodeToString(imageBytes);
                    return "[CQ:image,file=base64://" + base64 + "]";
                } catch (IOException e) {
                    logger.error("读取图片文件失败：{}", e.getMessage());
                }
            }
        }
        
        if (imageUrl != null && !imageUrl.isEmpty()) {
            return "[CQ:image,file=" + imageUrl + "]";
        }
        
        if (imageBase64 != null && !imageBase64.isEmpty()) {
            return "[CQ:image,file=base64://" + imageBase64 + "]";
        }
        
        return null;
    }
    
    /**
     * 构建完整消息
     */
    private String buildFullMessage(String message, String cqCode) {
        if (message != null && !message.isEmpty()) {
            return message + cqCode;
        }
        return cqCode;
    }
}
