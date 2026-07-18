package com.hmdp.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.rag.dto.CreateKbRequest;
import com.hmdp.rag.entity.KnowledgeBase;
import com.hmdp.exception.BusinessException;
import com.hmdp.rag.repository.KnowledgeBaseMapper;
import com.hmdp.rag.service.IKnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseServiceImpl implements IKnowledgeBaseService {

    private final KnowledgeBaseMapper kbMapper;

    @Override
    public KnowledgeBase create(CreateKbRequest request, Long userId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setMerchantId(request.getMerchantId());
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        kb.setChunkSize(request.getChunkSize() != null ? request.getChunkSize() : 500);
        kb.setChunkOverlap(request.getChunkOverlap() != null ? request.getChunkOverlap() : 50);
        kb.setCreatedBy(userId);
        kb.setCreatedAt(LocalDateTime.now());
        kb.setUpdatedAt(LocalDateTime.now());
        kbMapper.insert(kb);
        log.info("Knowledge base created: id={}, name={}, merchantId={}", kb.getId(), kb.getName(), kb.getMerchantId());
        return kb;
    }

    @Override
    public KnowledgeBase getById(Long id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) {
            throw new BusinessException("知识库不存在");
        }
        return kb;
    }

    @Override
    public Page<KnowledgeBase> listByMerchant(Long merchantId, int pageNum, int pageSize) {
        Page<KnowledgeBase> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBase::getMerchantId, merchantId)
                .orderByDesc(KnowledgeBase::getCreatedAt);
        return kbMapper.selectPage(page, wrapper);
    }

    @Override
    public KnowledgeBase update(Long id, CreateKbRequest request, Long userId) {
        KnowledgeBase kb = getById(id);
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        if (request.getChunkSize() != null) kb.setChunkSize(request.getChunkSize());
        if (request.getChunkOverlap() != null) kb.setChunkOverlap(request.getChunkOverlap());
        kb.setUpdatedAt(LocalDateTime.now());
        kbMapper.updateById(kb);
        return kb;
    }

    @Override
    public void delete(Long id) {
        getById(id);
        kbMapper.deleteById(id);
        log.info("Knowledge base deleted: id={}", id);
    }
}
