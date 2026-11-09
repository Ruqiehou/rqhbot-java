package com.rqhbot.sdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rqhbot.sdk.interfaces.IConnectionEventListener;

/**
 * NapCat 自动启动适配器
 * 
 * 功能：
 * 1. 监测 WebSocket 连接状态
 * 2. 连接失败时自动启动根目录的 NapCat
 * 3. 等待 NapCat 启动后重试连接
 */
public class NapCatAutoLauncher implements IConnectionEventListener {
    
    private static final Logger logger = LoggerFactory.getLogger(NapCatAutoLauncher.class);
    
    // NapCat 目录名
    private static final String NAPCAT_DIR = "napcat";
    // 启动脚本名称
    private static final String LAUNCHER_SCRIPT = "launcher.bat";
    // NapCat 启动等待时间（秒）
    private static final int NAPCAT_STARTUP_WAIT_SECONDS = 10;
    // 最大启动尝试次数
    private static final int MAX_LAUNCH_ATTEMPTS = 3;
    // 启动间隔（秒）
    private static final int LAUNCH_INTERVAL_SECONDS = 5;
    
    private final NapCatClient client;
    private final Path projectRoot;
    private final AtomicBoolean isLaunching = new AtomicBoolean(false);
    private final AtomicInteger launchAttempts = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private volatile Process napcatProcess = null;
    
    public NapCatAutoLauncher(NapCatClient client) {
        this.client = client;
        // 获取项目根目录（当前工作目录）
        this.projectRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        logger.info("NapCatAutoLauncher 初始化，项目根目录：{}", projectRoot);
    }
    
