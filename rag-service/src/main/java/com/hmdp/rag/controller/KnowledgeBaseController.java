package com.hmdp.rag.controller;

import com.hmdp.dto.Result;
import com.hmdp.rag.dto.CreateKbRequest;
import com.hmdp.rag.entity.KnowledgeBase;
import com.hmdp.rag.service.IKnowledgeBaseService;
import com.hmdp.utils.UserHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rag/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final IKnowledgeBaseService kbService;

    @PostMapping
    public Result create(@Valid @RequestBody CreateKbRequest request) {
        Long userId = UserHolder.getUser().getId();
        KnowledgeBase kb = kbService.create(request, userId);
        return Result.ok(kb);
    }

    @GetMapping("/{id}")
    public Result getById(@PathVariable Long id) {
        return Result.ok(kbService.getById(id));
    }

    @GetMapping
    public Result list(@RequestParam Long merchantId,
                       @RequestParam(defaultValue = "1") int pageNum,
                       @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(kbService.listByMerchant(merchantId, pageNum, pageSize));
    }

    @PutMapping("/{id}")
    public Result update(@PathVariable Long id, @Valid @RequestBody CreateKbRequest request) {
        Long userId = UserHolder.getUser().getId();
        return Result.ok(kbService.update(id, request, userId));
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        kbService.delete(id);
        return Result.ok();
    }
}
