package com.pahappa.musicplayer.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Download work runs on its own pool so that a long {@code yt-dlp} invocation
 * never occupies an HTTP worker thread.
 */
@Configuration(proxyBeanMethods = false)
public class AsyncConfig {

    /**
     * Bounded executor: threads == max concurrent downloads, so back-pressure is
     * handled by the queue rather than by spawning unbounded OS processes.
     */
    @Bean(name = "downloadExecutor")
    public Executor downloadExecutor(AppProperties properties) {
        int threads = properties.download().maxConcurrent();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("dl-");
        // Reject instead of running on the caller: the service layer turns a
        // rejection into a clean "queue full" response for the client.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
