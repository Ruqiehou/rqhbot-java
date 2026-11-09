package com.rqhbot.sdk.events;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.rqhbot.sdk.BotClient;

/**
 * 群消息事件
 */
public record GroupMessageEvent(
    long time,
    long selfId,
    String postType,
    String messageType,
    String subType,
    long messageId,
    long groupId,
    long userId,
    String userName,
    Message message,
    String rawMessage,
    int font,
    Map<String, Object> sender,
    BotClient bot
) implements BotEvent {
    
    public GroupMessageEvent {
        // 默认值处理
        if (messageType == null) messageType = "group";
        if (subType == null) subType = "";
        if (userName == null) userName = "";
        if (message == null) message = new Message();
        if (rawMessage == null) rawMessage = "";
        if (sender == null) sender = new HashMap<>();
    }
    
    /**
     * 从 Map 创建事件实例
     * @param data 事件数据
     * @return GroupMessageEvent 对象
     */
    public static GroupMessageEvent fromMap(Map<String, Object> data) {
        Object messageData = data.get("message");
        String rawMessage = getString(data, "raw_message");
        
        Message message = Message.fromMap(messageData);
        
        // 获取发送者信息
        Map<String, Object> sender = getMap(data, "sender");
        String userName = "";
        if (sender != null) {
            userName = getString(sender, "nickname");
            if (userName.isEmpty()) {
                userName = getString(sender, "card");
            }
        }
        
        return new GroupMessageEvent(
            getLong(data, "time"),
            getLong(data, "self_id"),
            getString(data, "post_type"),
            getString(data, "message_type"),
            getString(data, "sub_type"),
            getLong(data, "message_id"),
            getLong(data, "group_id"),
            getLong(data, "user_id"),
            userName,
            message,
            rawMessage,
            getInt(data, "font"),
            sender != null ? sender : new HashMap<>(),
            null // bot 将在外部设置
        );
    }
    
    /**
     * 回复群消息
     * @param text 回复内容
     * @return API 响应
     */
    public CompletableFuture<Map<String, Object>> reply(String text) {
        if (bot == null) {
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            future.completeExceptionally(new RuntimeException("事件未绑定到 BotClient"));
            return future;
        }
        return bot.sendGroupMessage(groupId, text);
    }
    
    /**
     * 私聊回复发送者
     * @param text 回复内容
     * @return API 响应
     */
    public CompletableFuture<Map<String, Object>> replyPrivate(String text) {
        if (bot == null) {
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            future.completeExceptionally(new RuntimeException("事件未绑定到 BotClient"));
            return future;
        }
        return bot.sendPrivateMessage(userId, text);
    }
    
    private static String getString(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof String str) {
            return str;
        } else if (value != null) {
            return value.toString();
        }
        return "";
    }
    
    private static long getLong(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number num) {
            return num.longValue();
        } else if (value instanceof String str) {
            try {
                return Long.parseLong(str);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }
    
    private static int getInt(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number num) {
            return num.intValue();
        } else if (value instanceof String str) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
    
    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Map<?, ?> map) {
            // 创建一个安全的 HashMap 副本
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(
                    entry.getKey() != null ? entry.getKey().toString() : "",
                    entry.getValue()
                );
            }
            return result;
        }
        return new HashMap<>();
    }
}
