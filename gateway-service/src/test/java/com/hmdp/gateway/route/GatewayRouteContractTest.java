package com.hmdp.gateway.route;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关路由契约测试（SPEC-01 G4 / 验收 A5）
 *
 * <p>双向断言：
 * <ul>
 *   <li>A：每条已声明路由前缀至少匹配一个 Controller —— 杜绝 {@code /auth/**}、{@code /order/**}
 *       这类指向空 Controller 的死断言</li>
 *   <li>B：每个对外 Controller 前缀都有路由覆盖 —— 杜绝 {@code /voucher-order/**}
 *       这类"接口存在但外部不可达"</li>
 * </ul>
 *
 * <p>Controller 前缀通过扫描磁盘源码获取，而非类路径反射：gateway-service 的测试类路径只含
 * {@code common}，看不到 order/social/rag 等模块的类，SPEC-01 §8.1 的"类路径扫描"在模块边界上
 * 不可实现。
 *
 * <p>纯单元测试，不依赖 Nacos / Redis / Spring 上下文，可入 CI。
 */
class GatewayRouteContractTest {

    /** 有意不对网关暴露的内部端点前缀（服务间 Feign 调用专用，见 InternalRetrievalController）。 */
    private static final Set<String> INTERNAL_PREFIXES = Set.of("/internal/rag");

    /** 仅匹配类级映射：@RequestMapping(...) 与其后的 class 声明之间不含 ; 或 {。 */
    private static final Pattern CLASS_LEVEL_MAPPING = Pattern.compile(
            "@RequestMapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"[^)]*\\)[^;{]*\\bclass\\b");

    @Test
    void everyRoutePrefixMatchesAController() throws IOException {
        Path root = repoRoot();
        Set<String> routes = routePrefixes(root);
        Set<String> controllers = controllerPrefixes(root);

        List<String> dead = new ArrayList<>();
        for (String route : routes) {
            if (controllers.stream().noneMatch(c -> covers(route, c))) {
                dead.add(route);
            }
        }

        assertTrue(dead.isEmpty(),
                "网关存在指向空 Controller 的死断言（应删除，或补齐对应 Controller）：" + dead
                        + "\n现有 Controller 前缀：" + controllers);
    }

    @Test
    void everyPublicControllerPrefixHasARoute() throws IOException {
        Path root = repoRoot();
        Set<String> routes = routePrefixes(root);
        Set<String> controllers = controllerPrefixes(root);

        List<String> unrouted = new ArrayList<>();
        for (String controller : controllers) {
            if (INTERNAL_PREFIXES.contains(controller)) {
                continue;
            }
            if (routes.stream().noneMatch(r -> covers(r, controller))) {
                unrouted.add(controller);
            }
        }

        assertTrue(unrouted.isEmpty(),
                "以下 Controller 前缀无网关路由，外部不可达：" + unrouted
                        + "\n现有路由前缀：" + routes
                        + "\n（若确为内部端点，请加入 INTERNAL_PREFIXES 白名单）");
    }

    /**
     * 防解析静默漏检：扫到的 Controller 文件必须每个都能解析出类级映射。
     * 否则断言 B 会因漏检而假绿。
     */
    @Test
    void everyControllerFileYieldsAClassLevelMapping() throws IOException {
        Path root = repoRoot();
        List<Path> files = controllerFiles(root);

        List<String> unparsed = new ArrayList<>();
        for (Path file : files) {
            if (!CLASS_LEVEL_MAPPING.matcher(read(file)).find()) {
                unparsed.add(root.relativize(file).toString());
            }
        }

        assertTrue(unparsed.isEmpty(),
                "以下 Controller 文件未解析出类级 @RequestMapping（正则可能已失配，需同步调整）：" + unparsed);
    }

    /**
     * 内部端点不得经网关暴露（SPEC-06 §5.2 方案 C）。
     * 网关本就没有 /internal/** 路由，此处把该隐式约定变成可回归的显式约束：
     * 一旦有人新增这样的路由，本测试立即失败。
     */
    @Test
    void noRouteExposesInternalEndpoints() throws IOException {
        Set<String> routes = routePrefixes(repoRoot());

        List<String> leaked = routes.stream()
                .filter(r -> covers(r, "/internal"))
                .toList();

        assertTrue(leaked.isEmpty(),
                "内部端点不得经网关暴露（SPEC-06 §5.2 C）：" + leaked);
    }

    /** 扫描根定位自检：文件数明显偏少即说明 repoRoot() 找错了目录。 */
    @Test
    void scanFindsTheExpectedControllerFiles() throws IOException {
        Path root = repoRoot();
        List<Path> files = controllerFiles(root);
        assertTrue(files.size() >= 20,
                "只扫到 " + files.size() + " 个 Controller 文件，疑似扫描根定位错误：" + root);
    }

    /** 从 user.dir 向上定位仓库根。surefire 的工作目录是模块目录，故需上溯。 */
    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++, dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("gateway-service/src/main/resources/application.yaml"))
                    && Files.isDirectory(dir.resolve("order-service"))) {
                return dir;
            }
        }
        throw new IllegalStateException("无法从 " + Paths.get("").toAbsolutePath()
                + " 上溯定位仓库根（5 层内未见 gateway-service + order-service）");
    }

    /** 解析 application.yaml 中全部 Path= 断言，拆出路由前缀集合。 */
    @SuppressWarnings("unchecked")
    private static Set<String> routePrefixes(Path root) throws IOException {
        Path config = root.resolve("gateway-service/src/main/resources/application.yaml");

        Object parsed;
        try (InputStream in = Files.newInputStream(config)) {
            parsed = new Yaml().load(in);
        }

        Map<String, Object> gateway = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) ((Map<String, Object>) parsed).get("spring")).get("cloud"))
                .get("gateway");
        List<Map<String, Object>> routes = (List<Map<String, Object>>) gateway.get("routes");

        Set<String> prefixes = new LinkedHashSet<>();
        for (Map<String, Object> route : routes) {
            Object predicates = route.get("predicates");
            if (!(predicates instanceof List)) {
                continue;
            }
            for (Object predicate : (List<Object>) predicates) {
                String text = String.valueOf(predicate).trim();
                if (!text.startsWith("Path=")) {
                    continue;
                }
                for (String path : text.substring("Path=".length()).split(",")) {
                    prefixes.add(path.trim());
                }
            }
        }
        return prefixes;
    }

    /** 扫描各业务模块 Controller 的类级 @RequestMapping 前缀。 */
    private static Set<String> controllerPrefixes(Path root) throws IOException {
        Set<String> prefixes = new LinkedHashSet<>();
        for (Path file : controllerFiles(root)) {
            Matcher matcher = CLASS_LEVEL_MAPPING.matcher(read(file));
            if (matcher.find()) {
                prefixes.add(matcher.group(1));
            }
        }
        return prefixes;
    }

    /** 各 *-service 模块 src/main/java 下的 Controller 文件（common 非服务模块，不参与）。 */
    private static List<Path> controllerFiles(Path root) throws IOException {
        List<Path> moduleDirs;
        try (Stream<Path> stream = Files.list(root)) {
            moduleDirs = stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().endsWith("-service"))
                    .sorted()
                    .toList();
        }

        List<Path> files = new ArrayList<>();
        for (Path module : moduleDirs) {
            Path sourceRoot = module.resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                walk.filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                        .sorted()
                        .forEach(files::add);
            }
        }
        return files;
    }

    /** PathPattern 语义：{@code /a/**} 覆盖 {@code /a} 本身及其全部子路径。 */
    private static boolean covers(String route, String controller) {
        if (!route.endsWith("/**")) {
            return route.equals(controller);
        }
        String base = route.substring(0, route.length() - "/**".length());
        return controller.equals(base) || controller.startsWith(base + "/");
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
