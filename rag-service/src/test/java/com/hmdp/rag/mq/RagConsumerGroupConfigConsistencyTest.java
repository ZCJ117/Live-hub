package com.hmdp.rag.mq;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SPEC-08 §1.5/§5.6（A5）——消费组名取值唯一，杜绝 yaml 与注解各写一份的误导性死配置。
 *
 * <p>纯文本单测、不启 Spring：直接读 {@code rag-service/src/main/resources/application.yaml} 与
 * {@code DocumentProcessConsumer.java} 源码。采用 §5.6 方案一（删除 yaml 死配置），唯一事实源 =
 * 注解 {@code consumerGroup}。
 *
 * <p>包名刻意放在 {@code com.hmdp.rag.mq}，以便通过包相对路径上溯定位仓库根
 * （surefire 默认工作目录 = 被构建模块根，即 rag-service）。
 */
class RagConsumerGroupConfigConsistencyTest {

    /** 注解里声明的真实消费组（单一事实源）。 */
    private static final String ANNOTATION_GROUP = "rag-doc-process-consumer-group";

    /** 被 §5.6 判定为误导性的旧 yaml 取值，删除后不得在任何位置残留。 */
    private static final String STALE_GROUP = "rag-doc-process-group";

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++) {
            if (Files.isDirectory(p.resolve("rag-service"))
                    && Files.exists(p.resolve("pom.xml"))
                    && Files.isDirectory(p.resolve("social-service"))) {
                return p;
            }
            p = p.getParent();
        }
        fail("无法从 " + Paths.get("").toAbsolutePath() + " 上溯定位仓库根");
        return null;
    }

    private static Path yamlPath() {
        return repoRoot().resolve("rag-service/src/main/resources/application.yaml");
    }

    private static Path consumerSourcePath() {
        return repoRoot().resolve("rag-service/src/main/java/com/hmdp/rag/mq/DocumentProcessConsumer.java");
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * 在 yaml 中查找形如 {@code group: <value>} 的配置项并返回其值（去注释与首尾空白）；
     * 无匹配返回 null。正则取自 SPEC-08 §5.6 的校验方式。
     */
    private static String yamlGroupValue(String yaml) {
        Matcher m = Pattern.compile("(?m)^\\s+group:\\s*([^#\\r\\n]+)").matcher(yaml);
        return m.find() ? m.group(1).trim() : null;
    }

    @Test
    void yaml中不再出现group死配置() throws IOException {
        String yaml = read(yamlPath());
        String groupValue = yamlGroupValue(yaml);
        assertTrue(groupValue == null,
                "rocketmq.consumer.group 应已删除（§5.6 方案一），但 yaml 中仍能匹配到 group: " + groupValue);
    }

    @Test
    void 注解声明的消费组与唯一事实源一致() throws IOException {
        String src = read(consumerSourcePath());
        Matcher m = Pattern.compile("consumerGroup\\s*=\\s*\"([^\"]+)\"").matcher(src);
        assertTrue(m.find(), "DocumentProcessConsumer 必须显式声明 consumerGroup");
        assertEquals(ANNOTATION_GROUP, m.group(1),
                "注解 consumerGroup 是全模块唯一的消费组事实源，不得被改动");
    }

    @Test
    void yaml中不得残留rag_doc_process_group字样() throws IOException {
        String yaml = read(yamlPath());
        assertFalse(yaml.contains(STALE_GROUP),
                "yaml 不得再出现旧消费组名 " + STALE_GROUP + "（即便写在注释里也会误导运维照抄建组）");
    }

    @Test
    void yaml就近注释说明消费组由注解声明() throws IOException {
        String yaml = read(yamlPath());
        assertTrue(yaml.contains("注解"),
                "删除死配置后需就近用中文注释说明消费组/topic 由 @RocketMQMessageListener 注解声明（§5.6）");
    }
}
