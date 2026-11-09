package com.rqhbot.sdk;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局有界业务线程池。
 *
 * <p>统一承载 SDK 与插件的异步任务，替代 {@code ForkJoinPool.commonPool()}。
 * 插件逻辑大量涉及阻塞式 IO（HTTP 请求、JSON 文件读写、{@code join()}），
 * 若继续占用公共池会拖垮整个 JVM 的并行流/异步任务调度；
 * 使用独立的有界线程池可以让并发受控、线程可命名、便于排查。</p>
 */
public final class BotExecutor {

    /** 线程数：CPU 核数 * 2，至少 8，保证有界的同时兼顾 IO 等待。 */
    private static final int POOL_SIZE = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);

    private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {
        private final AtomicInteger index = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "rqhbot-worker-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(POOL_SIZE, THREAD_FACTORY);

    private BotExecutor() {
    }

    /**
     * 异步执行任务，返回与任务绑定的 {@link CompletableFuture}。
     */
    public static CompletableFuture<Void> async(Runnable task) {
        return CompletableFuture.runAsync(task, POOL);
    }

    /**
     * 直接提交任务，不关心返回值与异常传播。
     */
    public static void execute(Runnable task) {
        POOL.execute(task);
    }

    /**
     * 暴露底层线程池，供需要自定义组合的场景使用。
     */
    public static ExecutorService pool() {
        return POOL;
    }

    /**
     * 关闭线程池，进程退出前调用。
     */
    public static void shutdown() {
        POOL.shutdown();
        try {
            if (!POOL.awaitTermination(5, TimeUnit.SECONDS)) {
                POOL.shutdownNow();
            }
        } catch (InterruptedException e) {
            POOL.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
