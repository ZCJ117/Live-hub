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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * order 库退款原子性（T4.4）：并发 UPDATE ... WHERE status=2 仅 1 次成功，status 终态=5
 * 直连 hmdp 库复现 VoucherOrderServiceImpl.refund 的原子 UPDATE（第二道闸门裁决）
 */
@Tag("db-it")
class OrderRefundAtomicDbIT {

    private static final String URL = "jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASS = "520117";

    private static Connection conn;
    private static long orderId;

    @BeforeAll
    static void setup() throws Exception {
        try {
            conn = DriverManager.getConnection(URL, USER, PASS);
        } catch (Exception e) {
            conn = null;
        }
        Assumptions.assumeTrue(conn != null, "MySQL 离线，跳过退款原子性 db-it");
        orderId = System.currentTimeMillis();
        Statement st = conn.createStatement();
        st.execute("INSERT IGNORE INTO tb_voucher_order(id, user_id, voucher_id, status, create_time) "
                + "VALUES (" + orderId + ", 999999, 1, 2, NOW())");
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (conn != null && orderId > 0) {
            conn.createStatement().execute("DELETE FROM tb_voucher_order WHERE id=" + orderId);
        }
    }

    @Test
    void 并发退款仅1次成功_终态退款中() throws Exception {
        String sql = "UPDATE tb_voucher_order SET status=5, refund_time=NOW() "
                + "WHERE id=? AND user_id=999999 AND status=2";
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
                    ps.setLong(1, orderId);
                    return ps.executeUpdate();
                }
            }));
        }
        ready.await();
        go.countDown();
        int total = 0;
        for (Future<Integer> f : futures) {
            total += f.get();
        }
        pool.shutdown();

        assertEquals(1, total, "并发退款必须仅 1 次成功");
        ResultSet rs = conn.createStatement()
                .executeQuery("SELECT status FROM tb_voucher_order WHERE id=" + orderId);
        rs.next();
        assertEquals(5, rs.getInt(1), "终态必须是 5=退款中");
    }
}
