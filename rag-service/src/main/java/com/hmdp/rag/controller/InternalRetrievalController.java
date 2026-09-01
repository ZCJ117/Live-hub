package com.hmdp.rag.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.dto.Result;
import com.hmdp.rag.dto.RetrievedChunk;
import com.hmdp.rag.entity.KnowledgeBase;
import com.hmdp.rag.retriever.HybridRetriever;
import com.hmdp.rag.service.IKnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 内部检索 API（D1.4 C4，Phase 3 T3.11）
 * 仅供 agent-service 经 Nacos 服务发现直连（不经网关暴露）；仅检索不生成（GLM 由 agent-service 完成）
 * 降级原则：任何异常/无知识库 → 空 hits，由调用方按"仅结构化字段"处理
 */
@RestController
@RequestMapping("/internal/rag")
@RequiredArgsConstructor
@Slf4j
public class InternalRetrievalController {

    private final IKnowledgeBaseService kbService;
    private final HybridRetriever hybridRetriever;

    @PostMapping("/retrieval/search")
    public Result search(@RequestBody Map<String, Object> body) {
        try {
            Long shopId = toLong(body.get("shopId"));
            String query = body.get("query") == null ? null : String.valueOf(body.get("query"));
            int topK = body.get("topK") == null ? 3 : Integer.parseInt(String.valueOf(body.get("topK")));
            if (shopId == null || query == null || query.isBlank()) {
                return Result.ok(Map.of("hits", List.of()));
            }
            // KB 定位约定：merchantId == shopId（无 KB 返回空，不报错）
            KnowledgeBase kb = kbService.getOne(Wrappers.<KnowledgeBase>lambdaQuery()
                    .eq(KnowledgeBase::getMerchantId, shopId)
                    .orderByDesc(KnowledgeBase::getId)
                    .last("LIMIT 1"));
            if (kb == null) {
                return Result.ok(Map.of("hits", List.of()));
            }
            List<RetrievedChunk> chunks = hybridRetriever.retrieve(query, kb.getId(), topK);
            List<Map<String, Object>> hits = chunks.stream()
                    .map(c -> Map.<String, Object>of(
                            "content", c.getContent() == null ? "" : c.getContent(),
                            "score", c.getScore() == null ? 0.0 : c.getScore(),
                            "kbId", kb.getId()))
                    .toList();
            return Result.ok(Map.of("hits", hits));
        } catch (Exception e) {
            log.warn("内部检索失败，空结果降级: {}", body, e);
            return Result.ok(Map.of("hits", List.of()));
        }
    }

    private Long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try {
            return v == null ? null : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
