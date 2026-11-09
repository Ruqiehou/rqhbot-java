package com.rqhbot.sdk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rqhbot.sdk.events.GroupMessageEvent;
import com.rqhbot.sdk.events.PrivateMessageEvent;
import com.rqhbot.sdk.interfaces.MessageHandler;
import com.rqhbot.sdk.pluginsystem.PluginManager;

/**
 * 机器人客户端 - 支持装饰器模式
 */
public class BotClient {
    
    private static final Logger logger = LoggerFactory.getLogger(BotClient.class);
    
    private final NapCatClient client;
    private final BotAPI api;
    private final String pluginsDir;
    private final PluginManager pluginManager;
    
    // 事件处理器列表
    private final List<MessageHandler> groupMessageHandlers = new ArrayList<>();
    private final List<MessageHandler> privateMessageHandlers = new ArrayList<>();
    private final List<MessageHandler> noticeHandlers = new ArrayList<>();
    private final List<MessageHandler> requestHandlers = new ArrayList<>();
    
    // 命令注册表
    private final List<CommandRegistration> commands = new ArrayList<>();
    
    public BotClient() {
        this(null, null, "plugins");
    }
    
    @SuppressWarnings("this-escape")
    public BotClient(String wsUrl, String accessToken, String pluginsDir) {
        this(wsUrl, accessToken, null, pluginsDir);
    }
    
    @SuppressWarnings("this-escape")
    public BotClient(String wsUrl, String accessToken, String qqId, String pluginsDir) {
        this.client = new NapCatClient(wsUrl, accessToken, qqId);
        this.api = new BotAPI(this.client);
        this.pluginsDir = pluginsDir;
        this.pluginManager = new PluginManager(this);
        
        setupHandlers();
    }
    
