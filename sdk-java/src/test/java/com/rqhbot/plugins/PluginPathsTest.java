package com.rqhbot.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * PluginPaths 路径约定单测
 */
class PluginPathsTest {

    @Test
    void configDirUsesDataRoot() {
        assertEquals("data/kinkus", normalize(PluginPaths.configDir("kinkus")));
    }

    @Test
    void dataDirUsesDataRoot() {
        assertEquals("data/speech", normalize(PluginPaths.dataDir("speech")));
    }

    @Test
    void assetsDirUsesPluginRoot() {
        assertEquals("plugins/kinkus", normalize(PluginPaths.assetsDir("kinkus")));
        assertEquals("plugins/bilibili", normalize(PluginPaths.assetsDir("bilibili")));
        assertNotNull(PluginPaths.assetsDir("cultivation"));
    }

    private static String normalize(java.nio.file.Path path) {
        return path.toString().replace('\\', '/');
    }
}
