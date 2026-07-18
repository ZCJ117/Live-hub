package com.hmdp.rag.controller;

import com.hmdp.rag.dto.ChatRequest;
import com.hmdp.rag.pipeline.QaPipeline;
import com.hmdp.rag.service.IAuditService;
import com.hmdp.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api/rag/qa")
@RequiredArgsConstructor
@Slf4j
public class QaController {

    private final QaPipeline qaPipeline;
    private final IAuditService auditService;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@Valid @RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        Long userId = UserHolder.getUser().getId();
        String ip = httpRequest.getRemoteAddr();

        auditService.log(userId, "QUERY", "KB",
                request.getKbId(), Map.of("question", request.getQuestion()), ip);

        Flux<String> answer = qaPipeline.answer(
                request.getQuestion(),
                request.getKbId(),
                request.getTopK(),
                request.getEnableRerank(),
                request.getMessages()
        );

        return Flux.just("event: thinking\ndata: {\"type\":\"thinking\",\"content\":\"正在检索相关知识...\"}\n\n")
                .concatWith(answer)
                .concatWith(Flux.just("event: done\ndata: {\"type\":\"done\"}\n\n"))
                .doOnComplete(() -> log.info("QA completed: userId={}, question={}", userId, request.getQuestion()))
                .doOnError(e -> log.error("QA error: {}", e.getMessage(), e));
    }
}
