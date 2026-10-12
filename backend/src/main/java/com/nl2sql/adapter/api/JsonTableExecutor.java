package com.nl2sql.adapter.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.dto.QueryResult;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * JSON 行集 → H2 内存表 → 执行 SQL（REST / GraphQL 适配器共用）。
 *
 * <p>思路与 T22 的 HDFS 适配器一致：既然目标是「让模型用 SQL 查询 API 返回的数据」，
 * 就把行集按推断类型装入一次性 H2 内存表（数值列保证 SUM/COUNT 可用），
 * SQL 由 H2 执行、查完即弃；不做跨查询缓存。
 *
 * <p><b>类型推断</b>（全列值均匹配才升级类型，与 T22 同口径）：
 * 全整数 → {@code BIGINT}；全数值（含小数）→ {@code DECIMAL(20,6)}；
 * 全 {@code yyyy-MM-dd} 文本 → {@code DATE}；其余（含布尔、嵌套对象/数组、空列）→ {@code VARCHAR}。
 * 嵌套对象/数组在行集里序列化为 JSON 字符串存储（报告契约）。
 */
@Slf4j
final class JsonTableExecutor {

    /** H2 查询超时（秒），与 JDBC/HDFS 适配器默认值对齐。 */
    private static final int QUERY_TIMEOUT_S = 10;

    /** 类型推断最多采样的行数。 */
    private static final int INFER_SAMPLE_ROWS = 100;

    private static final Pattern INT_PATTERN = Pattern.compile("-?\\d+");

    private static final Pattern DECIMAL_PATTERN = Pattern.compile("-?\\d+(\\.\\d+)?");

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final String H2_BIGINT = "BIGINT";

    private static final String H2_DECIMAL = "DECIMAL(20,6)";

    private static final String H2_DATE = "DATE";

    private static final String VARCHAR = "VARCHAR";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 表取数函数：按表声明拉取 JSON 行集（REST / GraphQL 各自实现）。 */
    @FunctionalInterface
    interface TableFetcher {
        List<Map<String, JsonNode>> fetch(ApiConfig.ApiTable table) throws Exception;
    }

    private JsonTableExecutor() {
    }

    /** 当前数据源已声明的表名列表（引导式报错用）。 */
    private static List<String> configTableNames(ApiConfig config) {
        return config.tables().stream().map(ApiConfig.ApiTable::name).toList();
    }

