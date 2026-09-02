package com.hmdp.agent.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 P1 数据层契约（FR-12/FR-13，T5.1/T5.3）
 * 直连 agent_service 库验证服务层依赖的 SQL 语义：
 * 1. 评价每会话仅一次 = rating IS NULL 条件更新（重复仅 1 胜出）
 * 2. 90 天归档条件更新 = status IN + update_time 边界
 * 3. 快照 uk_session 唯一键 = 固化幂等
 */
@Tag("db-it")
class SessionP1FeaturesDbIT {

    private static final String URL = "jdbc:mysql://127.0.0.1:3306/agent_service?useSSL=false&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASS = "520117";

    private static Connection conn;
    private static long sessionId;

    @BeforeAll
    static void setup() throws Exception {
        try {
            conn = DriverManager.getConnection(URL, USER, PASS);
        } catch (Exception e) {
            conn = null;
        }
        Assumptions.assumeTrue(conn != null, "MySQL 离线，跳过会话 P1 数据层契约 db-it");
        sessionId = System.currentTimeMillis();
        Statement st = conn.createStatement();
        st.execute("INSERT INTO agent_session(id, user_id, module, status, entry, flow_state, msg_count, "
                + "create_time, update_time) VALUES (" + sessionId + ", 999999, 'M5', 'CLOSED', 'my', 'IDLE', 3, NOW(), NOW())");
        // 快照表（若迁移未应用则跳过并提示）
        try {
            st.execute("INSERT INTO agent_session_snapshot(session_id, snapshot_json) "
                    + "VALUES (" + sessionId + ", '{\"messages\":[]}')");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "agent_session_snapshot 表未创建（先应用 sql/phase5-session-snapshot.sql）");
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (conn != null && sessionId > 0) {
            Statement st = conn.createStatement();
            st.execute("DELETE FROM agent_session_snapshot WHERE session_id=" + sessionId);
            st.execute("DELETE FROM agent_session WHERE id=" + sessionId);
        }
    }

    @Test
    void 评价每会话仅一次_条件更新幂等() throws Exception {
        String sql = "UPDATE agent_session SET rating=?, rating_tags=? "
                + "WHERE id=? AND user_id=999999 AND rating IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, 1);
            ps.setString(2, "[\"没解决问题\"]");
            assertEquals(1, ps.executeUpdate(), "首次评价恰好 1 行");
            ps.setInt(1, 5);
            ps.setString(2, null);
            assertEquals(0, ps.executeUpdate(), "重复评价 0 行（每会话仅一次）");
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT rating, rating_tags FROM agent_session WHERE id=" + sessionId)) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("rating"));
            assertEquals("[\"没解决问题\"]", rs.getString("rating_tags"));
        }
    }

    @Test
    void 快照固化幂等_uk_session唯一键() throws Exception {
        try {
            // 与 setup 中同 session_id 的第二次固化 → 必须被唯一键拦截
            Statement st = conn.createStatement();
            st.execute("INSERT INTO agent_session_snapshot(session_id, snapshot_json) "
                    + "VALUES (" + sessionId + ", '{\"messages\":[1]}')");
            org.junit.jupiter.api.Assertions.fail("重复固化未被 uk_session 拦截");
        } catch (Exception e) {
            assertTrue(String.valueOf(e.getMessage()).contains("Duplicate"),
                    "重复固化应报唯一键冲突: " + e.getMessage());
        }
    }

    @Test
    void 归档条件更新_90天边界生效() throws Exception {
        String sql = "UPDATE agent_session SET status='ARCHIVED' WHERE id=? "
                + "AND status IN ('CLOSED','TRANSFERRED') AND update_time < DATE_SUB(NOW(), INTERVAL 90 DAY)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            assertEquals(0, ps.executeUpdate(), "90 天内会话不归档");

            // 回拨 update_time 至 91 天前 → 归档命中
            conn.createStatement().execute(
                    "UPDATE agent_session SET update_time=DATE_SUB(NOW(), INTERVAL 91 DAY) WHERE id=" + sessionId);
            ps.setLong(1, sessionId);
            assertEquals(1, ps.executeUpdate(), "超 90 天会话归档");
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT status FROM agent_session WHERE id=" + sessionId)) {
            assertTrue(rs.next());
            assertEquals("ARCHIVED", rs.getString("status"));
        }
    }
}
