package com.rqhbot.sdk.pluginsystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rqhbot.sdk.BotClient;
import com.rqhbot.sdk.BotExecutor;

/**
 * 插件基类 - 所有插件必须继承此类
 */
public abstract class PluginBase {
    
    protected static final Logger logger = LoggerFactory.getLogger(PluginBase.class);
    
    protected String name;
    protected String version = "1.0.0";
    protected String description = "";
    protected String author = "Unknown";
    protected boolean enabled = true;
    
    private final List<CompletableFuture<?>> tasks = new ArrayList<>();
    
    public PluginBase() {
        this.name = this.getClass().getSimpleName();
    }
    
    /**
     * 插件加载时调用
     */
    public abstract CompletableFuture<Void> onLoad(BotClient bot);
    
    /**
     * 插件卸载时调用
     */
    public abstract CompletableFuture<Void> onUnload(BotClient bot);
    
    /**
     * 消息事件处理
     */
    public CompletableFuture<Void> onMessage(BotClient bot, Map<String, Object> message) {
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * 通知事件处理
     */
    public CompletableFuture<Void> onNotice(BotClient bot, Map<String, Object> notice) {
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * 请求事件处理
     */
    public CompletableFuture<Void> onRequest(BotClient bot, Map<String, Object> request) {
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * 创建后台任务
     */
    protected void createTask(CompletableFuture<?> task) {
        tasks.add(task);
    }
    
    /**
     * 获取所有后台任务
     */
    protected List<CompletableFuture<?>> getTasks() {
        return new ArrayList<>(tasks);
    }
    
    /**
     * 清空已完成的后台任务
     */
    protected void clearCompletedTasks() {
        tasks.removeIf(CompletableFuture::isDone);
    }
    
    /**
     * 等待所有后台任务完成
     */
    protected CompletableFuture<Void> waitForAllTasks() {
        CompletableFuture<?>[] futures = tasks.toArray(new CompletableFuture<?>[0]);
        return CompletableFuture.allOf(futures);
    }
    
    /**
     * 异步延迟
     */
    protected CompletableFuture<Void> delay(long seconds) {
        return BotExecutor.async(() -> {
            try {
                Thread.sleep(seconds * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
    
    /**
     * 获取插件名称
     */
    public String getName() {
        return name;
    }
    
    /**
     * 获取插件版本
     */
    public String getVersion() {
        return version;
    }
    
    /**
     * 获取插件描述
     */
    public String getDescription() {
        return description;
    }
    
    /**
     * 获取插件作者
     */
    public String getAuthor() {
        return author;
    }
    
    /**
     * 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * 设置启用状态
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