    @Override
    public CompletableFuture<Void> onConnected() {
        logger.info("WebSocket 连接成功，重置启动尝试计数");
        launchAttempts.set(0);
        isLaunching.set(false);
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> onDisconnected() {
        logger.info("WebSocket 连接断开");
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> onReconnecting(int attempt) {
        logger.info("正在重连 (第{}次)", attempt);
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> onReconnectSuccess() {
        logger.info("重连成功");
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> onReconnectFailed(Exception error) {
        logger.warn("重连失败：{}，尝试启动 NapCat...", error.getMessage());
        
        // 异步启动 NapCat
        BotExecutor.async(() -> {
            if (isLaunching.compareAndSet(false, true)) {
                try {
                    launchNapCatIfNeeded();
                } finally {
                    isLaunching.set(false);
                }
            } else {
                logger.info("NapCat 正在启动中，跳过重复启动");
            }
        });
        
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * 检测并启动 NapCat
     */
    private void launchNapCatIfNeeded() {
        int currentAttempt = launchAttempts.incrementAndGet();
        
        if (currentAttempt > MAX_LAUNCH_ATTEMPTS) {
            logger.error("NapCat 启动尝试次数已达上限 ({})，请手动检查", MAX_LAUNCH_ATTEMPTS);
            return;
        }
        
        logger.info("第 {} 次尝试启动 NapCat...", currentAttempt);
        
        // 1. 检查 NapCat 目录是否存在
        Path napcatDir = projectRoot.resolve(NAPCAT_DIR);
        if (!Files.exists(napcatDir) || !Files.isDirectory(napcatDir)) {
            logger.error("NapCat 目录不存在：{}", napcatDir);
            return;
        }
        
        // 2. 检查启动脚本是否存在
        Path launcherScript = napcatDir.resolve(LAUNCHER_SCRIPT);
        if (!Files.exists(launcherScript)) {
            logger.error("NapCat 启动脚本不存在：{}", launcherScript);
            // 尝试查找其他启动脚本
            launcherScript = findAlternativeLauncher(napcatDir);
            if (launcherScript == null) {
                logger.error("未找到可用的启动脚本");
                return;
            }
        }
        
        // 3. 先尝试关闭可能残留的 NapCat 进程
        killExistingNapCat();
        
        // 4. 执行启动脚本
        try {
            logger.info("执行启动脚本：{}", launcherScript);
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(napcatDir.toFile());
            pb.command("cmd", "/c", launcherScript.toString());
            pb.redirectErrorStream(true);
            
            napcatProcess = pb.start();
            
            // 读取并记录输出
            BotExecutor.async(() -> {
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(napcatProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.info("[NapCat] {}", line);
                    }
                } catch (IOException e) {
                    logger.error("读取 NapCat 输出失败：{}", e.getMessage());
                }
            });
            
            logger.info("NapCat 启动命令已执行，等待 {} 秒...", NAPCAT_STARTUP_WAIT_SECONDS);
            
            // 5. 等待 NapCat 启动
            scheduler.schedule(() -> {
                logger.info("等待 NapCat 启动完成，尝试重新连接...");
                // 触发重新连接
                client.connect(null).thenAccept(success -> {
                    if (success) {
                        logger.info("NapCat 启动成功，WebSocket 已重新连接");
                    } else {
                        logger.warn("NapCat 启动后连接失败，将在下次重连时重试");
                    }
                });
            }, NAPCAT_STARTUP_WAIT_SECONDS, TimeUnit.SECONDS);
            
        } catch (IOException e) {
            logger.error("启动 NapCat 失败：{}", e.getMessage(), e);
            // 安排重试
            if (currentAttempt < MAX_LAUNCH_ATTEMPTS) {
                logger.info("{} 秒后重试...", LAUNCH_INTERVAL_SECONDS);
                scheduler.schedule(this::launchNapCatIfNeeded, LAUNCH_INTERVAL_SECONDS, TimeUnit.SECONDS);
            }
        }
    }
    
    /**
     * 查找替代的启动脚本
     */
    private Path findAlternativeLauncher(Path napcatDir) {
        String[] alternativeScripts = {
            "launcher-win10.bat",
            "launcher-win10-user.bat", 
            "launcher-user.bat",
            "NapCatWinBootMain.exe"
        };
        
        for (String script : alternativeScripts) {
            Path scriptPath = napcatDir.resolve(script);
            if (Files.exists(scriptPath)) {
                logger.info("找到替代启动脚本：{}", scriptPath);
                return scriptPath;
            }
        }
        
        return null;
    }
    
    /**
     * 关闭可能残留的 NapCat 进程
     */
    private void killExistingNapCat() {
        try {
            logger.info("尝试关闭可能残留的 NapCat 进程...");
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("taskkill", "/F", "/IM", "NapCatWinBootMain.exe");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(5, TimeUnit.SECONDS);
            logger.info("已发送关闭命令");
        } catch (Exception e) {
            // 忽略错误，可能没有运行中的进程
            logger.debug("关闭 NapCat 进程时出错（可忽略）：{}", e.getMessage());
        }
    }
    
    /**
     * 检查 NapCat 是否正在运行
     */
    public boolean isNapCatRunning() {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("tasklist", "/FI", "IMAGENAME eq NapCatWinBootMain.exe");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("NapCatWinBootMain.exe")) {
                        return true;
                    }
                }
            }
            
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("检查 NapCat 进程状态失败：{}", e.getMessage());
        }
        return false;
    }
    
    /**
     * 手动启动 NapCat
     */
    public CompletableFuture<Boolean> manualLaunch() {
        return CompletableFuture.supplyAsync(() -> {
            if (isLaunching.compareAndSet(false, true)) {
                try {
                    launchNapCatIfNeeded();
                    return true;
                } finally {
                    isLaunching.set(false);
                }
            }
            return false;
        });
    }
    
    /**
     * 停止 NapCat 进程
     */
    public void stopNapCat() {
        killExistingNapCat();
        if (napcatProcess != null && napcatProcess.isAlive()) {
            napcatProcess.destroyForcibly();
            napcatProcess = null;
        }
    }
    
    /**
     * 获取 NapCat 目录路径
     */
    public Path getNapCatDir() {
        return projectRoot.resolve(NAPCAT_DIR);
    }
    
    /**
     * 检查 NapCat 目录和启动脚本是否存在
     */
    public boolean isNapCatAvailable() {
        Path napcatDir = projectRoot.resolve(NAPCAT_DIR);
        if (!Files.exists(napcatDir) || !Files.isDirectory(napcatDir)) {
            return false;
        }
        
        Path launcher = napcatDir.resolve(LAUNCHER_SCRIPT);
        if (Files.exists(launcher)) {
            return true;
        }
        
        // 检查替代脚本
        return findAlternativeLauncher(napcatDir) != null;
    }
    
    /**
     * 关闭调度器
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
