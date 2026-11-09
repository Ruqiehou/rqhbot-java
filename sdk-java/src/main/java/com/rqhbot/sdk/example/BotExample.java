package com.rqhbot.sdk.example;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rqhbot.sdk.BotClient;
import com.rqhbot.sdk.pluginsystem.PluginBase;

/**
 * SDK 使用示例
 */
public class BotExample {
    
    private static final Logger logger = LoggerFactory.getLogger(BotExample.class);
    
    public static void main(String[] args) {
        // 创建机器人客户端
        BotClient bot = new BotClient();
        
        // 注册命令 - 测试命令
        bot.command("测试")
            .handler((event, match) -> {
                logger.info("收到测试命令");
                return CompletableFuture.completedFuture(null);
            });
        
        // 注册命令 - hello 命令
        bot.command("hello")
            .handler((event, match) -> {
                logger.info("收到 hello 命令");
                return CompletableFuture.completedFuture(null);
            });
        
        // 注册命令 - help 命令（带前缀）
        bot.command("help")
            .prefix("/")
            .handler((event, match) -> {
                logger.info("收到 help 命令");
                return CompletableFuture.completedFuture(null);
            });
        
        // 群消息事件监听
        bot.onGroupMessage(data -> {
            logger.info("监听到群消息：{}", data);
            return CompletableFuture.completedFuture(null);
        });
        
        // 私聊消息事件监听
        bot.onPrivateMessage(data -> {
            logger.info("监听到私聊消息：{}", data);
            return CompletableFuture.completedFuture(null);
        });
        
        // 注册自定义插件示例
        bot.registerPlugin(new PluginBase() {
            @Override
            public CompletableFuture<Void> onLoad(BotClient bot) {
                logger.info("自定义插件已加载");
                return CompletableFuture.completedFuture(null);
            }
            
            @Override
            public CompletableFuture<Void> onUnload(BotClient bot) {
                logger.info("自定义插件已卸载");
                return CompletableFuture.completedFuture(null);
            }
            
            @Override
            public CompletableFuture<Void> onMessage(BotClient bot, Map<String, Object> message) {
                // 处理所有消息
                return CompletableFuture.completedFuture(null);
            }
        });
        
        // 启动机器人
        logger.info("正在启动机器人...");
        bot.start(true, true);
    }
}
