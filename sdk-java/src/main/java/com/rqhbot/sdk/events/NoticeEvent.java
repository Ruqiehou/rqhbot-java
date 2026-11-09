package com.rqhbot.sdk.events;

import java.util.Map;

/**
 * 通知事件
 */
public record NoticeEvent(
    long time,
    long selfId,
    String postType,
    String noticeType
) implements BotEvent {
    
    public NoticeEvent {
        if (noticeType == null) noticeType = "";
    }
    
    public NoticeEvent() {
        this(0, 0, "", "");
    }
    
    /**
     * 从 Map 创建事件实例
     * @param data 事件数据
     * @return NoticeEvent 对象
     */
    public static NoticeEvent fromMap(Map<String, Object> data) {
        return new NoticeEvent(
            getLong(data, "time"),
            getLong(data, "self_id"),
            getString(data, "post_type"),
            getString(data, "notice_type")
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
