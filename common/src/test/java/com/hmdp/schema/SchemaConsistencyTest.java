package com.hmdp.schema;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema 一致性测试（SPEC-02 G5 / 验收 A2、A7）
 *
 * <p>断言 {@code docs/SQL/start.sql} —— 干净库初始化的唯一入口 —— 与 {@code com.hmdp.entity}
 * 下的实体完全对齐：
 * <ul>
 *   <li>每个 {@code @TableName} 表在 DDL 中都存在</li>
 *   <li>每个非 {@code @TableField(exist=false)} 字段都有同名的 snake_case 列</li>
 * </ul>
 *
 * <p>修复前必须为红：{@code tb_shop} 建成 blog 结构、{@code tb_blog}/{@code tb_blog_comments}/
 * {@code tb_user} 无建表语句。
 *
 * <p>纯单元测试，不连数据库，可入 CI。
 */
class SchemaConsistencyTest {

    private static final String ENTITY_PACKAGE = "com.hmdp.entity";

    /** 表体中的列定义行：行首反引号包住的标识符（KEY/PRIMARY/UNIQUE 行不以反引号开头）。 */
    private static final Pattern COLUMN_LINE =
            Pattern.compile("(?m)^\\s*`([A-Za-z_][A-Za-z0-9_]*)`\\s");

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+`([A-Za-z_][A-Za-z0-9_]*)`\\s*\\((.*?)\\)\\s*ENGINE",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    @Test
    void everyEntityTableExistsInDdl() throws IOException {
        Map<String, Set<String>> schema = parseSchema();
        Map<String, String> entities = entityTables();

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entity : entities.entrySet()) {
            if (!schema.containsKey(entity.getValue())) {
                missing.add(entity.getKey() + " -> " + entity.getValue() + "（DDL 中无该表）");
            }
        }

        assertTrue(missing.isEmpty(),
                "以下实体在 docs/SQL/start.sql 中无对应建表语句，干净库初始化后必然 SQL 报错：" + missing
                        + "\n现有表：" + schema.keySet());
    }

    @Test
    void everyMappedFieldHasAColumn() throws IOException {
        Map<String, Set<String>> schema = parseSchema();

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entity : entityTables().entrySet()) {
            Set<String> columns = schema.get(entity.getValue());
            if (columns == null) {
                continue; // 由上一条测试负责报缺表，此处避免重复噪音
            }
            for (Map.Entry<String, String> field : mappedColumns(entity.getKey()).entrySet()) {
                if (!columns.contains(field.getValue())) {
                    missing.add(entity.getKey() + "." + field.getKey()
                            + " -> 列 " + field.getValue() + "（表 " + entity.getValue() + " 中不存在）");
                }
            }
        }

        assertTrue(missing.isEmpty(),
                "以下实体字段在 docs/SQL/start.sql 中无同名列：\n  " + String.join("\n  ", missing));
    }

    /** 防解析静默漏检：实体包下应扫到预期数量的 @TableName 类。 */
    @Test
    void scanFindsTheExpectedEntities() throws IOException {
        Map<String, String> entities = entityTables();
        assertTrue(entities.size() >= 10,
                "只扫到 " + entities.size() + " 个 @TableName 实体，疑似实体包扫描失效：" + entities);
    }

    /** DDL 解析自检：应解析出预期数量的建表语句。 */
    @Test
    void scanFindsTheExpectedTables() throws IOException {
        Map<String, Set<String>> schema = parseSchema();
        assertTrue(schema.size() >= 9,
                "只解析出 " + schema.size() + " 张表，疑似 CREATE TABLE 正则失配：" + schema.keySet());
        assertFalse(schema.getOrDefault("tb_shop", Set.of()).isEmpty(), "tb_shop 未解析出任何列");
    }

    // ---------- helpers ----------

    /** 从 user.dir 向上定位仓库根。surefire 的工作目录是模块目录，故需上溯。 */
    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++, dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("docs/SQL/start.sql")) && Files.isDirectory(dir.resolve("common"))) {
                return dir;
            }
        }
        throw new IllegalStateException("无法从 " + Paths.get("").toAbsolutePath()
                + " 上溯定位仓库根（5 层内未见 docs/SQL/start.sql + common）");
    }

    /** 表名 -> 列名集合。 */
    private static Map<String, Set<String>> parseSchema() throws IOException {
        String ddl = Files.readString(repoRoot().resolve("docs/SQL/start.sql"), StandardCharsets.UTF_8);

        Map<String, Set<String>> schema = new LinkedHashMap<>();
        Matcher tables = CREATE_TABLE.matcher(ddl);
        while (tables.find()) {
            Set<String> columns = new java.util.LinkedHashSet<>();
            Matcher columnLines = COLUMN_LINE.matcher(tables.group(2));
            while (columnLines.find()) {
                columns.add(columnLines.group(1));
            }
            schema.put(tables.group(1), columns);
        }
        return schema;
    }

    /** 实体简单类名 -> @TableName 表名。 */
    private static Map<String, String> entityTables() throws IOException {
        Map<String, String> tables = new TreeMap<>();
        Path entityDir = repoRoot().resolve("common/src/main/java/com/hmdp/entity");

        List<Path> sources;
        try (Stream<Path> stream = Files.list(entityDir)) {
            sources = stream.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList();
        }

        for (Path source : sources) {
            String simpleName = source.getFileName().toString().replace(".java", "");
            Class<?> type;
            try {
                type = Class.forName(ENTITY_PACKAGE + "." + simpleName);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("实体类无法加载：" + simpleName, e);
            }
            TableName annotation = type.getAnnotation(TableName.class);
            if (annotation != null) {
                tables.put(simpleName, annotation.value());
            }
        }
        return tables;
    }

    /** 实体字段名 -> 数据库列名（排除 @TableField(exist=false) 与静态字段）。 */
    private static Map<String, String> mappedColumns(String simpleClassName) {
        Class<?> type;
        try {
            type = Class.forName(ENTITY_PACKAGE + "." + simpleClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("实体类无法加载：" + simpleClassName, e);
        }

        Map<String, String> columns = new TreeMap<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            TableField tableField = field.getAnnotation(TableField.class);
            if (tableField != null && !tableField.exist()) {
                continue;
            }
            if (tableField != null && !tableField.value().isEmpty()) {
                columns.put(field.getName(), tableField.value());
                continue;
            }
            TableId tableId = field.getAnnotation(TableId.class);
            if (tableId != null && !tableId.value().isEmpty()) {
                columns.put(field.getName(), tableId.value());
                continue;
            }
            columns.put(field.getName(), camelToSnake(field.getName()));
        }
        return columns;
    }

    /** typeId -> type_id（MyBatis-Plus 默认 map-underscore-to-camel-case 的逆向）。 */
    private static String camelToSnake(String name) {
        StringBuilder snake = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    snake.append('_');
                }
                snake.append(Character.toLowerCase(c));
            } else {
                snake.append(c);
            }
        }
        return snake.toString();
    }
}
