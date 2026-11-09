package com.rqhbot.sdk.events;

import java.util.Map;

/**
 * 请求事件
 */
public record RequestEvent(
    long time,
    long selfId,
    String postType,
    String requestType,
    String comment,
    String flag
) implements BotEvent {
    
    public RequestEvent {
        if (requestType == null) requestType = "";
        if (comment == null) comment = "";
        if (flag == null) flag = "";
    }
    
    public RequestEvent() {
        this(0, 0, "", "", "", "");
    }
    
    /**
     * 从 Map 创建事件实例
     * @param data 事件数据
     * @return RequestEvent 对象
     */
    public static RequestEvent fromMap(Map<String, Object> data) {
        return new RequestEvent(
            getLong(data, "time"),
            getLong(data, "self_id"),
            getString(data, "post_type"),
            getString(data, "request_type"),
            getString(data, "comment"),
            getString(data, "flag")
        );
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
}
