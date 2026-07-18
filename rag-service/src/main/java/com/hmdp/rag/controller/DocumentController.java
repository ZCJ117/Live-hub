package com.hmdp.rag.controller;

import com.hmdp.dto.Result;
import com.hmdp.rag.service.IDocumentService;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class DocumentController {

    private final IDocumentService documentService;

    @PostMapping("/knowledge-bases/{kbId}/documents")
    public Result upload(@PathVariable Long kbId,
                          @RequestParam("file") MultipartFile file) {
        Long userId = UserHolder.getUser().getId();
        return Result.ok(documentService.upload(file, kbId, userId));
    }

    @GetMapping("/knowledge-bases/{kbId}/documents")
    public Result list(@PathVariable Long kbId,
                       @RequestParam(defaultValue = "1") int pageNum,
                       @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(documentService.listByKb(kbId, pageNum, pageSize));
    }

    @GetMapping("/documents/{id}/status")
    public Result status(@PathVariable Long id) {
        return Result.ok(documentService.getStatus(id));
    }

    @DeleteMapping("/documents/{id}")
    public Result delete(@PathVariable Long id) {
        documentService.delete(id);
        return Result.ok();
    }
}
