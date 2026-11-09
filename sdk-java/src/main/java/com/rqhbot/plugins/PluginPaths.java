package com.rqhbot.plugins;

import java.nio.file.Path;

/**
 * Shared filesystem layout for Java plugins.
 *
 * 运行时数据（配置、用户数据等）放在顶层 data/ 目录，按插件名分隔。
 * 静态资源（图片等）放在插件源码同级目录下，即 plugins/{pluginName}/。
 */
public final class PluginPaths {
    private static final Path DATA_ROOT = Path.of("data");
    private static final Path PLUGINS_ROOT = Path.of("plugins");

    private PluginPaths() {
    }

    /** 插件配置目录: data/{pluginName}/ */
    public static Path configDir(String pluginName) {
        return DATA_ROOT.resolve(pluginName);
    }

    /** 插件数据目录: data/{pluginName}/ */
    public static Path dataDir(String pluginName) {
        return DATA_ROOT.resolve(pluginName);
    }

    /** 插件资源目录: plugins/{pluginName}/ */
    public static Path assetsDir(String pluginName) {
        return PLUGINS_ROOT.resolve(pluginName);
    }
}