    /**
     * 设置底层事件处理器
     */
    private void setupHandlers() {
        // 群消息处理器
        client.onMessage("group", (MessageHandler) data -> {
            try {
                GroupMessageEvent event = GroupMessageEvent.fromMap(data);
                GroupMessageEvent finalEvent = setBotOnEvent(event, this);

                printGroupMessage(finalEvent);

                for (MessageHandler handler : groupMessageHandlers) {
                    dispatchHandler(handler, data, "群消息");
                }

                processCommands(finalEvent, true);

            } catch (Exception e) {
                logger.error("处理群消息时出错：{}", e.getMessage(), e);
            }
            return CompletableFuture.completedFuture(null);
        });
        
        // 私聊消息处理器
        client.onMessage("private", (MessageHandler) data -> {
            try {
                PrivateMessageEvent event = PrivateMessageEvent.fromMap(data);
                PrivateMessageEvent finalEvent = setBotOnEvent(event, this);

                printPrivateMessage(finalEvent);

                for (MessageHandler handler : privateMessageHandlers) {
                    dispatchHandler(handler, data, "私聊消息");
                }

                processCommands(finalEvent, false);

            } catch (Exception e) {
                logger.error("处理私聊消息时出错：{}", e.getMessage(), e);
            }
            return CompletableFuture.completedFuture(null);
        });
        
        // 通知事件处理器
        client.onMessage("notice", (MessageHandler) data -> {
            for (MessageHandler handler : noticeHandlers) {
                dispatchHandler(handler, data, "通知");
            }
            return CompletableFuture.completedFuture(null);
        });
        
        // 请求事件处理器
        client.onMessage("request", (MessageHandler) data -> {
            for (MessageHandler handler : requestHandlers) {
                dispatchHandler(handler, data, "请求");
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    /**
     * 将处理器投递到统一有界线程池执行，异常只记录不向上抛出。
     */
    private void dispatchHandler(MessageHandler handler, Map<String, Object> data, String kind) {
        BotExecutor.execute(() -> {
            try {
                handler.handle(data);
            } catch (Exception e) {
                logger.error("{}处理器执行失败：{}", kind, e.getMessage());
            }
        });
    }
    
    @SuppressWarnings("unchecked")
    private <T> T setBotOnEvent(T event, BotClient bot) {
        return switch (event) {
            case GroupMessageEvent gme -> 
                (T) new GroupMessageEvent(
                    gme.time(), gme.selfId(), gme.postType(), gme.messageType(),
                    gme.subType(), gme.messageId(), gme.groupId(), gme.userId(),
                    gme.userName(), gme.message(), gme.rawMessage(), gme.font(),
                    gme.sender(), bot
                );
            case PrivateMessageEvent pme -> 
                (T) new PrivateMessageEvent(
                    pme.time(), pme.selfId(), pme.postType(), pme.messageType(),
                    pme.subType(), pme.messageId(), pme.userId(), pme.userName(),
                    pme.message(), pme.rawMessage(), pme.font(), bot
                );
            default -> event;
        };
    }
    
    /**
     * 处理命令
     */
    private void processCommands(Object event, boolean isGroup) {
        String text = extractMessageText(event);
        if (text == null || text.trim().isEmpty()) return;
        
        text = text.trim();
        
        for (CommandRegistration cmd : commands) {
            if (cmd.groupOnly && !isGroup) continue;
            
            Matcher matcher = cmd.pattern.matcher(text);
            if (matcher.matches()) {
                BotExecutor.execute(() -> {
                    try {
                        CompletableFuture<Void> future = cmd.handler.handle(event, matcher);
                        if (future != null) {
                            future.exceptionally(ex -> {
                                logger.error("命令 '{}' 执行失败：{}", cmd.keyword, ex.getMessage());
                                return null;
                            });
                        }
                    } catch (Exception e) {
                        logger.error("命令 '{}' 执行失败：{}", cmd.keyword, e.getMessage());
                    }
                });
                break;
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    private String extractMessageText(Object event) {
        return switch (event) {
            case GroupMessageEvent gme -> gme.message().plainText();
            case PrivateMessageEvent pme -> pme.message().plainText();
            default -> "";
        };
    }
    
    private void printGroupMessage(GroupMessageEvent event) {
        logger.info("[群消息] 群:{} 用户:{}({}): {}", 
            event.groupId(), event.userName(), event.userId(), 
            event.message().plainText());
    }
    
    private void printPrivateMessage(PrivateMessageEvent event) {
        logger.info("[私聊] 用户:{}({}): {}", 
            event.userName(), event.userId(), 
            event.message().plainText());
    }
    
    /**
     * 命令装饰器
     */
    public CommandBuilder command(String keyword) {
        return new CommandBuilder(keyword);
    }
    
    /**
     * 群消息事件装饰器
     */
    public void onGroupMessage(MessageHandler handler) {
        groupMessageHandlers.add(handler);
    }
    
    /**
     * 私聊消息事件装饰器
     */
    public void onPrivateMessage(MessageHandler handler) {
        privateMessageHandlers.add(handler);
    }
    
    /**
     * 通知事件装饰器
     */
    public void onNotice(MessageHandler handler) {
        noticeHandlers.add(handler);
    }
    
    /**
     * 请求事件装饰器
     */
    public void onRequest(MessageHandler handler) {
        requestHandlers.add(handler);
    }
    
    /**
     * 注册插件
     */
    public boolean registerPlugin(com.rqhbot.sdk.pluginsystem.PluginBase plugin) {
        return pluginManager.registerPlugin(plugin);
    }

    /**
     * 获取插件管理器
     */
    public PluginManager getPluginManager() {
        return pluginManager;
    }
    
    /**
     * 加载插件
     */
    public CompletableFuture<List<String>> loadPlugins() {
        return pluginManager.loadPluginsFromDirAsync(pluginsDir);
    }
    
    /**
     * 运行前端（异步版本）
     */
    public CompletableFuture<Void> runFrontend(boolean debug, boolean loadPlugins) {
        return client.connect(null).thenCompose(connected -> {
            if (!connected) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                future.completeExceptionally(new RuntimeException("连接失败"));
                return future;
            }
            
            if (debug) {
                logger.info("调试模式已开启");
            }
            
            CompletableFuture<List<String>> pluginFuture = loadPlugins
                ? loadPlugins()
                : CompletableFuture.completedFuture(List.of());
            
            return pluginFuture.thenCompose(loaded -> {
                if (loadPlugins) {
                    logger.info("成功加载 {} 个插件", loaded.size());
                }
                
                logger.info("==================================================");
                logger.info("机器人已启动");
                logger.info("==================================================");
                logger.info("注册命令数量：{}", commands.size());
                logger.info("已加载插件数量：{}", pluginManager.getAllPlugins().size());
                logger.info("==================================================");
                logger.info("等待消息中...");
                logger.info("==================================================");

                // 保活：使用独立守护线程等待关闭，不占用业务线程池
                CompletableFuture<Void> keepAlive = new CompletableFuture<>();
                Thread keepAliveThread = new Thread(() -> {
                    try {
                        while (!Thread.currentThread().isInterrupted()) {
                            Thread.sleep(1000);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.info("收到中断信号，正在关闭...");
                    } finally {
                        keepAlive.complete(null);
                    }
                }, "rqhbot-keepalive");
                keepAliveThread.setDaemon(true);
                keepAliveThread.start();
                return keepAlive;
            });
        }).whenComplete((result, error) -> {
            if (error != null) {
                logger.error("发生错误：{}", error.getMessage(), error);
            }
            client.disconnect(false).join();
            logger.info("机器人已关闭");
        });
    }
    
    /**
     * 启动机器人（阻塞版本）
     */
    public void start(boolean debug, boolean loadPlugins) {
        try {
            runFrontend(debug, loadPlugins).join();
        } catch (Exception e) {
            logger.error("发生错误：{}", e.getMessage(), e);
            System.exit(1);
        }
    }
    
    /**
     * 获取 API 实例
     */
    public BotAPI getApi() {
        return api;
    }

    /**
     * 发送群消息，供事件对象快捷回复使用。
     */
    public CompletableFuture<Map<String, Object>> sendGroupMessage(long groupId, String message) {
        return api.sendGroupMessage(groupId, message);
    }

    /**
     * 发送私聊消息，供事件对象快捷回复使用。
     */
    public CompletableFuture<Map<String, Object>> sendPrivateMessage(long userId, String message) {
        return api.sendPrivateMessage(userId, message);
    }
    
    /**
     * 获取客户端实例
     */
    public NapCatClient getClient() {
        return client;
    }

    /**
     * 获取已注册插件数量。
     */
    public int getLoadedPluginCount() {
        return pluginManager.getAllPlugins().size();
    }
    
    /**
     * 命令构建器
     */
    public class CommandBuilder {
        private final String keyword;
        private boolean groupOnly = false;
        private String prefix = "";
        
        public CommandBuilder(String keyword) {
            this.keyword = keyword;
        }
        
        public CommandBuilder groupOnly(boolean value) {
            this.groupOnly = value;
            return this;
        }
        
        public CommandBuilder prefix(String value) {
            this.prefix = value;
            return this;
        }
        
        public void handler(CommandHandler func) {
            String patternStr = "^" + Pattern.quote(prefix + keyword) + "(.*)$";
            Pattern pattern = Pattern.compile(patternStr);
            commands.add(new CommandRegistration(keyword, pattern, func, groupOnly));
        }
    }
    
    /**
     * 命令处理器函数式接口
     */
    @FunctionalInterface
    public interface CommandHandler {
        CompletableFuture<Void> handle(Object event, Matcher match);
    }
    
    /**
     * 命令注册记录
     */
    private record CommandRegistration(
        String keyword,
        Pattern pattern,
        CommandHandler handler,
        boolean groupOnly
    ) {}
}
