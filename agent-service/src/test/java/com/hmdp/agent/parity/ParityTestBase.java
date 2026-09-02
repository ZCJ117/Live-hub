package com.hmdp.agent.parity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 对拍基座（T3.14）：业务库探活 + JDBC 直查 + 登录辅助
 * 环境不可达 → 跳过（如实记录在报告，不虚构一致率）
 */
public abstract class ParityTestBase {

    protected static final String BIZ_URL =
            "jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=Asia/Shanghai";
    protected static final String DB_USER = "root";
    protected static final String DB_PWD = "520117";
    protected static final String GATEWAY = "http://127.0.0.1:8081";

    protected static Connection biz;

    /** 中间件探活（供子类 @EnabledIf 守卫：Nacos 未启动时在 Spring 上下文启动前跳过） */
    public static boolean middlewareReachable() {
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", 8848)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 业务服务探活：Nacos 实例列表非空才算可达（服务未启动时 Feign 降级返回兜底话术，会污染对拍结果） */
    private static boolean serviceUp(String serviceName) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=" + serviceName)).GET().build();
            HttpResponse<String> resp = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && resp.body().contains("\"hosts\":[{");
        } catch (Exception e) {
            return false;
        }
    }

    /** 供子类 @EnabledIf：shop-service 是否已注册到 Nacos */
    public static boolean shopServiceUp() {
        return serviceUp("shop-service");
    }

    /** 供子类 @EnabledIf：voucher-service 是否已注册到 Nacos */
    public static boolean voucherServiceUp() {
        return serviceUp("voucher-service");
    }

    @BeforeAll
    static void initBizDb() {
        try {
            biz = DriverManager.getConnection(BIZ_URL, DB_USER, DB_PWD);
            biz.createStatement().executeQuery("SELECT 1");
        } catch (Exception e) {
            biz = null;
        }
        assumeTrue(biz != null, "业务库 hmdp 不可达，跳过对拍测试（待环境）");
    }

    @AfterAll
    static void closeBizDb() throws Exception {
        if (biz != null) {
            biz.close();
        }
    }

    /** JDBC 直查（期望值来源） */
    protected static List<Map<String, Object>> rows(String sql) throws Exception {
        try (Statement st = biz.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            List<Map<String, Object>> list = new ArrayList<>();
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.put(md.getColumnLabel(i).toLowerCase(), rs.getObject(i));
                }
                list.add(row);
            }
            return list;
        }
    }

    /** 经网关的 HTTP 调用（登录用） */
    protected static String httpPost(String path, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(GATEWAY + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody))
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    protected static String httpGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(GATEWAY + path)).GET().build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    /** 字段断言（null 完全等价比较，不做默认值兜底） */
    protected static void assertFieldEquals(Object actual, Object expected, String field, Object rowId) {
        assertEquals(String.valueOf(expected), String.valueOf(actual),
                "字段不一致: " + field + " (行 " + rowId + ") 期望=" + expected + " 实际=" + actual);
    }

    /** 数值字段断言（跨 BigDecimal/Double 序列化边界按数值比较，如 DECIMAL 100.00 vs JSON 100.0；双侧 null 视为等价） */
    protected static void assertNumericEquals(Object actual, Object expected, String field, Object rowId) {
        if (actual == null && expected == null) {
            return;
        }
        assertTrue(actual != null && expected != null,
                "字段不一致: " + field + " (行 " + rowId + ") 期望=" + expected + " 实际=" + actual);
        assertEquals(0, new BigDecimal(String.valueOf(expected)).compareTo(new BigDecimal(String.valueOf(actual))),
                "字段不一致: " + field + " (行 " + rowId + ") 期望=" + expected + " 实际=" + actual);
    }
}
