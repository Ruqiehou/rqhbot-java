package com.rqhbot;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rqhbot.plugins.PluginRegistry;
import com.rqhbot.sdk.BotClient;
import com.rqhbot.sdk.NapCatAutoLauncher;

/**
 * RqhBot Java 版主程序
 */
public class BotMain {
    
    private static final Logger logger = LoggerFactory.getLogger(BotMain.class);
    
    public static void main(String[] args) {
        logger.info("========================================");
        logger.info("  RqhBot Java 版启动中...");
        logger.info("========================================");
        
        try {
            // 1. 加载配置
            Config config = loadConfig(args);

            if (config.checkPlugins) {
                BotClient bot = new BotClient("ws://localhost:0", "", config.qqId, config.pluginsDir);
                registerPlugins(bot);
                logger.info("插件自检完成，已注册插件数量：{}", bot.getLoadedPluginCount());
                System.out.println("插件自检完成，已注册插件数量：" + bot.getLoadedPluginCount());
                return;
            }
            
            // 2. 创建机器人客户端
            logger.info("协议端后端：{}", config.backend);
            logger.info("正在连接已有 WebSocket: {}", config.wsUrl);
            if (!config.qqId.isEmpty()) {
                logger.info("机器人 QQ 号: {}", config.qqId);
            }
            BotClient bot = new BotClient(config.wsUrl, config.accessToken, config.qqId, config.pluginsDir);
            
            // 3. 注册事件处理器（示例）
            registerEventHandlers(bot);

            // 4. 注册 Java 插件
            registerPlugins(bot);
            
            // 5. 注册 NapCat 自动启动适配器（仅 NapCat 后端）
            NapCatAutoLauncher autoLauncher = null;
            if (config.autoLaunch && !"napcat".equalsIgnoreCase(config.backend)) {
                logger.warn("当前协议端后端为 {}，--auto-launch 仅适用于 NapCat，已跳过自动启动", config.backend);
            } else if (config.autoLaunch) {
                autoLauncher = new NapCatAutoLauncher(bot.getClient());
                bot.getClient().addConnectionListener(autoLauncher);
                logger.info("NapCat 自动启动已启用");
                
                // 检查 NapCat 目录
                if (autoLauncher.isNapCatAvailable()) {
                    logger.info("NapCat 目录检查通过：{}", autoLauncher.getNapCatDir());
                } else {
                    logger.warn("NapCat 目录或启动脚本不存在，自动启动功能可能无法正常工作");
                }
            }
            
            // 6. 启动机器人
            logger.info("启动机器人服务...");
            bot.start(false, false); // debug=false, built-in plugins already registered
            
            // 7. 保持运行
            final NapCatAutoLauncher finalAutoLauncher = autoLauncher;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("正在关闭机器人...");
                if (finalAutoLauncher != null) {
                    finalAutoLauncher.stopNapCat();
                    finalAutoLauncher.shutdown();
                }
                com.rqhbot.sdk.BotExecutor.shutdown();
            }));
            
            // 主线程等待退出信号
            while (true) {
                Thread.sleep(60000); // 每分钟检查一次
            }
            
        } catch (Exception e) {
            logger.error("启动失败：{}", e.getMessage(), e);
            System.err.println("\n错误详情：" + e.getMessage());
            System.err.println("请检查配置文件或命令行参数。");
            System.err.println("按回车键退出...");
            try {
                System.in.read();
            } catch (IOException ignored) {
            }
            System.exit(1);
        }
    }
    
    /**
     * 加载配置
     */
    private static Config loadConfig(String[] args) throws IOException {
        Config config = new Config();
        
        // 从.env 文件加载
        File envFile = new File(".env");
        if (envFile.exists()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(envFile)) {
                props.load(fis);
                // 通用键优先，其次 SnowLuma 专用键，最后兼容旧的 NapCat 键
                config.wsUrl = firstNotBlank(
                        props.getProperty("WS_URL"),
                        props.getProperty("SNOWLUMA_WS_URL"),
                        props.getProperty("NAPCAT_WS_URL"), "");
                config.accessToken = firstNotBlank(
                        props.getProperty("ACCESS_TOKEN"),
                        props.getProperty("SNOWLUMA_ACCESS_TOKEN"),
                        props.getProperty("NAPCAT_ACCESS_TOKEN"), "");
                config.qqId = firstNotBlank(
                        props.getProperty("QQ_ID"),
                        props.getProperty("SNOWLUMA_QQ_ID"),
                        props.getProperty("NAPCAT_QQ_ID"), "");
                // 协议端后端标识：napcat / snowluma（仅用于日志与自动启动判定）
                config.backend = firstNotBlank(props.getProperty("BACKEND"), "napcat");
            }
        }
        
        // 命令行参数覆盖
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--ws-url":
                    if (i + 1 < args.length) {
                        config.wsUrl = args[++i];
                    }
                    break;
                case "--access-token":
                    if (i + 1 < args.length) {
                        config.accessToken = args[++i];
                    }
                    break;
                case "--qq-id":
                    if (i + 1 < args.length) {
                        config.qqId = args[++i];
                    }
                    break;
                case "--plugin-dir":
                    if (i + 1 < args.length) {
                        config.pluginsDir = args[++i];
                    }
                    break;
                case "--check-plugins":
                    config.checkPlugins = true;
                    break;
                case "--auto-launch":
                    config.autoLaunch = true;
                    break;
                case "--help":
                    printHelp();
                    System.exit(0);
                    break;
            }
        }
        
        // 验证必要配置
        if (!config.checkPlugins && config.wsUrl.isEmpty()) {
            throw new IllegalArgumentException("缺少 WebSocket URL，请使用 --ws-url 参数或在.env 文件中配置 WS_URL");
        }
        
        return config;
    }
    
    private static String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
    
    /**
     * 注册事件处理器
     */
    private static void registerEventHandlers(BotClient bot) {
        logger.info("注册事件处理器...");
        
        // 这里可以添加自定义的事件处理器
        // 例如：监听特定消息、定时任务等
        
        logger.info("事件处理器注册完成");
    }

    /**
     * 注册当前项目插件。
     */
    private static void registerPlugins(BotClient bot) {
        logger.info("注册插件...");
        PluginRegistry.registerAll(bot);
        logger.info("插件注册完成");
    }
    
    /**
     * 打印帮助信息
     */
    private static void printHelp() {
        System.out.println("RqhBot Java 版 - 使用帮助");
        System.out.println();
        System.out.println("用法：java [options] -jar rqhbot.jar [options]");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  --ws-url <url>         已有 WebSocket 服务器地址");
        System.out.println("  --access-token <token> 访问令牌");
        System.out.println("  --qq-id <qq>           机器人 QQ 号");
        System.out.println("  --plugin-dir <dir>     插件目录 (默认：plugins)");
        System.out.println("  --check-plugins        只检查插件自动发现和注册，不连接 WebSocket");
        System.out.println("  --auto-launch          启用 NapCat 自动启动（连接失败时自动启动根目录 NapCat，仅 NapCat 后端）");
        System.out.println("  --help                 显示帮助信息");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java -jar rqhbot.jar --ws-url ws://localhost:8080 --access-token mytoken");
        System.out.println();
        System.out.println("环境变量 (可选):");
        System.out.println("  WS_URL         已有 WebSocket 地址（如 NapCat: ws://127.0.0.1:3001）");
        System.out.println("  ACCESS_TOKEN   访问令牌");
        System.out.println("  QQ_ID          机器人 QQ 号");
        System.out.println("  BACKEND        协议端后端 napcat / snowluma（默认 napcat，仅影响日志与自动启动）");
        System.out.println();
        System.out.println("连接 SnowLuma：");
        System.out.println("  1) 在 SnowLuma WebUI 开启「正向 WebSocket 服务端」(wsServers)，默认端口 3001");
        System.out.println("  2) 设置 WS_URL=ws://127.0.0.1:3001、BACKEND=snowluma、ACCESS_TOKEN=<SnowLuma Token>");
        System.out.println("  3) 直接运行即可，无需 --auto-launch（该实例由 SnowLuma 自行启动）");
    }
    
    /**
     * 配置类
     */
    private static class Config {
        String wsUrl = "";
        String accessToken = "";
        String qqId = "";
        String pluginsDir = "plugins";
        String backend = "napcat";
        boolean checkPlugins = false;
        boolean autoLaunch = false;
    }
}
