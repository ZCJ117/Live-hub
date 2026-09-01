package com.hmdp.agent.it;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 并发确认幂等压测（FR-08 验收 1：并发 10 次确认仅 1 条生效；P4-R4 硬门禁）
 * 直连 agent_service 库复现 ConfirmTaskService.tryAdopt 的条件更新 SQL
 */
@Tag("db-it")
class RefundIdempotencyDbIT {

    private static final String URL = "jdbc:mysql://127.0.0.1:3306/agent_service?useSSL=false&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASS = "520117";

    private static Connection conn;

    @BeforeAll
    static void setup() {
        try {
            conn = DriverManager.getConnection(URL, USER, PASS);
        } catch (Exception e) {
            conn = null;
        }
        Assumptions.assumeTrue(conn != null, "MySQL 离线，跳过幂等 db-it");
    }

    @Test
    void 并发10次条件更新仅1次胜出() throws Exception {
        String actionId = "it-" + UUID.randomUUID();
        Statement st = conn.createStatement();
        try {
            st.execute("INSERT INTO agent_task(id, session_id, user_id, task_type, status, action_id, "
                    + "biz_order_id, expire_time, create_time, update_time) VALUES ("
                    + System.nanoTime() % 1000000000000L + ", 1, 100, 'REFUND_REQUEST', 'PENDING_CONFIRM', '"
                    + actionId + "', 200, NOW() + INTERVAL 10 MINUTE, NOW(), NOW())");

            // 10 并发执行 tryAdopt 同款条件更新（每线程独立连接）
            String sql = "UPDATE agent_task SET status='ADOPTED', confirm_time=NOW() "
                    + "WHERE action_id=? AND status='PENDING_CONFIRM' AND expire_time > NOW()";
            ExecutorService pool = Executors.newFixedThreadPool(10);
            CountDownLatch ready = new CountDownLatch(10);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    // JDBC Connection 非线程安全：每线程独立连接
                    try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                         PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setString(1, actionId);
                        return ps.executeUpdate();
                    }
                }));
            }
            ready.await();
            go.countDown();
            int totalUpdated = 0;
            for (Future<Integer> f : futures) {
                totalUpdated += f.get();
            }
            pool.shutdown();

            assertEquals(1, totalUpdated, "并发 10 次确认必须仅 1 次生效（P4-R4 硬门禁）");
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM agent_task WHERE action_id='" + actionId + "' AND status='ADOPTED'");
            rs.next();
            assertEquals(1, rs.getInt(1));
        } finally {
            st.execute("DELETE FROM agent_task WHERE action_id='" + actionId + "'");
        }
    }
}
