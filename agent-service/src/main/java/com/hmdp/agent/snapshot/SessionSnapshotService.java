package com.hmdp.agent.snapshot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.entity.AgentSession;
import com.hmdp.agent.entity.AgentSessionSnapshot;
import com.hmdp.agent.mapper.AgentSessionMapper;
import com.hmdp.agent.mapper.AgentSessionSnapshotMapper;
import com.hmdp.agent.memory.ChatMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话回放快照服务（FR-13 T5.3）
 * 会话关闭/转人工确认时固化 消息+卡片 → agent_session_snapshot（静态回放，工具过程不回放）；
 * 卡片流水由 SseSessionManager 在 card 事件发出时写入 Redis（cardsKey，覆盖全部卡片发射点）；
 * 快照写入失败不阻断会话关闭（尽力而为），回放接口对无快照会话返回 snapshot=null
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SessionSnapshotService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentSessionSnapshotMapper snapshotMapper;
    private final ChatMemoryService memoryService;
    private final RedissonClient redisson;
    private final AgentSessionMapper sessionMapper;

    /** 会话关闭后清理卡片快照键（随内存历史一并清理） */
    public void evictCards(Long sessionId) {
        redisson.getKeys().delete(ChatMemoryService.cardsKey(sessionId));
    }

    /**
     * 固化快照：history（Redis）+ cards（Redis）+ 摘要 → 快照表 + snapshot_uri 回填
     * @return 是否写入成功
     */
    public boolean saveSnapshot(com.hmdp.agent.entity.AgentSession session) {
        Long sessionId = session.getId();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("messages", memoryService.loadHistory(sessionId));
        snapshot.put("cards", readCards(sessionId));
        snapshot.put("summary", session.getSummary());
        snapshot.put("msgCount", session.getMsgCount());
        snapshot.put("closedAt", LocalDateTime.now().toString());
        try {
            int inserted = snapshotMapper.insert(new AgentSessionSnapshot()
                    .setSessionId(sessionId)
                    .setSnapshotJson(MAPPER.writeValueAsString(snapshot)));
            if (inserted > 0) {
                // snapshot_uri 仅在为空时回填：转人工路径已被移交包指针（redis://agent:transfer:{id}）占用
                sessionMapper.update(null, Wrappers.<AgentSession>lambdaUpdate()
                        .eq(AgentSession::getId, sessionId)
                        .isNull(AgentSession::getSnapshotUri)
                        .set(AgentSession::getSnapshotUri, "db://agent_session_snapshot/" + sessionId));
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("会话快照固化失败（不阻断关闭）: sessionId={}", sessionId, e);
            return false;
        }
    }

    /** 读快照（回放接口）：messages=[{role,content}], cards=[{cardType,data}]；无快照返回 null */
    public Map<String, Object> loadSnapshot(Long sessionId) {
        AgentSessionSnapshot row = snapshotMapper.selectOne(
                new LambdaQueryWrapper<AgentSessionSnapshot>()
                        .eq(AgentSessionSnapshot::getSessionId, sessionId)
                        .last("LIMIT 1"));
        if (row == null) {
            return null;
        }
        try {
            return MAPPER.readValue(row.getSnapshotJson(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("快照解析失败: sessionId={}", sessionId);
            return null;
        }
    }

    private List<Object> readCards(Long sessionId) {
        try {
            RList<String> list = redisson.getList(ChatMemoryService.cardsKey(sessionId));
            return list.readAll().stream()
                    .map(json -> {
                        try {
                            return MAPPER.readValue(json, Object.class);
                        } catch (Exception e) {
                            return Map.of("cardType", "UNKNOWN", "data", json);
                        }
                    })
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
