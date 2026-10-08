package com.hmdp.social.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Feed 写扩散线程池（SPEC-09 §5.4）。
 */
@Configuration
public class FeedFanOutConfig {

    /**
     * 写扩散专用线程池。
     *
     * <p>{@link ThreadPoolExecutor.CallerRunsPolicy} 是刻意取舍：队列满时退化为调用方线程
     * 同步执行（发布变慢），但**绝不丢弃**任何粉丝的推送。丢动态是静默的数据丢失，
     * 慢是可观测的性能退化，两者不对等。
     */
    @Bean("feedFanOutExecutor")
    public ThreadPoolTaskExecutor feedFanOutExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("feed-fanout-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