    /**
     * 执行 SQL：JSqlParser 提取被引用表 → 逐表拉 JSON → 装入 H2 内存表 → 执行。
     *
     * @param sql     只读 SQL（上层已保证）
     * @param limit   最大返回行数（<=0 不限制）
     * @param config  连接配置（表声明匹配用）
     * @param fetcher 表取数函数
     * @return 查询结果（失败返回 success=false 而非抛异常，与 JDBC 适配器语义一致）
     */
    static QueryResult executeQuery(String sql, int limit, ApiConfig config, TableFetcher fetcher) {
        QueryResult result = new QueryResult();
        long startTime = System.currentTimeMillis();
        String dbUrl = "jdbc:h2:mem:nl2sql_api_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1";
        try {
            List<String> tableRefs = extractTableNames(sql);
            Map<String, ApiConfig.ApiTable> toLoad = new LinkedHashMap<>();
            List<String> unknownRefs = new ArrayList<>();
            for (String ref : tableRefs) {
                ApiConfig.ApiTable table = config.findTable(ref);
                if (table == null) {
                    unknownRefs.add(ref);
                    continue;
                }
                toLoad.putIfAbsent(ApiConfig.normalizeName(table.name()), table);
            }
            if (!unknownRefs.isEmpty()) {
                // 引导式报错（T23 实测）：小模型会把历史/其他数据源的表名当本数据源的表去 JOIN，
                // 原始 H2 报错对它没有引导性——错误的 SQL 计划被拒后应指出"可用表 + 跨源不可 JOIN"，
                // 而不是让它反复重探把迭代预算耗光。
                result.setSuccess(false);
                result.setErrorMessage("表 " + unknownRefs + " 不在当前数据源中。本数据源可用表: "
                        + configTableNames(config) + "；系统不支持跨数据源 JOIN，"
                        + "其他数据源或历史轮次的数据请直接引用其数值进行对比。");
                result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
                return result;
            }

            try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "")) {
                for (ApiConfig.ApiTable table : toLoad.values()) {
                    loadTable(conn, table.name(), fetcher.fetch(table));
                }
                try (java.sql.Statement stmt = conn.createStatement()) {
                    if (limit > 0) {
                        stmt.setMaxRows(limit);
                    }
                    stmt.setQueryTimeout(QUERY_TIMEOUT_S);
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        ResultSetMetaData metaData = rs.getMetaData();
                        int columnCount = metaData.getColumnCount();
                        List<String> columns = new ArrayList<>();
                        for (int i = 1; i <= columnCount; i++) {
                            columns.add(metaData.getColumnLabel(i));
                        }
                        result.setColumns(columns);

                        List<Map<String, Object>> rows = new ArrayList<>();
                        while (rs.next() && (limit <= 0 || rows.size() < limit)) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= columnCount; i++) {
                                row.put(columns.get(i - 1), rs.getObject(i));
                            }
                            rows.add(row);
                        }
                        result.setRows(rows);
                        result.setRowCount(rows.size());
                        result.setSuccess(true);
                    }
                }
            }
        } catch (Exception e) {
            log.error("SQL 执行失败(api): {}", e.getMessage(), e);
            result.setSuccess(false);
            result.setErrorMessage("查询执行失败: " + e.getMessage());
        } finally {
            dropH2Database(dbUrl);
        }
        result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
        return result;
    }

    /**
     * 按行集推断列信息（getColumns 与建表共用一套口径，保证「展示类型 == 执行类型」）。
     *
     * @param rows JSON 行集（可为空）
     * @return 列信息列表（列名保持 JSON 原始大小写）
     */
    static List<ColumnInfo> inferColumns(List<Map<String, JsonNode>> rows) {
        List<ColumnInfo> columns = new ArrayList<>();
        for (Map.Entry<String, String> entry : inferColumnTypes(rows).entrySet()) {
            ColumnInfo column = new ColumnInfo();
            column.setColumnName(entry.getKey());
            column.setDataType(entry.getValue());
            column.setColumnType(entry.getValue());
            column.setComment("");
            column.setColumnKey("");
            column.setIsNullable("YES");
            columns.add(column);
        }
        return columns;
    }

    /** 推断列类型：LinkedHashMap 保持「首次出现顺序」。 */
    private static Map<String, String> inferColumnTypes(List<Map<String, JsonNode>> rows) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Map<String, JsonNode> row : rows) {
            names.addAll(row.keySet());
        }
        Map<String, String> types = new LinkedHashMap<>();
        for (String name : names) {
            List<JsonNode> values = new ArrayList<>();
            for (Map<String, JsonNode> row : rows) {
                if (values.size() >= INFER_SAMPLE_ROWS) {
                    break;
                }
                values.add(row.get(name));
            }
            types.put(name, inferType(values));
        }
        return types;
    }

    /** 单列类型推断（全列值均匹配才升级）。 */
    private static String inferType(List<JsonNode> values) {
        List<JsonNode> nonNull = values.stream()
                .filter(v -> v != null && !v.isNull() && !v.isMissingNode())
                .toList();
        if (nonNull.isEmpty()) {
            return VARCHAR;
        }
        if (nonNull.stream().allMatch(JsonTableExecutor::isIntegral)) {
            return H2_BIGINT;
        }
        if (nonNull.stream().allMatch(JsonTableExecutor::isNumeric)) {
            return H2_DECIMAL;
        }
        if (nonNull.stream().allMatch(JsonTableExecutor::isDateText)) {
            return H2_DATE;
        }
        return VARCHAR;
    }

    private static boolean isIntegral(JsonNode node) {
        return node.isIntegralNumber()
                || (node.isTextual() && INT_PATTERN.matcher(node.asText()).matches());
    }

    private static boolean isNumeric(JsonNode node) {
        return node.isNumber()
                || (node.isTextual() && DECIMAL_PATTERN.matcher(node.asText()).matches());
    }

    private static boolean isDateText(JsonNode node) {
        return node.isTextual() && DATE_PATTERN.matcher(node.asText()).matches();
    }

    /**
     * 把一行集装入 H2 内存表为一张表（表名与列名统一大写 + 双引号，对齐 H2 的
     * 无引号引用；空行集时报清晰错误）。
     */
    private static void loadTable(Connection conn, String tableName, List<Map<String, JsonNode>> rows) throws Exception {
        Map<String, String> columnTypes = inferColumnTypes(rows);
        if (columnTypes.isEmpty()) {
            throw new IllegalStateException("表 " + tableName + " 的响应为空，无法推断列结构");
        }
        StringBuilder createSql = new StringBuilder("CREATE TABLE ").append(quoteIdentifier(tableName)).append(" (");
        boolean first = true;
        for (Map.Entry<String, String> entry : columnTypes.entrySet()) {
            if (!first) {
                createSql.append(", ");
            }
            first = false;
            createSql.append(quoteIdentifier(entry.getKey())).append(' ').append(entry.getValue());
        }
        createSql.append(')');
        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(createSql.toString());
        }

        if (rows.isEmpty()) {
            return;
        }
        StringBuilder insertSql = new StringBuilder("INSERT INTO ").append(quoteIdentifier(tableName)).append(" (");
        StringBuilder placeholders = new StringBuilder();
        first = true;
        for (String column : columnTypes.keySet()) {
            if (!first) {
                insertSql.append(", ");
                placeholders.append(", ");
            }
            first = false;
            insertSql.append(quoteIdentifier(column));
            placeholders.append('?');
        }
        insertSql.append(") VALUES (").append(placeholders).append(')');

        try (PreparedStatement ps = conn.prepareStatement(insertSql.toString())) {
            for (Map<String, JsonNode> row : rows) {
                int index = 1;
                for (Map.Entry<String, String> entry : columnTypes.entrySet()) {
                    bind(ps, index++, entry.getValue(), row.get(entry.getKey()));
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 按推断类型绑定参数值。 */
    private static void bind(PreparedStatement ps, int index, String type, JsonNode value) throws Exception {
        if (value == null || value.isNull() || value.isMissingNode()) {
            ps.setNull(index, Types.VARCHAR);
            return;
        }
        switch (type) {
            case H2_BIGINT -> ps.setLong(index, Long.parseLong(value.asText()));
            case H2_DECIMAL -> ps.setBigDecimal(index, new BigDecimal(value.asText()));
            case H2_DATE -> ps.setDate(index, java.sql.Date.valueOf(value.asText()));
            default -> ps.setString(index, toStorageText(value));
        }
    }

    /** 标量转存储文本；嵌套对象/数组序列化为 JSON 字符串。 */
    private static String toStorageText(JsonNode value) throws Exception {
        if (value.isObject() || value.isArray()) {
            return MAPPER.writeValueAsString(value);
        }
        return value.asText();
    }

    /**
     * JSqlParser 提取 SQL 引用的表名。
     *
     * @throws IllegalStateException SQL 无法解析时抛出（消息回传上层工具）
     */
    private static List<String> extractTableNames(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            return new TablesNamesFinder().getTableList(statement);
        } catch (Exception e) {
            throw new IllegalStateException("SQL 解析失败: " + e.getMessage(), e);
        }
    }

    /** H2 标识符引用：大写归一 + 双引号包裹（与无引号 SQL 引用大小写归一后一致）。 */
    private static String quoteIdentifier(String name) {
        String normalized = name.trim().toUpperCase(Locale.ROOT).replace("\"", "\"\"");
        return "\"" + normalized + "\"";
    }

    /** 用完即弃：清空并关停该次查询的 H2 内存库。 */
    private static void dropH2Database(String dbUrl) {
        try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "");
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DROP ALL OBJECTS");
            stmt.execute("SHUTDOWN");
        } catch (Exception e) {
            log.debug("清理临时 H2 内存库失败（可忽略）: {}", e.getMessage());
        }
    }
}
