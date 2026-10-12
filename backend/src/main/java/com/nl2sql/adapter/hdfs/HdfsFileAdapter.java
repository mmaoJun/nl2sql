package com.nl2sql.adapter.hdfs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.adapter.DataSourceAdapter;
import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.TableInfo;
import com.nl2sql.model.dto.QueryResult;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * HDFS 文件数据源适配器（WebHDFS 拉取 + H2 内存表执行 SQL）。
 *
 * <p>面向的是 HDFS 上的<b>裸 CSV 文件</b>（如 {@code /demo/orders/hive_orders.csv}），
 * 不经过 Hive/Spark：元数据与数据全部经 WebHDFS REST 获取，SQL 由 H2 内存库执行。
 * 直接实现 {@link DataSourceAdapter}（非 JDBC 系），方法语义与
 * {@code JdbcDataSourceAdapter} 对齐：connect/testConnection 返回可用性布尔、
 * getTables/getColumns 失败时返回空列表并记日志、executeQuery 失败时返回
 * {@code success=false} 的 {@link QueryResult} 而非抛异常。
 *
 * <p><b>配置语义</b>（与 datasources 表字段的映射）：
 * <ul>
 *   <li>{@code host} + {@code port} → WebHDFS 基址 {@code http://{host}:{port}/webhdfs/v1}；</li>
 *   <li>{@code databaseName} → 根目录（如 {@code /demo}），表列表即该目录下递归的 {@code *.csv}；</li>
 *   <li>{@code username} → WebHDFS {@code user.name} 参数，缺省 {@value #DEFAULT_USERNAME}；</li>
 *   <li>编码固定 UTF-8、分隔符固定逗号（本任务范围，编码经含中文城市名的往返用例验证）；</li>
 *   <li>{@code password} 不使用（HDFS simple 认证）。</li>
 * </ul>
 *
 * <p><b>表名映射</b>：文件名去 {@code .csv} 后缀即表名（不区分大小写查找），
 * 目录结构不影响表名。当前环境 CSV 位于 {@code /demo} 的两级子目录
 * （{@code /demo/orders/hive_orders.csv}），故 {@link #collectCsvFiles} 对根目录做
 * <b>客户端递归</b>——实测该 Hadoop 3.4.1 的 WebHDFS {@code LISTSTATUS&recursive=true}
 * 只返回一层子目录、不展开子树，必须逐层 LISTSTATUS。
 *
 * <p><b>执行模型</b>：每次 {@link #executeQuery} 用 JSqlParser 提取 SQL 中的表 →
 * 逐表经 WebHDFS OPEN 拉到临时文件 → 新建一次性 H2 内存库
 * （{@code jdbc:h2:mem:nl2sql_hdfs_<uuid>;DB_CLOSE_DELAY=-1}，用完 DROP ALL OBJECTS +
 * SHUTDOWN 释放）→ 建表并执行 → 查完即弃。不做跨查询缓存（演示文件很小，如需缓存
 * 按 path+mtime 记）。
 *
 * <p><b>建表方式</b>：{@code CREATE TABLE ... AS SELECT} + {@code CSVREAD}。
 * CSVREAD 的列定义参数实测不支持类型（第二参数按列名列表解析）、也没有 skip 选项，
 * 且不指定列名时所有列都是 VARCHAR（实测 H2 2.2.224，SUM/AVG 直接报 90015）。
 * 因此采用两段式：CSVREAD 读原始 VARCHAR 列 → 在 SELECT 中对每列按推断类型
 * {@code CAST(...) AS "列名"}，得到有类型的表（数值列可聚合、日期列可比较）。
 *
 * @see DataSourceAdapter
 */
@Slf4j
public class HdfsFileAdapter implements DataSourceAdapter {

    /** WebHDFS user.name 缺省值（演示环境 HDFS 关闭权限校验，任意用户名可访问）。 */
    static final String DEFAULT_USERNAME = "hadoop";

    /** CSV 编码（本任务固定，中文城市名依赖 UTF-8 往返正确）。 */
    static final String DEFAULT_ENCODING = "UTF-8";

    /** CSV 字段分隔符（本任务固定逗号）。 */
    static final String DEFAULT_SEPARATOR = ",";

    /** CSV 文件后缀（大小写不敏感）。 */
    private static final String CSV_SUFFIX = ".csv";

    /** WebHDFS OPEN 首段拉取字节数：覆盖表头 + 前若干数据行，供列名与类型推断。 */
    private static final int HEAD_FETCH_BYTES = 64 * 1024;

    /** 类型推断最多采样的数据行数。 */
    private static final int INFER_SAMPLE_ROWS = 100;

    /** 目录递归最大深度（防御异常目录树）。 */
    private static final int MAX_DIR_DEPTH = 5;

    /** 连接探测超时（毫秒）。 */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /** 单次 HTTP 请求超时（秒）。 */
    private static final int REQUEST_TIMEOUT_S = 30;

    /** H2 查询超时（秒），与 JDBC 适配器默认值对齐。 */
    private static final int QUERY_TIMEOUT_S = 10;

    /** 整数列形态（负号可选）。 */
    private static final Pattern INT_PATTERN = Pattern.compile("-?\\d+");

    /** 数值列形态（含小数）。 */
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("-?\\d+(\\.\\d+)?");

    /** 日期列形态（yyyy-MM-dd）。 */
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** 数值列统一落到的 H2 类型：整数 BIGINT（避免 INT 溢出），小数 DECIMAL(20,6)。 */
    private static final String H2_BIGINT = "BIGINT";

    private static final String H2_DECIMAL = "DECIMAL(20,6)";

    private static final String H2_DATE = "DATE";

    private static final String VARCHAR = "VARCHAR";

    /** 共享 HTTP 客户端：手动跟随重定向（307 Location 需改写 host，见 {@link #get}）。 */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** 连接配置解析结果（connect 之后有效）。 */
    private Settings settings;

    /** {@inheritDoc} */
    @Override
    public boolean connect(Map<String, Object> config) {
        this.settings = parse(config);
        try {
            return listStatus(this.settings, this.settings.root()) != null;
        } catch (Exception e) {
            log.error("hdfs 连接失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void disconnect() {
        // 无持久连接/连接池：每次查询自建临时 H2 内存库，HTTP 为短连接
        this.settings = null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean testConnection(Map<String, Object> config) {
        try {
            Settings probe = parse(config);
            return listStatus(probe, probe.root()) != null;
        } catch (Exception e) {
            log.error("hdfs 连接测试失败: {}", e.getMessage());
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<TableInfo> getTables() {
        List<TableInfo> tables = new ArrayList<>();
        try {
            for (CsvFile file : collectCsvFiles()) {
                TableInfo table = new TableInfo();
                table.setTableName(file.tableName());
                table.setComment(file.length() + " 字节 · 更新 "
                        + TIME_FORMATTER.format(Instant.ofEpochMilli(file.modificationTime()))
                        + " · " + file.path());
                table.setColumns(getColumns(file.tableName()));
                tables.add(table);
            }
        } catch (Exception e) {
            log.error("获取表列表失败: {}", e.getMessage(), e);
        }
        return tables;
    }

    /** {@inheritDoc} */
    @Override
    public List<ColumnInfo> getColumns(String tableName) {
        CsvFile file = findCsvFile(tableName);
        if (file == null) {
            log.warn("表 {} 不存在（根目录 {} 下无对应 .csv 文件）", tableName,
                    settings == null ? null : settings.root());
            return List.of();
        }
        try {
            // 只拉首段：表头（首行）即列名，其余行用于类型推断
            byte[] head = fetchBytes("OPEN", file.path(),
                    "&offset=0&length=" + HEAD_FETCH_BYTES, true);
            List<String> lines = splitLines(head, head.length >= HEAD_FETCH_BYTES);
            if (lines.isEmpty()) {
                return List.of();
            }
            List<String> header = parseHeader(lines.get(0));
            List<List<String>> samples = parseSampleRows(lines, INFER_SAMPLE_ROWS);
            List<String> types = inferTypes(header, samples);
            List<ColumnInfo> columns = new ArrayList<>();
            for (int i = 0; i < header.size(); i++) {
                ColumnInfo col = new ColumnInfo();
                col.setColumnName(header.get(i));
                col.setDataType(types.get(i));
                col.setColumnType(types.get(i));
                col.setComment("");
                col.setColumnKey("");
                col.setIsNullable("YES");
                columns.add(col);
            }
            return columns;
        } catch (Exception e) {
            log.error("获取表 {} 字段失败: {}", tableName, e.getMessage(), e);
            return List.of();
        }
    }

    /** {@inheritDoc} */
    @Override
    public QueryResult executeQuery(String sql, int limit) {
        QueryResult result = new QueryResult();
        long startTime = System.currentTimeMillis();

        if (settings == null) {
            result.setSuccess(false);
            result.setErrorMessage("适配器未连接，请先调用 connect");
            result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
            return result;
        }

        String dbUrl = "jdbc:h2:mem:nl2sql_hdfs_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1";
        try {
            // SQL 中的表引用 → 按需只拉取被引用的 CSV（JSqlParser 提取）
            List<CsvFile> toLoad = resolveReferencedFiles(extractTableNames(sql));            try (Connection conn = DriverManager.getConnection(dbUrl, "sa", "")) {
                for (CsvFile file : toLoad) {
                    loadTable(conn, file);
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
                        int count = 0;
                        while (rs.next() && count < limit) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= columnCount; i++) {
                                row.put(columns.get(i - 1), rs.getObject(i));
                            }
                            rows.add(row);
                            count++;
                        }
                        result.setRows(rows);
                        result.setRowCount(rows.size());
                        result.setSuccess(true);
                    }
                }
            }
        } catch (Exception e) {
            log.error("SQL 执行失败(hdfs): {}", e.getMessage(), e);
            result.setSuccess(false);
            result.setErrorMessage("查询执行失败: " + e.getMessage());
        } finally {
            dropH2Database(dbUrl);
        }

        result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public String getDialect() {
        // SQL 实际由 H2 执行（LIMIT 等语法按 H2），而非 Hive/Spark SQL
        return "h2";
    }

    /**
     * 把一张 CSV 文件载入 H2 内存库为表。
     *
     * <p>拉全量文件到临时文件（演示数据很小；用完即删），解析表头与采样行做类型推断，
     * 以 {@code CREATE TABLE AS SELECT ... CSVREAD(...)} 建表；类型推断万一导致
     * CAST 转换失败，退回全 VARCHAR 再试一次，保证可读性优先。
     *
     * @param conn H2 连接
     * @param file 目标 CSV 文件
     * @throws Exception 拉取或建表失败
     */
    private void loadTable(Connection conn, CsvFile file) throws Exception {
        byte[] data = fetchBytes("OPEN", file.path(), "", false);
        List<String> lines = splitLines(data, false);
        if (lines.isEmpty()) {
            throw new IllegalStateException("CSV 文件为空: " + file.path());
        }
        List<String> header = parseHeader(lines.get(0));
        List<List<String>> samples = parseSampleRows(lines, INFER_SAMPLE_ROWS);

        Path tempFile = Files.createTempFile("nlp2sql-hdfs-", ".csv");
        try {
            Files.write(tempFile, data);
            List<String> types = inferTypes(header, samples);
            String createSql = buildCreateTableSql(file.tableName(), header, types, tempFile);
            try (java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute(createSql);
            } catch (java.sql.SQLException e) {
                log.warn("按推断类型建表失败（{}），退回全 VARCHAR 重试: {}", file.path(), e.getMessage());
                String fallback = buildCreateTableSql(file.tableName(), header,
                        header.stream().map(h -> VARCHAR).toList(), tempFile);
                try (java.sql.Statement stmt = conn.createStatement()) {
                    stmt.execute(fallback);
                }
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * 生成 {@code CREATE TABLE "T" AS SELECT ...} 语句。
     *
     * <p>CSVREAD 不指定列名时首行即列名、所有列 VARCHAR；对推断为非 VARCHAR 的列在
     * SELECT 中包一层 CAST 并显式取大写别名——H2 对不指定列名读出的列统一存为大写，
     * 与不带引号的 SQL 引用（`SELECT city FROM ...`）大小写归一后一致。
     *
     * @param tableName CSV 文件名（去 .csv）
     * @param header    表头列名
     * @param types     与表头一一对应的推断类型
     * @param csvPath   本地临时 CSV 路径
     * @return 建表 SQL
     */
    private static String buildCreateTableSql(String tableName, List<String> header,
                                              List<String> types, Path csvPath) {
        StringBuilder select = new StringBuilder();
        for (int i = 0; i < header.size(); i++) {
            if (i > 0) {
                select.append(", ");
            }
            String quoted = quoteIdentifier(header.get(i));
            if (VARCHAR.equals(types.get(i))) {
                select.append(quoted);
            } else {
                select.append("CAST(").append(quoted).append(" AS ").append(types.get(i))
                        .append(") AS ").append(quoted);
            }
        }
        String path = csvPath.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
        return "CREATE TABLE " + quoteIdentifier(tableName) + " AS SELECT " + select
                + " FROM CSVREAD('" + path + "', NULL, 'charset=" + DEFAULT_ENCODING + "')";
    }

    /**
     * 按采样行推断列类型：全部整数 → BIGINT；全部数值 → DECIMAL(20,6)；
     * 全部 yyyy-MM-dd → DATE；其余（含无采样值、混合形态）→ VARCHAR。
     *
     * <p>"全部匹配才升级类型"是刻意收紧：只要出现一个无法安全转换的值就保持 VARCHAR，
     * 避免个别脏值让整表建表失败；整数用 BIGINT 规避 INT 溢出。
     *
     * @param header  表头
     * @param samples 采样数据行
     * @return 与表头一一对应的类型列表
     */
    private static List<String> inferTypes(List<String> header, List<List<String>> samples) {
        List<String> types = new ArrayList<>();
        for (int col = 0; col < header.size(); col++) {
            List<String> values = new ArrayList<>();
            for (List<String> row : samples) {
                if (col < row.size() && !row.get(col).isEmpty()) {
                    values.add(row.get(col));
                }
            }
            if (values.isEmpty()) {
                types.add(VARCHAR);
            } else if (values.stream().allMatch(v -> INT_PATTERN.matcher(v).matches())) {
                types.add(H2_BIGINT);
            } else if (values.stream().allMatch(v -> DECIMAL_PATTERN.matcher(v).matches())) {
                types.add(H2_DECIMAL);
            } else if (values.stream().allMatch(v -> DATE_PATTERN.matcher(v).matches())) {
                types.add(H2_DATE);
            } else {
                types.add(VARCHAR);
            }
        }
        return types;
    }

    /**
     * 用 JSqlParser 提取 SQL 引用的表名。
     *
     * @param sql 用户 SQL
     * @return 原始表名列表（可能带库名前缀/引号）
     * @throws IllegalStateException SQL 无法解析时抛出（提示信息回传上层工具）
     */
    private static List<String> extractTableNames(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            return new TablesNamesFinder().getTableList(statement);
        } catch (Exception e) {
            throw new IllegalStateException("SQL 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把 SQL 引用的表名解析为 CSV 文件列表（去重、保持 SQL 中的出现顺序）。
     *
     * <p>表名归一化：去引号 → 取最后一段（容忍 {@code db.table} 前缀）→ 不区分大小写匹配
     * 文件名。匹配不到的文件跳过，交给 H2 在执行时报"表不存在"（错误信息更贴近用户 SQL）。
     *
     * @param tableRefs JSqlParser 提取的原始表名
     * @return 需要载入的 CSV 文件
     * @throws Exception 目录列举失败
     */
    private List<CsvFile> resolveReferencedFiles(List<String> tableRefs) throws Exception {
        Map<String, CsvFile> byName = new LinkedHashMap<>();
        for (CsvFile file : collectCsvFiles()) {
            byName.putIfAbsent(file.tableName().toLowerCase(Locale.ROOT), file);
        }
        Map<String, CsvFile> ordered = new LinkedHashMap<>();
        for (String ref : tableRefs) {
            String normalized = normalizeTableName(ref);
            CsvFile file = byName.get(normalized);
            if (file == null) {
                log.warn("SQL 引用的表 {} 在根目录 {} 下无对应 .csv 文件，交由 H2 报错", ref, settings.root());
                continue;
            }
            ordered.putIfAbsent(file.path(), file);
        }
        return new ArrayList<>(ordered.values());
    }

    private static String normalizeTableName(String ref) {
        String name = ref == null ? "" : ref.trim();
        name = name.replace("`", "").replace("\"", "").replace("'", "");
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 递归枚举根目录下的全部 CSV 文件（视图内的"表"）。
     *
     * <p>客户端递归原因见类注释：该集群 {@code LISTSTATUS&recursive=true} 不展开子树。
     * 同名文件（不同子目录）按路径排序取先者并告警，保证确定性。
     *
     * @return 按路径排序的 CSV 文件列表
     * @throws Exception 目录列举失败或超出深度限制
     */
    private List<CsvFile> collectCsvFiles() throws Exception {
        List<CsvFile> files = new ArrayList<>();
        walkCsvFiles(settings, settings.root(), 0, files);
        files.sort(Comparator.comparing(CsvFile::path));
        return files;
    }

    private void walkCsvFiles(Settings settings, String dir, int depth, List<CsvFile> out) throws Exception {
        if (depth > MAX_DIR_DEPTH) {
            throw new IllegalStateException("目录层级超过 " + MAX_DIR_DEPTH + " 层: " + dir);
        }
        JsonNode statuses = listStatus(settings, dir);
        if (statuses == null) {
            return;
        }
        for (JsonNode entry : statuses) {
            String name = entry.path("pathSuffix").asText();
            String fullPath = joinPath(dir, name);
            if ("DIRECTORY".equals(entry.path("type").asText())) {
                walkCsvFiles(settings, fullPath, depth + 1, out);
            } else if (name.toLowerCase(Locale.ROOT).endsWith(CSV_SUFFIX)) {
                out.add(new CsvFile(fullPath,
                        name.substring(0, name.length() - CSV_SUFFIX.length()),
                        entry.path("length").asLong(),
                        entry.path("modificationTime").asLong()));
            }
        }
    }

    /**
     * 按表名查找 CSV 文件（不区分大小写；同名取路径排序先者，与 {@link #collectCsvFiles} 一致）。
     *
     * @param tableName 表名
     * @return 对应文件；不存在时返回 null
     */
    private CsvFile findCsvFile(String tableName) {
        if (settings == null || !StringUtils.hasText(tableName)) {
            return null;
        }
        String target = normalizeTableName(tableName);
        try {
            for (CsvFile file : collectCsvFiles()) {
                if (file.tableName().toLowerCase(Locale.ROOT).equals(target)) {
                    return file;
                }
            }
        } catch (Exception e) {
            log.error("查找文件失败: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * WebHDFS GET 请求（含 307 重定向跟随）。
     *
     * @param settings     连接配置
     * @param op           操作名（LISTSTATUS / OPEN）
     * @param path         HDFS 路径（如 /demo/orders/hive_orders.csv）
     * @param extraQuery   追加查询参数（如 &offset=0&length=65536），可为空
     * @param notFoundAsNull true 时 HTTP 404 返回 null（不抛异常）
     * @return 响应体字节；notFoundAsNull 且 404 时返回 null
     * @throws Exception 非 2xx（或 404 且非 notFoundAsNull）或协议错误
     */
    private byte[] get(Settings settings, String op, String path, String extraQuery,
                       boolean notFoundAsNull) throws Exception {
        String url = "http://" + settings.host() + ":" + settings.port() + "/webhdfs/v1" + path
                + "?op=" + op + "&user.name=" + URLEncoder.encode(settings.user(), StandardCharsets.UTF_8)
                + (extraQuery == null ? "" : extraQuery);
        HttpResponse<byte[]> response = send(URI.create(url));
        int status = response.statusCode();

        if (status == 307 || status == 302 || status == 301) {
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IllegalStateException("WebHDFS 重定向缺少 Location 头"));
            URI redirected = URI.create(location);
            // ===== 关键决策：重定向 host 替换 =====
            // OPEN 的 Location 指向 DataNode，形如 http://datanode:9864/webhdfs/v1/...，
            // host 是容器主机名（datanode），宿主机不可解析。而 DataNode 端口 9864 与
            // 外部一致且已发布到宿主机，故把 Location 的 scheme/host 替换为适配器配置的
            // host（保持原 port 与 path/query 不变）而非按原样跟随。
            URI rewritten = new URI("http", null, settings.host(), redirected.getPort(),
                    redirected.getPath(), redirected.getQuery(), null);
            log.debug("WebHDFS 重定向 host 替换: {} -> {}", redirected.getAuthority(), rewritten.getAuthority());
            response = send(rewritten);
            status = response.statusCode();
        }

        if (notFoundAsNull && status == 404) {
            return null;
        }
        if (status / 100 != 2) {
            throw new IllegalStateException("WebHDFS " + op + " " + path + " 返回 HTTP " + status);
        }
        return response.body();
    }

    private static HttpResponse<byte[]> send(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    /** 便捷封装：LISTSTATUS 返回 FileStatus 数组；根目录不存在返回 null。 */
    private JsonNode listStatus(Settings settings, String dir) throws Exception {
        byte[] body = get(settings, "LISTSTATUS", normalizeDir(dir), "", true);
        if (body == null) {
            return null;
        }
        JsonNode fileStatuses = MAPPER.readTree(body).path("FileStatuses").path("FileStatus");
        return fileStatuses.isArray() ? fileStatuses : null;
    }

    /** 便捷封装：OPEN 拉取字节（指定长度 + notFoundAsNull）。 */
    private byte[] fetchBytes(String op, String path, String extraQuery, boolean notFoundAsNull) throws Exception {
        byte[] body = get(settings, op, path, extraQuery, notFoundAsNull);
        if (body == null) {
            throw new IllegalStateException("HDFS 文件不存在: " + path);
        }
        return body;
    }

    /** 解析连接配置。 */
    private static Settings parse(Map<String, Object> config) {
        String host = config == null ? null : String.valueOf(config.get("host"));
        if (!StringUtils.hasText(host) || "null".equals(host)) {
            throw new IllegalArgumentException("hdfs 数据源缺少 host 配置");
        }
        Object portValue = config.get("port");
        int port = portValue instanceof Number number ? number.intValue()
                : (portValue == null || String.valueOf(portValue).isBlank()
                        ? 9870 : Integer.parseInt(String.valueOf(portValue)));
        String username = config.get("username") == null ? null : String.valueOf(config.get("username"));
        String user = StringUtils.hasText(username) && !"null".equals(username)
                ? username : DEFAULT_USERNAME;
        String database = config.get("databaseName") == null ? null
                : String.valueOf(config.get("databaseName"));
        String root = (database == null || database.isBlank() || "null".equals(database))
                ? "/" : normalizeDir(database);
        return new Settings(host, port, root, user);
    }

    /** 归一化目录路径：确保以 / 开头、去掉末尾 /；根目录保持 "/"。 */
    private static String normalizeDir(String dir) {
        if (dir == null || dir.isBlank() || "/".equals(dir.trim())) {
            return "/";
        }
        String normalized = dir.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String joinPath(String dir, String name) {
        return "/".equals(dir) ? "/" + name : dir + "/" + name;
    }

    /**
     * 按行切分（剔除可能的 \r 与 BOM）。
     *
     * @param bytes         原始字节
     * @param truncatedHint 拉取时是否可能截断（bytes 达到请求上限）：真时丢弃最后一行
     *                      （可能是被截半的行），避免脏样本干扰类型推断
     */
    private static List<String> splitLines(byte[] bytes, boolean truncatedHint) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        String[] parts = text.split("\n", -1);
        List<String> lines = new ArrayList<>();
        for (String part : parts) {
            lines.add(part.endsWith("\r") ? part.substring(0, part.length() - 1) : part);
        }
        // split 尾部的空段（文件以换行结尾）不算数据行
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        if (truncatedHint && !lines.isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /** 解析表头行 → 列名列表。 */
    private static List<String> parseHeader(String line) {
        return parseCsvLine(line);
    }

    /** 取文件的前 n 个数据行（表头之后）做类型推断采样。 */
    private static List<List<String>> parseSampleRows(List<String> lines, int n) {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size() && rows.size() < n; i++) {
            if (!lines.get(i).isBlank()) {
                rows.add(parseCsvLine(lines.get(i)));
            }
        }
        return rows;
    }

    /**
     * 极简 CSV 行解析：逗号分隔，支持双引号包裹字段（"" 转义）。
     * 与 CSVREAD 行为在演示数据上一致；仅用于表头/类型推断采样，不参与最终数据读取。
     */
    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == DEFAULT_SEPARATOR.charAt(0)) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    /** H2 标识符引用：大写归一 + 双引号包裹（与 CSVREAD 生成列名、无引号 SQL 引用均对齐）。 */
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

    /** 连接配置解析结果。 */
    private record Settings(String host, int port, String root, String user) {
    }

    /** 根目录下发现的一个 CSV 文件（即视图中的一张"表"）。 */
    private record CsvFile(String path, String tableName, long length, long modificationTime) {
    }
}
