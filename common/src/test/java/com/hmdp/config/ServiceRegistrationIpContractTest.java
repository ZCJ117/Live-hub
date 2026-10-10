package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 注册地址 == 监听地址（BUG-01 回归锁）
 *
 * <p><b>背景</b>：业务端口按 SPEC-06 §5.2 绑 {@code server.address: 127.0.0.1}，但 Spring Cloud
 * 仍用 {@code spring.cloud.inetutils} 探测到的 Docker/WSL vNIC 地址（如 172.26.112.1）注册到 Nacos。
 * 网关 {@code lb://} 拿到该地址后 {@code Connection refused}，导致**每个经网关的请求都 500**，
 * 而直连 {@code 127.0.0.1:8086} 却一切正常——极难定位。
 *
 * <p>本测试是静态契约锁：每个把业务端口绑到回环的服务，必须在 {@code bootstrap.yaml} 里显式声明
 * {@code spring.cloud.nacos.discovery.ip}，且其值等于监听地址。运行时的对应验证是全栈 E2E
 * （不带 token 经网关请求应返回 401 而非 500）。
 */
class ServiceRegistrationIpContractTest {

    /** 全部会注册到 Nacos 的服务模块。 */
    private static final List<String> SERVICES = List.of(
            "gateway-service", "user-service", "shop-service", "voucher-service",
            "order-service", "social-service", "rag-service", "agent-service");

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++) {
            if (Files.isDirectory(p.resolve("common")) && Files.exists(p.resolve("pom.xml"))
                    && Files.isDirectory(p.resolve("order-service"))) {
                return p;
            }
            p = p.getParent();
        }
        fail("无法从 " + Paths.get("").toAbsolutePath() + " 上溯定位仓库根");
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        assertTrue(Files.exists(path), "配置文件不存在: " + path);
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return new Yaml().load(r);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object dig(Map<String, Object> root, String... keys) {
        Object cur = root;
        for (String k : keys) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<String, Object>) cur).get(k);
        }
        return cur;
    }

    /** {@code ${VAR:default}} → {@code default}；无占位符时原样返回。 */
    private static String resolve(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.startsWith("${") && v.endsWith("}")) {
            String inner = v.substring(2, v.length() - 1);
            int colon = inner.indexOf(':');
            v = colon >= 0 ? inner.substring(colon + 1).trim() : v;
        }
        return v;
    }

    @Test
    void 每个服务都必须显式声明注册IP() throws IOException {
        Path root = repoRoot();
        StringBuilder missing = new StringBuilder();
        for (String svc : SERVICES) {
            Map<String, Object> bootstrap = loadYaml(root.resolve(svc + "/src/main/resources/bootstrap.yaml"));
            Object ip = dig(bootstrap, "spring", "cloud", "nacos", "discovery", "ip");
            if (ip == null) {
                missing.append("\n  - ").append(svc);
            }
        }
        assertEquals("", missing.toString(),
                "以下服务未在 bootstrap.yaml 声明 spring.cloud.nacos.discovery.ip——"
                        + "它们会以 Docker/WSL vNIC 地址注册到 Nacos，网关 lb:// 转发必然 Connection refused (BUG-01)："
                        + missing);
    }

    @Test
    void 注册IP必须等于监听地址() throws IOException {
        Path root = repoRoot();
        for (String svc : SERVICES) {
            Map<String, Object> bootstrap = loadYaml(root.resolve(svc + "/src/main/resources/bootstrap.yaml"));
            String ip = resolve((String) dig(bootstrap, "spring", "cloud", "nacos", "discovery", "ip"));
            assertNotNull(ip, svc + " 未声明 spring.cloud.nacos.discovery.ip (BUG-01)");

            Map<String, Object> app = loadYaml(root.resolve(svc + "/src/main/resources/application.yaml"));
            String bind = resolve((String) dig(app, "server", "address"));
            if (bind == null) {
                // 未限制监听地址的服务（如网关）绑全部网卡，注册任意可达地址都能连上
                continue;
            }
            assertEquals(bind, ip,
                    svc + " 的注册 IP 与监听地址不一致：注册=" + ip + "，监听=" + bind
                            + "。不一致则经网关的请求 100% 失败 (BUG-01)");
        }
    }
}
