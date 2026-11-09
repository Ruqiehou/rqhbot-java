package com.rqhbot.sdk.events;

import java.util.Map;

/**
 * 基础事件类
 */
public record BaseEvent(
    long time,
    long selfId,
    String postType
) {
    
    public BaseEvent {
        // 默认值处理
        if (postType == null) postType = "";
    }
    
    public BaseEvent() {
        this(0, 0, "");
    }
    
    /**
     * 从 Map 创建事件实例
     * @param data 事件数据
     * @return BaseEvent 对象
     */
    public static BaseEvent fromMap(Map<String, Object> data) {
        return new BaseEvent(
            getLong(data, "time"),
            getLong(data, "self_id"),
            getString(data, "post_type")
        );
    }
    
    /**
     * 安全获取 Long 值
     */
    protected static long getLong(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number num) {
            return num.longValue();
        } else if (value instanceof String str) {
            try {
                return Long.parseLong(str);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
    
    /**
     * 安全获取 String 值
     */
    protected static String getString(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value != null ? value.toString() : "";
    }
}
