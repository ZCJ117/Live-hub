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

    //NOTE 1 文档上传流程
    //NOTE 1,1 Gateway 拦截请求，Sa-Token 校验登录状态

    private final IDocumentService documentService;


    //NOTE 1,2 Controller 接收请求，调用 Service 处理业务逻辑
    @PostMapping("/knowledge-bases/{kbId}/documents")
    public Result upload(@PathVariable Long kbId,
                          @RequestParam("file") MultipartFile file) {
        Long userId = UserHolder.getUser().getId(); //从ThreadLocal中获取当前登录用户的ID
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
