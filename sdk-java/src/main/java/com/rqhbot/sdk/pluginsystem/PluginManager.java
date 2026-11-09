package com.rqhbot.sdk.pluginsystem;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rqhbot.sdk.BotClient;
import com.rqhbot.sdk.BotExecutor;

/**
 * 插件管理器
 */
public class PluginManager {
    
    private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS_PATTERN = Pattern.compile("(?m)^\\s*public\\s+class\\s+(\\w+)");
    
    private final BotClient bot;
    private final Map<String, PluginBase> plugins = new ConcurrentHashMap<>();
    
    public PluginManager(BotClient bot) {
        this.bot = bot;
    }
    
    /**
     * 注册插件
     */
    public boolean registerPlugin(PluginBase plugin) {
        String pluginName = plugin.getName().toLowerCase();
        
        if (plugins.containsKey(pluginName)) {
            logger.warn("插件 {} 已注册", pluginName);
            return false;
        }
        
        plugins.put(pluginName, plugin);
        logger.info("注册插件：{}", pluginName);
        
        bindPluginEvents(plugin);
        
        // 异步调用 on_load
        BotExecutor.async(() -> {
            try {
                plugin.onLoad(bot).join();
            } catch (Exception e) {
                logger.error("插件 {} 加载失败：{}", pluginName, e.getMessage(), e);
            }
        });
        
        return true;
    }
    
    /**
     * 注销插件
     */
    public boolean unregisterPlugin(String pluginName) {
        pluginName = pluginName.toLowerCase();
        
        if (!plugins.containsKey(pluginName)) {
            logger.warn("插件 {} 未注册", pluginName);
            return false;
        }
        
        PluginBase plugin = plugins.get(pluginName);
        final String finalPluginName = pluginName; // 创建最终变量用于 lambda
        
        // 异步调用 on_unload
        BotExecutor.async(() -> {
            try {
                plugin.onUnload(bot).join();
            } catch (Exception e) {
                logger.error("插件 {} 卸载失败：{}", finalPluginName, e.getMessage(), e);
            }
        });
        
        plugins.remove(pluginName);
        
        logger.info("注销插件：{}", pluginName);
        return true;
    }
    
    /**
     * 绑定插件事件处理器。
     *
     * <p>事件已由 {@code NapCatClient} 派发到统一线程池，此处直接调用插件回调，
     * 避免再套一层异步后 {@code join()} 造成线程浪费与潜在死锁。</p>
     */
    private void bindPluginEvents(PluginBase plugin) {
        bot.getClient().onMessage("group", data -> safely(plugin, "群消息", () -> plugin.onMessage(bot, data)));
        bot.getClient().onMessage("private", data -> safely(plugin, "私聊消息", () -> plugin.onMessage(bot, data)));
        bot.getClient().onMessage("notice", data -> safely(plugin, "通知", () -> plugin.onNotice(bot, data)));
        bot.getClient().onMessage("request", data -> safely(plugin, "请求", () -> plugin.onRequest(bot, data)));
    }

    /**
     * 安全执行插件回调：捕获同步异常并接管异步异常。
     */
    private CompletableFuture<Void> safely(
            PluginBase plugin,
            String event,
            java.util.concurrent.Callable<CompletableFuture<Void>> action
    ) {
        if (!plugin.isEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            CompletableFuture<Void> future = action.call();
            if (future == null) {
                return CompletableFuture.completedFuture(null);
            }
            return future.exceptionally(ex -> {
                logger.error("插件 {} 处理{}失败：{}", plugin.getName(), event, ex.getMessage());
                return null;
            });
        } catch (Exception e) {
            logger.error("插件 {} 处理{}失败：{}", plugin.getName(), event, e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }
    
    /**
     * 从 Java 源码文件对应的已编译类加载插件。
     */
    public PluginBase loadPluginFromFile(Path pluginPath) {
        try {
            String source = Files.readString(pluginPath);
            Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
            Matcher classMatcher = CLASS_PATTERN.matcher(source);
            if (!packageMatcher.find() || !classMatcher.find()) {
                return null;
            }

            String className = packageMatcher.group(1) + "." + classMatcher.group(1);
            Class<?> candidate = Class.forName(className);
            if (!PluginBase.class.isAssignableFrom(candidate) || Modifier.isAbstract(candidate.getModifiers())) {
                return null;
            }

            return (PluginBase) candidate.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | IOException | LinkageError e) {
            logger.warn("加载插件文件 {} 失败：{}", pluginPath, e.getMessage());
            return null;
        }
    }
    
    /**
     * 从目录异步加载所有插件
     */
    public CompletableFuture<List<String>> loadPluginsFromDirAsync(String pluginsDir) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> loaded = new ArrayList<>();
            Path pluginsPath = Path.of(pluginsDir);
            
            if (!Files.exists(pluginsPath)) {
                logger.warn("插件目录不存在：{}", pluginsDir);
                return loaded;
            }
            
            try (var stream = Files.walk(pluginsPath)) {
                stream.filter(path -> path.toString().endsWith(".java"))
                    .forEach(pluginFile -> {
                        try {
                            PluginBase plugin = loadPluginFromFile(pluginFile);
                            if (plugin != null) {
                                if (registerPlugin(plugin)) {
                                    loaded.add(plugin.getName());
                                }
                            }
                        } catch (Exception e) {
                            logger.error("加载插件失败 {}: {}", pluginFile, e.getMessage());
                        }
                    });
            } catch (IOException e) {
                logger.error("扫描插件目录失败：{}", e.getMessage(), e);
            }
            
            return loaded;
        });
    }
    
    /**
     * 获取所有已注册的插件
     */
    public Map<String, PluginBase> getAllPlugins() {
        return Collections.unmodifiableMap(plugins);
    }
    
    /**
     * 获取指定插件
     */
    public PluginBase getPlugin(String pluginName) {
        return plugins.get(pluginName.toLowerCase());
    }
    
    /**
     * 卸载插件
     */
    public boolean unloadPlugin(String pluginName) {
        return unregisterPlugin(pluginName);
    }
}
