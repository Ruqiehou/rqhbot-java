package com.rqhbot.sdk.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 适配器后端注册表与连接参数解析测试
 */
class AdapterResolverTest {

    private static AdapterResolver resolver(String... pairs) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return AdapterResolver.fromValues(values);
    }

    @Test
    void defaultsToNapCatOnPort3002() {
        AdapterResolver resolver = resolver();
        assertEquals("napcat", resolver.backendName());
        assertEquals("NapCat", resolver.backend().displayName());
        assertEquals("ws://127.0.0.1:3002", resolver.wsUrl());
        assertTrue(resolver.backend().autoStartCapable());
    }

    @Test
    void snowlumaDefaultsToPort3001AndNoAutoStart() {
        AdapterResolver resolver = resolver("BACKEND", "snowluma");
        assertEquals("snowluma", resolver.backendName());
        assertEquals("SnowLuma", resolver.backend().displayName());
        assertEquals("ws://127.0.0.1:3001", resolver.wsUrl());
        assertFalse(resolver.backend().autoStartCapable());
    }

    @Test
    void backendNameIsCaseInsensitive() {
        assertEquals("snowluma", resolver("BACKEND", " SnowLuma ").backendName());
        assertEquals("napcat", resolver("ADAPTER", "NAPCAT").backendName());
    }

    @Test
    void unknownBackendListsAvailableNames() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> resolver("BACKEND", "unknown"));
        assertTrue(error.getMessage().contains("napcat"));
        assertTrue(error.getMessage().contains("snowluma"));
    }

    @Test
    void backendSpecificKeyWinsOverGenericKey() {
        AdapterResolver resolver = resolver(
                "BACKEND", "snowluma",
                "SNOWLUMA_WS_URL", "ws://snow:4001",
                "WS_URL", "ws://generic:5001");
        assertEquals("ws://snow:4001", resolver.wsUrl());
    }

    @Test
    void genericKeyUsedWhenBackendKeyMissing() {
        assertEquals("ws://generic:5001", resolver("BACKEND", "snowluma", "WS_URL", "ws://generic:5001").wsUrl());
    }

    @Test
    void tokenAndQqIdFallBackToGenericKeys() {
        AdapterResolver resolver = resolver(
                "BACKEND", "snowluma",
                "ACCESS_TOKEN", "generic-token",
                "QQ_ID", "10001");
        assertEquals("generic-token", resolver.accessToken());
        assertEquals("10001", resolver.qqId());
    }

    @Test
    void activeInstanceOverridesSectionSettings() {
        AdapterResolver resolver = resolver(
                "BACKEND", "snowluma",
                "SNOWLUMA_WS_URL", "ws://section:3001",
                "SNOWLUMA_INSTANCE_1_NAME", "一号",
                "SNOWLUMA_INSTANCE_1_WS_URL", "ws://one:3001",
                "SNOWLUMA_INSTANCE_1_ACCESS_TOKEN", "token-one",
                "SNOWLUMA_INSTANCE_2_NAME", "二号",
                "SNOWLUMA_INSTANCE_2_WS_URL", "ws://two:3001",
                "SNOWLUMA_ACTIVE", "1");

        List<AdapterResolver.Instance> instances = resolver.instances();
        assertEquals(2, instances.size());
        assertTrue(resolver.hasMultipleInstances());
        assertEquals(1, resolver.activeIndex());
        assertEquals("二号", instances.get(1).name());
        assertEquals("ws://two:3001", resolver.wsUrl());
    }

    @Test
    void instanceWithoutAddressFallsBackToDefault() {
        AdapterResolver resolver = resolver(
                "BACKEND", "snowluma",
                "SNOWLUMA_INSTANCE_1_NAME", "仅命名");
        assertEquals("ws://127.0.0.1:3001", resolver.wsUrl());
    }

    @Test
    void invalidActiveIndexFallsBackToFirstInstance() {
        AdapterResolver resolver = resolver(
                "BACKEND", "snowluma",
                "SNOWLUMA_INSTANCE_1_WS_URL", "ws://one:3001",
                "SNOWLUMA_ACTIVE", "9");
        assertEquals(0, resolver.activeIndex());
        assertEquals("ws://one:3001", resolver.wsUrl());
    }

    @Test
    void withBackendReResolvesConnectionSettings() {
        AdapterResolver napcat = resolver(
                "BACKEND", "napcat",
                "WS_URL", "ws://shared:3002",
                "SNOWLUMA_ACCESS_TOKEN", "snow-token");

        AdapterResolver snowluma = napcat.withBackend("snowluma");
        assertEquals("snowluma", snowluma.backendName());
        assertEquals("ws://shared:3002", snowluma.wsUrl());
        assertEquals("snow-token", snowluma.accessToken());
    }

    @Test
    void withInstanceSwitchesActiveInstance() {
        AdapterResolver resolver = resolver(
                "BACKEND", "snowluma",
                "SNOWLUMA_INSTANCE_1_WS_URL", "ws://one:3001",
                "SNOWLUMA_INSTANCE_2_WS_URL", "ws://two:3001");

        AdapterResolver switched = resolver.withInstance(1);
        assertEquals(1, switched.activeIndex());
        assertEquals("ws://two:3001", switched.wsUrl());
    }

    @Test
    void withInstanceRejectsOutOfRange() {
        AdapterResolver resolver = resolver("BACKEND", "snowluma", "SNOWLUMA_INSTANCE_1_NAME", "一号");
        assertThrows(IllegalArgumentException.class, () -> resolver.withInstance(1));
        assertThrows(IllegalArgumentException.class, () -> resolver.withInstance(-1));
    }
}
