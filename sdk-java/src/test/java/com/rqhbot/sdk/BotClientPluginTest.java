package com.rqhbot.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.rqhbot.sdk.pluginsystem.PluginBase;

/**
 * BotClient / PluginManager 基础行为单测
 */
class BotClientPluginTest {

    @Test
    void pluginManagerIsInitialized() {
        BotClient bot = new BotClient("ws://localhost:3001", "token", "plugins");
        assertNotNull(bot.getPluginManager());
    }

    @Test
    void registerAndGetPlugin() {
        BotClient bot = new BotClient("ws://localhost:3001", "token", "plugins");
        TestPlugin plugin = new TestPlugin();

        assertTrue(bot.registerPlugin(plugin));
        PluginBase retrieved = bot.getPluginManager().getPlugin("testplugin");
        assertNotNull(retrieved);
        assertEquals("testplugin", retrieved.getName());
    }

    @Test
    void duplicateRegisterReturnsFalse() {
        BotClient bot = new BotClient("ws://localhost:3001", "token", "plugins");
        TestPlugin plugin = new TestPlugin();
        assertTrue(bot.registerPlugin(plugin));
        assertFalse(bot.registerPlugin(plugin));
    }

    @Test
    void getMissingPluginReturnsNull() {
        BotClient bot = new BotClient("ws://localhost:3001", "token", "plugins");
        assertNull(bot.getPluginManager().getPlugin("missing"));
    }

    @Test
    void pluginBaseDefaults() {
        TestPlugin plugin = new TestPlugin();
        assertEquals("testplugin", plugin.getName());
        assertEquals("1.0.0", plugin.getVersion());
        assertTrue(plugin.isEnabled());
    }

    private static final class TestPlugin extends PluginBase {
        TestPlugin() {
            this.name = "testplugin";
            this.version = "1.0.0";
            this.enabled = true;
        }

        @Override
        public CompletableFuture<Void> onLoad(BotClient bot) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> onUnload(BotClient bot) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
