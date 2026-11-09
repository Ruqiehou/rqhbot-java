package com.rqhbot.sdk.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 适配器连接参数解析器
 *
 * 按 BACKEND（兼容 ADAPTER）字段选择后端，读取该后端专属的连接配置；
 * 未配置时回落到后端默认地址（NapCat 3002 / SnowLuma 3001）。
 *
 * 取值优先级（从高到低）：
 * <ol>
 *   <li>当前激活实例（多实例后端，如 SnowLuma）的 ws / token</li>
 *   <li>后端专属键：&lt;后端&gt;_WS_URL / &lt;后端&gt;_ACCESS_TOKEN / &lt;后端&gt;_QQ_ID</li>
 *   <li>通用键：WS_URL / ACCESS_TOKEN / QQ_ID</li>
 *   <li>后端默认 WebSocket 地址</li>
 * </ol>
 */
public final class AdapterResolver {

    private static final Logger logger = LoggerFactory.getLogger(AdapterResolver.class);

    /** 环境变量文件名 */
    public static final String ENV_FILE = ".env";

    /**
     * 多实例后端的单个连接实例（如 SnowLuma 多开）
     *
     * @param name        实例名称
     * @param wsUrl       实例 WebSocket 地址，可为 null（回落默认地址）
     * @param accessToken 实例访问令牌，可为 null（回落配置段令牌）
     */
    public record Instance(String name, String wsUrl, String accessToken) {
    }

    private final Map<String, String> values;
    private final BackendSpec backend;
    private final List<Instance> instances;
    private final int activeIndex;
    private final String wsUrl;
    private final String accessToken;
    private final String qqId;

    private AdapterResolver(Map<String, String> rawValues) {
        Map<String, String> cleaned = new LinkedHashMap<>();
        rawValues.forEach((key, value) -> {
            if (key != null && value != null && !value.isBlank()) {
                cleaned.put(key, value.trim());
            }
        });
        this.values = Map.copyOf(cleaned);
        this.backend = Backends.get(firstNotBlank(values.get("BACKEND"), values.get("ADAPTER")));

        String prefix = backend.envPrefix();
        this.instances = List.copyOf(parseInstances(values, prefix));
        this.activeIndex = parseIndex(values.get(prefix + "ACTIVE"), instances.size());

        Instance active = activeInstance();
        this.wsUrl = firstNotBlank(
                active == null ? null : active.wsUrl(),
                values.get(prefix + "WS_URL"),
                values.get("WS_URL"),
                backend.defaultWsUrl());
        this.accessToken = firstNotBlank(
                active == null ? null : active.accessToken(),
                values.get(prefix + "ACCESS_TOKEN"),
                values.get("ACCESS_TOKEN"),
                "");
        this.qqId = firstNotBlank(
                values.get(prefix + "QQ_ID"),
                values.get("QQ_ID"),
                "");
    }

    /**
     * 读取系统环境变量与当前目录 {@code .env}（系统环境变量优先）构造解析器。
     */
    public static AdapterResolver fromEnvironment() {
        Map<String, String> values = new LinkedHashMap<>();

        Path envPath = Path.of(ENV_FILE);
        if (Files.exists(envPath)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(envPath)) {
                props.load(in);
                props.stringPropertyNames()
                        .forEach(key -> values.put(key, props.getProperty(key).trim()));
            } catch (IOException e) {
                logger.warn("读取 {} 失败：{}", ENV_FILE, e.getMessage());
            }
        }

        System.getenv().forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                values.put(key, value.trim());
            }
        });

        return fromValues(values);
    }

    /**
     * 从已加载的配置键值构造解析器（便于测试与自定义配置来源）。
     */
    public static AdapterResolver fromValues(Map<String, String> values) {
        return new AdapterResolver(values);
    }

    /**
     * 当前后端描述。
     */
    public BackendSpec backend() {
        return backend;
    }

    /**
     * 当前后端名称（小写）。
     */
    public String backendName() {
        return backend.name();
    }

    /**
     * 解析后的 WebSocket 地址。
     */
    public String wsUrl() {
        return wsUrl;
    }

    /**
     * 解析后的访问令牌，未配置时为空串。
     */
    public String accessToken() {
        return accessToken;
    }

    /**
     * 解析后的机器人 QQ 号，未配置时为空串。
     */
    public String qqId() {
        return qqId;
    }

    /**
     * 当前后端配置的实例列表，未配置时为空。
     */
    public List<Instance> instances() {
        return instances;
    }

    /**
     * 当前激活实例索引，越界或非法时为 0。
     */
    public int activeIndex() {
        return activeIndex;
    }

    /**
     * 是否存在多个实例，需要启动前选择。
     */
    public boolean hasMultipleInstances() {
        return instances.size() > 1;
    }

    /**
     * 切换后端，连接参数按新后端重新解析。
     *
     * @throws IllegalArgumentException 后端未注册时抛出
     */
    public AdapterResolver withBackend(String name) {
        Map<String, String> next = new LinkedHashMap<>(values);
        next.put("BACKEND", Backends.get(name).name());
        return new AdapterResolver(next);
    }

    /**
     * 切换激活实例。
     *
     * @param index 0 起的实例索引
     * @throws IllegalArgumentException 索引超出范围时抛出
     */
    public AdapterResolver withInstance(int index) {
        if (instances.isEmpty()) {
            throw new IllegalArgumentException(
                    "当前后端未配置实例，请先设置 " + backend.envPrefix() + "INSTANCE_1_WS_URL");
        }
        if (index < 0 || index >= instances.size()) {
            throw new IllegalArgumentException(
                    "实例索引 " + index + " 超出范围 (0-" + (instances.size() - 1) + ")");
        }
        Map<String, String> next = new LinkedHashMap<>(values);
        next.put(backend.envPrefix() + "ACTIVE", String.valueOf(index));
        return new AdapterResolver(next);
    }

    /**
     * 当前激活实例，未配置实例时为 null。
     */
    private Instance activeInstance() {
        return (activeIndex >= 0 && activeIndex < instances.size()) ? instances.get(activeIndex) : null;
    }

    private static List<Instance> parseInstances(Map<String, String> values, String prefix) {
        List<Instance> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            String url = values.get(prefix + "INSTANCE_" + i + "_WS_URL");
            String name = values.get(prefix + "INSTANCE_" + i + "_NAME");
            String token = values.get(prefix + "INSTANCE_" + i + "_ACCESS_TOKEN");
            if (url == null && name == null && token == null) {
                break;
            }
            result.add(new Instance(name == null ? "实例 " + i : name, url, token));
        }
        return result;
    }

    private static int parseIndex(String raw, int size) {
        if (raw == null) {
            return 0;
        }
        try {
            int index = Integer.parseInt(raw.trim());
            return (index >= 0 && index < size) ? index : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String firstNotBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }
}
