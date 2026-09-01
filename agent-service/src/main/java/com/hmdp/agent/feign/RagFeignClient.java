package com.hmdp.agent.feign;

import com.hmdp.agent.config.FeignAuthConfig;
import com.hmdp.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * rag-service 内部检索客户端（FR-07 扩展，D1.4 C4；Nacos 服务发现直连，不经网关）
 */
@FeignClient(name = "rag-service", configuration = FeignAuthConfig.class, contextId = "ragInternal")
public interface RagFeignClient {

    @PostMapping("/internal/rag/retrieval/search")
    Result search(@RequestBody Map<String, Object> body);
}
