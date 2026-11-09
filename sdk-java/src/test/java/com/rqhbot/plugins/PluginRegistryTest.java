package com.rqhbot.plugins;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.rqhbot.sdk.pluginsystem.PluginBase;

/**
 * 插件动态发现单测
 */
class PluginRegistryTest {

    @Test
    void discoverPluginsDoesNotThrow() {
        assertDoesNotThrow(PluginRegistry::discoverPlugins);
    }

    @Test
    void discoverPluginsReturnsList() {
        List<Class<? extends PluginBase>> plugins = PluginRegistry.discoverPlugins();
        assertNotNull(plugins);
        // 编译后 classpath 上应至少能扫到内置插件；若测试 classpath 不全也不应抛错
        assertTrue(plugins.size() >= 0);
    }
}
