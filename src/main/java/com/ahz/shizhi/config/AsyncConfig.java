package com.ahz.shizhi.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 流式对话的线程资源。
 *
 * <p>模型调用是**阻塞**的（一答可能占满 180 秒），绝不能跑在 Tomcat 的请求线程或
 * 默认的 {@code applicationTaskExecutor} 上：前者会耗尽 web 线程，后者会让别的
 * {@code @Async} 任务一起排队。给它一条独立的池，最多 8 个并发对话，
 * 超出的请求留在队列里等——比直接拒绝更符合"个人项目、自己用"的实际情况。
 */
@Configuration
public class AsyncConfig {

    public static final String CHAT_STREAM_EXECUTOR = "chatStreamExecutor";
    public static final String CHAT_SCHEDULER = "chatScheduler";

    @Bean(name = CHAT_STREAM_EXECUTOR)
    public ThreadPoolTaskExecutor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("chat-stream-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * 心跳与模型看门狗共用。都是"每几十毫秒到十几秒戳一下"的轻任务，两个线程足够。
     * 用守护线程，进程退出时不阻塞 JVM。
     */
    @Bean(name = CHAT_SCHEDULER, destroyMethod = "shutdownNow")
    public ScheduledExecutorService chatScheduler() {
        return Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "chat-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }
}
