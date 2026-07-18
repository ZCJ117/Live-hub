package com.hmdp.rag.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.rag.entity.Document;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}
