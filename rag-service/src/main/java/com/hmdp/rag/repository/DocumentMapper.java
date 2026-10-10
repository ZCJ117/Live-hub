package com.hmdp.rag.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.rag.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

    //NOTE 1,6 乐观更新 CAS：仅 PENDING 能被抢到，重复投递（PROCESSING/COMPLETED/FAILED）影响 0 行，是消费幂等的唯一依据
    @Update("UPDATE rag_document SET status = 'PROCESSING' WHERE id = #{id} AND status = 'PENDING'")
    int casToProcessing(@Param("id") Long id);
}
