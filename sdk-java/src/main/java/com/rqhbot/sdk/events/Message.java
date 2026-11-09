package com.rqhbot.sdk.events;

import java.util.List;
import java.util.Map;

/**
 * 消息内容
 */
public record Message(
    String plainText,
    String rawMessage,
    List<Map<String, Object>> segments
) {
    
    public Message {
        // 默认值处理
        if (plainText == null) plainText = "";
        if (rawMessage == null) rawMessage = "";
        if (segments == null) segments = List.of();
    }
    
    public Message() {
        this("", "", List.of());
    }
    
    /**
     * 获取纯文本消息
     */
    public String getPlainText() {
        return plainText.isEmpty() ? rawMessage : plainText;
    }
    
    /**
     * 从 Map 创建 Message 实例
     * @param data 消息数据
     * @return Message 对象
     */
    public static Message fromMap(Object data) {
        if (data == null) {
            return new Message();
        }
        
        if (data instanceof String str) {
            return new Message(str, str, List.of());
        }
        
        if (data instanceof List<?> list) {
            StringBuilder plainText = new StringBuilder();
            List<Map<String, Object>> segments = new java.util.ArrayList<>();
            
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String type = (String) map.get("type");
                    if ("text".equals(type)) {
                        Object dataObj = map.get("data");
                        if (dataObj instanceof Map<?, ?> dataMap) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> safeDataMap = (Map<String, Object>) dataMap;
                            if (safeDataMap.get("text") != null) {
                                plainText.append(safeDataMap.get("text"));
                            }
                        }
                    }
                    // 将所有 segment 添加到列表中
                    @SuppressWarnings("unchecked")
                    Map<String, Object> segment = (Map<String, Object>) map;
                    segments.add(segment);
                }
            }
            
            String text = plainText.toString();
            return new Message(text, text, segments);
        }
        
        return new Message();
    }
}
