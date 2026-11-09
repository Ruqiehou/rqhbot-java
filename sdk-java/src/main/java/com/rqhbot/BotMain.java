package com.rqhbot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rqhbot.plugins.PluginRegistry;
import com.rqhbot.sdk.BotClient;
import com.rqhbot.sdk.NapCatAutoLauncher;
import com.rqhbot.sdk.adapter.AdapterResolver;
import com.rqhbot.sdk.adapter.BackendSpec;

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
            // 1. 加载配置（按 BACKEND 解析对应协议端的连接参数）
            Config config = loadConfig(args);

            if (config.checkPlugins) {
                BotClient bot = new BotClient("ws://localhost:0", "", config.qqId, config.pluginsDir);
                registerPlugins(bot);
                logger.info("插件自检完成，已注册插件数量：{}", bot.getLoadedPluginCount());
                System.out.println("插件自检完成，已注册插件数量：" + bot.getLoadedPluginCount());
                return;
            }
            
            // 2. 创建机器人客户端
            logger.info("协议端后端：{} ({})", config.backend.displayName(), config.backend.name());
            if (config.instanceName != null) {
                logger.info("协议端实例：{}", config.instanceName);
            }
            logger.info("正在连接已有 WebSocket: {}", config.wsUrl);
            if (!config.qqId.isEmpty()) {
                logger.info("机器人 QQ 号: {}", config.qqId);
            }
            BotClient bot = new BotClient(config.wsUrl, config.accessToken, config.qqId, config.pluginsDir);
            
            // 3. 注册事件处理器（示例）
            registerEventHandlers(bot);

            // 4. 注册 Java 插件
            registerPlugins(bot);
            
            // 5. 注册自动启动适配器（仅支持自动启动的后端，如 NapCat）
            NapCatAutoLauncher autoLauncher = null;
            if (config.autoLaunch && !config.backend.autoStartCapable()) {
                logger.warn("当前协议端后端为 {}，--auto-launch 仅适用于可自动启动的后端（NapCat），已跳过自动启动",
                        config.backend.displayName());
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
    private static Config loadConfig(String[] args) {
        Config config = new Config();
        AdapterResolver resolver = AdapterResolver.fromEnvironment();

        String backendArg = null;
        Integer instanceArg = null;
        String wsUrlArg = null;
        String accessTokenArg = null;
        String qqIdArg = null;

        // 命令行参数
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--ws-url" -> { if (i + 1 < args.length) wsUrlArg = args[++i]; }
                case "--access-token" -> { if (i + 1 < args.length) accessTokenArg = args[++i]; }
                case "--qq-id" -> { if (i + 1 < args.length) qqIdArg = args[++i]; }
                case "--backend" -> { if (i + 1 < args.length) backendArg = args[++i]; }
                case "--instance" -> { if (i + 1 < args.length) instanceArg = parseInstanceArg(args[++i]); }
                case "--plugin-dir" -> { if (i + 1 < args.length) config.pluginsDir = args[++i]; }
                case "--check-plugins" -> config.checkPlugins = true;
                case "--auto-launch" -> config.autoLaunch = true;
                case "--help" -> {
                    printHelp();
                    System.exit(0);
                }
                default -> { }
            }
        }

        // 后端与实例（多实例后端在交互式终端下启动前选择）
        if (backendArg != null) {
            resolver = resolver.withBackend(backendArg);
        }
        if (instanceArg != null) {
            resolver = resolver.withInstance(instanceArg);
        } else if (resolver.hasMultipleInstances() && System.console() != null) {
            resolver = selectInstance(resolver);
        }

        config.backend = resolver.backend();
        config.wsUrl = firstNotBlank(wsUrlArg, resolver.wsUrl());
        config.accessToken = firstNotBlank(accessTokenArg, resolver.accessToken());
        config.qqId = firstNotBlank(qqIdArg, resolver.qqId());
        config.instanceName = activeInstanceName(resolver);

        // 验证必要配置
        if (!config.checkPlugins && config.wsUrl.isEmpty()) {
            throw new IllegalArgumentException("缺少 WebSocket URL，请使用 --ws-url 参数或在.env 文件中配置 WS_URL");
        }
        
        return config;
    }

    /**
     * 解析 --instance 参数（从 1 开始，转换为 0 起的索引）。
     */
    private static int parseInstanceArg(String raw) {
        try {
            int index = Integer.parseInt(raw.trim()) - 1;
            if (index < 0) {
                throw new IllegalArgumentException("--instance 需要从 1 开始的实例序号：" + raw);
            }
            return index;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--instance 需要从 1 开始的实例序号：" + raw, e);
        }
    }

    /**
     * 多实例后端启动前交互式选择实例。
     */
    private static AdapterResolver selectInstance(AdapterResolver resolver) {
        List<AdapterResolver.Instance> instances = resolver.instances();
        int active = resolver.activeIndex();

        System.out.println();
        System.out.println("=== " + resolver.backend().displayName() + " 实例选择 ===");
        for (int i = 0; i < instances.size(); i++) {
            AdapterResolver.Instance instance = instances.get(i);
            String address = instance.wsUrl() != null ? instance.wsUrl() : resolver.backend().defaultWsUrl();
            System.out.println("  " + (i + 1) + ". " + instance.name() + " (" + address + ")"
                    + (i == active ? " [当前]" : ""));
        }
        System.out.println("  0. 直接启动（使用当前实例）");
        System.out.println();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("请选择实例 [0-" + instances.size() + "，默认 0]: ");
            String line;
            try {
                line = reader.readLine();
            } catch (IOException e) {
                return resolver;
            }
            if (line == null || line.isBlank() || "0".equals(line.trim())) {
                System.out.println("使用当前实例：" + instances.get(active).name());
                return resolver;
            }
            try {
                int index = Integer.parseInt(line.trim()) - 1;
                if (index >= 0 && index < instances.size()) {
                    System.out.println("已选择：" + instances.get(index).name());
                    return resolver.withInstance(index);
                }
            } catch (NumberFormatException ignored) {
                // 统一在下方提示
            }
            System.out.println("请输入 0-" + instances.size() + " 范围内的数字");
        }
    }

    /**
     * 当前激活实例名称，未配置实例时为 null。
     */
    private static String activeInstanceName(AdapterResolver resolver) {
        List<AdapterResolver.Instance> instances = resolver.instances();
        int index = resolver.activeIndex();
        return (index >= 0 && index < instances.size()) ? instances.get(index).name() : null;
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
        System.out.println("  --backend <name>       协议端后端：napcat / snowluma（默认 napcat）");
        System.out.println("  --instance <n>         多实例后端选择第 n 个实例（从 1 开始，如 SnowLuma 多开）");
        System.out.println("  --ws-url <url>         已有 WebSocket 服务器地址");
        System.out.println("  --access-token <token> 访问令牌");
        System.out.println("  --qq-id <qq>           机器人 QQ 号");
        System.out.println("  --plugin-dir <dir>     插件目录 (默认：plugins)");
        System.out.println("  --check-plugins        只检查插件自动发现和注册，不连接 WebSocket");
        System.out.println("  --auto-launch          启用 NapCat 自动启动（连接失败时自动启动根目录 NapCat，仅 NapCat 后端）");
        System.out.println("  --help                 显示帮助信息");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java -jar rqhbot.jar --backend napcat --ws-url ws://127.0.0.1:3002 --access-token mytoken");
        System.out.println("  java -jar rqhbot.jar --backend snowluma --instance 2");
        System.out.println();
        System.out.println("环境变量 (可选):");
        System.out.println("  BACKEND        协议端后端 napcat / snowluma（默认 napcat）");
        System.out.println("  WS_URL         已有 WebSocket 地址（缺省按 BACKEND 回落到后端默认地址）");
        System.out.println("  ACCESS_TOKEN   访问令牌");
        System.out.println("  QQ_ID          机器人 QQ 号");
        System.out.println("  后端专属键优先于通用键，如 NAPCAT_WS_URL / SNOWLUMA_WS_URL、");
        System.out.println("  NAPCAT_ACCESS_TOKEN / SNOWLUMA_ACCESS_TOKEN、NAPCAT_QQ_ID / SNOWLUMA_QQ_ID");
        System.out.println("  多实例（如 SnowLuma 多开）：");
        System.out.println("    SNOWLUMA_INSTANCE_1_NAME / SNOWLUMA_INSTANCE_1_WS_URL / SNOWLUMA_INSTANCE_1_ACCESS_TOKEN");
        System.out.println("    SNOWLUMA_ACTIVE=0   选择当前实例（0 起）");
        System.out.println();
        System.out.println("连接 SnowLuma：");
        System.out.println("  1) 在 SnowLuma WebUI 开启「正向 WebSocket 服务端」(wsServers)，默认端口 3001");
        System.out.println("  2) 设置 BACKEND=snowluma 与 SNOWLUMA_WS_URL=ws://127.0.0.1:3001（或 WS_URL）");
        System.out.println("  3) 不需要 --auto-launch（SnowLuma 实例由 SnowLuma 自行启动）");
    }
    
    /**
     * 配置类
     */
    private static class Config {
        String wsUrl = "";
        String accessToken = "";
        String qqId = "";
        String pluginsDir = "plugins";
        String instanceName = null;
        BackendSpec backend = null;
        boolean checkPlugins = false;
        boolean autoLaunch = false;
    }
}
