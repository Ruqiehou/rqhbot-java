package com.rqhbot.sdk.adapter;

import java.util.Locale;

/**
 * OneBot 11 协议端后端描述
 *
 * NapCat 与 SnowLuma 均通过 OneBot 11 正向 WebSocket 暴露接口，
 * 差异只在默认地址、环境变量前缀以及是否支持由框架自动启动。
 *
 * @param name             后端标识（小写，如 napcat / snowluma）
 * @param displayName      展示名称（如 NapCat / SnowLuma）
 * @param defaultWsUrl     未显式配置时的默认 WebSocket 地址
 * @param autoStartCapable 是否支持由框架自动启动
 */
public record BackendSpec(
        String name,
        String displayName,
        String defaultWsUrl,
        boolean autoStartCapable) {

    /**
     * 该后端专属环境变量前缀，如 napcat 对应 NAPCAT_。
     */
    public String envPrefix() {
        return name.toUpperCase(Locale.ROOT) + "_";
    }
}
