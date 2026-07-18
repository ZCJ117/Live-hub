package com.hmdp.rag.repository;

import com.hmdp.rag.entity.DocumentChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
@RequiredArgsConstructor
@Slf4j
public class DocumentChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String INSERT_SQL =
        "INSERT INTO rag_document_chunk (document_id, kb_id, chunk_index, content, embedding, tsv, metadata) " +
        "VALUES (?, ?, ?, ?, ?::vector, to_tsvector('chinese', ?), ?::jsonb)";

    private static final String VECTOR_SEARCH_SQL =
        "SELECT id, document_id, kb_id, chunk_index, content, metadata, " +
        "1 - (embedding <=> ?::vector) AS similarity " +
        "FROM rag_document_chunk WHERE kb_id = ? " +
        "ORDER BY embedding <=> ?::vector LIMIT ?";

    private static final String KEYWORD_SEARCH_SQL =
        "SELECT id, document_id, kb_id, chunk_index, content, metadata, " +
        "ts_rank(tsv, plainto_tsquery('chinese', ?)) AS score " +
        "FROM rag_document_chunk WHERE kb_id = ? AND tsv @@ plainto_tsquery('chinese', ?) " +
        "ORDER BY score DESC LIMIT ?";

    private static final String DELETE_BY_DOC_ID =
        "DELETE FROM rag_document_chunk WHERE document_id = ?";

    public void batchInsert(List<DocumentChunk> chunks, List<String> embeddingStrs) {
        jdbcTemplate.batchUpdate(INSERT_SQL, chunks, chunks.size(),
            (ps, chunk) -> {
                int idx = chunks.indexOf(chunk);
                ps.setLong(1, chunk.getDocumentId());
                ps.setLong(2, chunk.getKbId());
                ps.setInt(3, chunk.getChunkIndex());
                ps.setString(4, chunk.getContent());
                ps.setString(5, embeddingStrs.get(idx));
                ps.setString(6, chunk.getContent());
                ps.setString(7, chunk.getMetadata() != null ? chunk.getMetadata() : "{}");
            });
        log.debug("Batch inserted {} chunks", chunks.size());
    }

    public List<DocumentChunk> vectorSearch(String embeddingStr, Long kbId, int topK) {
        return jdbcTemplate.query(VECTOR_SEARCH_SQL,
            new ChunkWithScoreRowMapper(), embeddingStr, kbId, embeddingStr, topK);
    }

    public List<DocumentChunk> keywordSearch(String query, Long kbId, int topK) {
        return jdbcTemplate.query(KEYWORD_SEARCH_SQL,
            new ChunkWithScoreRowMapper(), query, kbId, query, topK);
    }

    public void deleteByDocumentId(Long documentId) {
        jdbcTemplate.update(DELETE_BY_DOC_ID, documentId);
    }

    static class ChunkWithScoreRowMapper implements RowMapper<DocumentChunk> {
        @Override
        public DocumentChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(rs.getLong("id"));
            chunk.setDocumentId(rs.getLong("document_id"));
            chunk.setKbId(rs.getLong("kb_id"));
            chunk.setChunkIndex(rs.getInt("chunk_index"));
            chunk.setContent(rs.getString("content"));
            chunk.setMetadata(rs.getString("metadata"));
            return chunk;
        }
    }
}
