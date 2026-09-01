package com.hmdp.agent.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * SSE 异步链路线程池（D1.2 §2.1）
 * 严禁在 Tomcat 工作线程上同步阻塞等待 LLM 响应（PRD 4.1 性能设计约束）
 */
@Configuration
@RequiredArgsConstructor
public class SseExecutorConfig {

    public static final String SSE_EXECUTOR = "agentSseExecutor";

    @Bean(name = SSE_EXECUTOR)
    public Executor agentSseExecutor(AgentProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getSse().getCorePoolSize());
        executor.setMaxPoolSize(props.getSse().getMaxPoolSize());
        executor.setQueueCapacity(props.getSse().getQueueCapacity());
        executor.setThreadNamePrefix("agent-sse-");
        // 拒绝策略：快速失败，由上层转 429 语义友好提示（D1.6 §2）
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
