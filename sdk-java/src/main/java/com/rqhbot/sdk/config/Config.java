package com.rqhbot.sdk.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Config {
    private static final Logger logger = LoggerFactory.getLogger(Config.class);

    public static String NAPCAT_WS_URL = loadEnv("NAPCAT_WS_URL", "ws://localhost:3001");
    public static String NAPCAT_ACCESS_TOKEN = loadEnv("NAPCAT_ACCESS_TOKEN", "");
    public static String NAPCAT_QQ_ID = loadEnv("NAPCAT_QQ_ID", "");
    public static String LOG_LEVEL = loadEnv("LOG_LEVEL", "INFO");
    public static String LOG_DIR = loadEnv("LOG_DIR", "log");
    public static boolean BOT_DEBUG = Boolean.parseBoolean(loadEnv("BOT_DEBUG", "false"));

    private static String loadEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }

        try {
            Path envPath = Path.of(".env");
            if (Files.exists(envPath)) {
                Properties props = new Properties();
                try (InputStream is = Files.newInputStream(envPath)) {
                    props.load(is);
                    String propValue = props.getProperty(key);
                    if (propValue != null && !propValue.trim().isEmpty()) {
                        return propValue.trim();
                    }
                }
            }
        } catch (IOException e) {
            logger.debug("读取.env 文件失败：{}", e.getMessage());
        }

        return defaultValue;
    }

    public static String get(String key, String defaultValue) {
        return switch (key) {
            case "NAPCAT_WS_URL" -> NAPCAT_WS_URL;
            case "NAPCAT_ACCESS_TOKEN" -> NAPCAT_ACCESS_TOKEN;
            case "NAPCAT_QQ_ID" -> NAPCAT_QQ_ID;
            case "LOG_LEVEL" -> LOG_LEVEL;
            case "LOG_DIR" -> LOG_DIR;
            default -> defaultValue;
        };
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        if ("BOT_DEBUG".equals(key)) {
            return BOT_DEBUG;
        }
        return defaultValue;
    }
}
