package com.hmdp.rag.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.rag.dto.CreateKbRequest;
import com.hmdp.rag.entity.KnowledgeBase;

public interface IKnowledgeBaseService {
    KnowledgeBase create(CreateKbRequest request, Long userId);
    KnowledgeBase getById(Long id);
    Page<KnowledgeBase> listByMerchant(Long merchantId, int pageNum, int pageSize);
    KnowledgeBase update(Long id, CreateKbRequest request, Long userId);
    void delete(Long id);
}
