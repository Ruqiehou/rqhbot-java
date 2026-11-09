package com.rqhbot.sdk.adapter;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OneBot 11 后端注册表
 *
 * 新增协议端只需在这里登记一项，配置解析与启动流程会按 BACKEND 自动适配。
 */
public final class Backends {

    /** 缺省后端 */
    public static final String DEFAULT_BACKEND = "napcat";

    private static final Map<String, BackendSpec> REGISTRY = new LinkedHashMap<>();

    static {
        register(new BackendSpec("napcat", "NapCat", "ws://127.0.0.1:3002", true));
        register(new BackendSpec("snowluma", "SnowLuma", "ws://127.0.0.1:3001", false));
    }

    private Backends() {
    }

    private static void register(BackendSpec spec) {
        REGISTRY.put(spec.name(), spec);
    }

    /**
     * 按名称获取后端描述，不区分大小写。
     *
     * @param name 后端名称，null 或空白时返回缺省后端
     * @return 对应的后端描述
     * @throws IllegalArgumentException 名称未注册时抛出，消息中列出可选值
     */
    public static BackendSpec get(String name) {
        String key = (name == null || name.isBlank())
                ? DEFAULT_BACKEND
                : name.trim().toLowerCase(Locale.ROOT);
        BackendSpec spec = REGISTRY.get(key);
        if (spec == null) {
            throw new IllegalArgumentException(
                    "未知的协议端后端：" + name + "，可选值：" + names());
        }
        return spec;
    }

    /**
     * 已注册的后端名称，用于帮助信息与错误提示。
     */
    public static String names() {
        return REGISTRY.keySet().stream().collect(Collectors.joining(", "));
    }

    /**
     * 只读的后端注册表视图。
     */
    public static Map<String, BackendSpec> all() {
        return Map.copyOf(REGISTRY);
    }
}
