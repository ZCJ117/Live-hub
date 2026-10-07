package com.hmdp.contract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feign ⇄ Controller 契约测试（SPEC-07 G5 / 验收 A4、A5）
 *
 * <p>断言一：每个 {@code @FeignClient} 方法都能在目标服务的 {@code @RestController}
 * 里匹配到「HTTP 方法 + 路径」的唯一映射——杜绝 {@code /user/list} 这类
 * "调用方按设想编写、提供方从未实现"的死契约。
 *
 * <p>断言二：{@code @FeignClient} 接口中不存在 {@code @GetMapping} + {@code @RequestBody}
 * 的组合——违反 HTTP 语义，多数客户端与代理会丢弃 GET 的 body。本测试是 Feign 契约测试，
 * 只扫 {@code @FeignClient} 接口，Controller 侧的同类写法不在其范围内。
 *
 * <p>纯单元测试，不依赖 Spring 上下文，可入 CI。
 */
class FeignContractTest {

    /** @FeignClient(name=...) → 模块目录名 */
    private static final Map<String, String> SERVICE_MODULE = Map.of(
            "user-service", "user-service",
            "voucher-service", "voucher-service",
            "order-service", "order-service",
            "shop-service", "shop-service",
            "social-service", "social-service",
            "rag-service", "rag-service",
            "agent-service", "agent-service");

    private static final Pattern FEIGN_CLIENT =
            Pattern.compile("@FeignClient\\(([^)]*)\\)");
    private static final Pattern CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"[^)]*\\)[^;{]*\\bclass\\b");
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"[^)]*\\)");
    private static final Pattern REQUEST_BODY = Pattern.compile("@RequestBody");

    @Test
    void everyFeignMethodHasAControllerMapping() throws IOException {
        Path root = repoRoot();
        List<String> missing = new ArrayList<>();

        for (Path feignFile : feignInterfaces(root)) {
            String src = Files.readString(feignFile, StandardCharsets.UTF_8);
            Matcher clientMatcher = FEIGN_CLIENT.matcher(src);
            if (!clientMatcher.find()) {
                continue;
            }
            String name = attr(clientMatcher.group(1), "name");
            String module = SERVICE_MODULE.get(name);
            if (module == null) {
                missing.add(feignFile + " → 未知服务名 " + name);
                continue;
            }
            Set<String> routes = controllerRoutes(root.resolve(module));
            for (String[] call : feignCalls(src)) {
                String key = call[0] + " " + normalize(call[1]);
                if (!routes.contains(key)) {
                    missing.add(feignFile.getFileName() + " 的 " + key
                            + " 在 " + module + " 中无对应 Controller 映射。该服务已有：" + routes);
                }
            }
        }

        assertTrue(missing.isEmpty(), "Feign 契约失配（调用方声明的端点，提供方不存在）：\n" + String.join("\n", missing));
    }

    @Test
    void noGetMappingWithRequestBody() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(repoRoot())) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/target/")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                if (!src.contains("@FeignClient")) {
                    continue;
                }
                Matcher m = METHOD_MAPPING.matcher(src);
                while (m.find()) {
                    if ("Get".equals(m.group(1))) {
                        // 只看到本方法声明语句的 ';' 为止：固定 400 字窗口会跨过下一个方法，
                        // 把 @PostMapping 方法上的 @RequestBody 误判为本 GET 的 body。
                        // 找不到 ';' 时收敛到文件末尾，绝不放宽窗口——护栏不该 fail-open
                        int end = src.indexOf(';', m.end());
                        int bound = end < 0 ? src.length() : end + 1;
                        String tail = src.substring(m.end(), bound);
                        if (REQUEST_BODY.matcher(tail).find()) {
                            violations.add(f.getFileName() + " → " + m.group(0));
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Feign 存在 @GetMapping + @RequestBody 组合（GET 带 body 会被代理丢弃）：" + violations);
    }

    private static String attr(String annotationBody, String name) {
        Matcher m = Pattern.compile(name + "\\s*=\\s*\"([^\"]*)\"").matcher(annotationBody);
        return m.find() ? m.group(1) : null;
    }

    /** 返回该 Feign 接口内每个方法映射的 [HTTP方法, 路径] */
    private static List<String[]> feignCalls(String src) {
        List<String[]> calls = new ArrayList<>();
        Matcher m = METHOD_MAPPING.matcher(src);
        while (m.find()) {
            calls.add(new String[]{m.group(1).toUpperCase(), m.group(2)});
        }
        return calls;
    }

    /** 目标模块所有 @RestController 的「HTTP方法 + 全路径」集合 */
    private static Set<String> controllerRoutes(Path moduleDir) throws IOException {
        Set<String> routes = new LinkedHashSet<>();
        Path srcRoot = moduleDir.resolve("src/main/java");
        if (!Files.exists(srcRoot)) {
            return routes;
        }
        try (Stream<Path> files = Files.walk(srcRoot)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                if (!src.contains("@RestController")) {
                    continue;
                }
                Matcher classMapping = CLASS_MAPPING.matcher(src);
                if (!classMapping.find()) {
                    continue;
                }
                String prefix = classMapping.group(1);
                Matcher m = METHOD_MAPPING.matcher(src);
                while (m.find()) {
                    routes.add(m.group(1).toUpperCase() + " " + normalize(prefix + "/" + m.group(2)));
                }
            }
        }
        return routes;
    }

    private static List<Path> feignInterfaces(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            result.addAll(files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> p.toString().contains("/feign/"))
                    .toList());
        }
        return result;
    }

    /** 去除重复斜杠、去尾斜杠；路径变量 {id} 原样保留 */
    private static String normalize(String path) {
        String p = ("/" + path).replaceAll("/{2,}", "/");
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    /** common 模块的 user.dir 是 common/，仓库根是上一级 */
    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        return Files.exists(dir.resolve("common")) ? dir : dir.getParent();
    }
}
