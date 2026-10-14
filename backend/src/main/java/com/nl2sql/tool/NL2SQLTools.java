package com.nl2sql.tool;

import com.nl2sql.adapter.DataSourceAdapter;
import com.nl2sql.adapter.DataSourceFactory;
import com.nl2sql.common.BusinessException;
import com.nl2sql.common.ErrorCode;
import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.TableInfo;
import com.nl2sql.model.dto.QueryResult;
import com.nl2sql.security.SqlSecurityChecker;
import com.nl2sql.security.SqlSecurityChecker.ValidationResult;
import com.nl2sql.service.DataSourceService;
import com.nl2sql.service.MetadataService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * NL2SQL Agent 的 5 个只读工具（W2 检查点：工具可独立调用）。
 *
 * <p>供 {@code Toolkit.registerTool} 反射注册，方法参数名即对 LLM 暴露的参数名，
 * 因此依赖编译期 {@code -parameters}（spring-boot-starter-parent 已默认开启）。
 *
 * <p><b>只读保证</b>：5 个工具全部标注 {@code readOnly = true}，且不依赖模型自觉：
 * {@link #sampleData} 与 {@link #executeSql} 在交给适配器之前，一律先过
 * {@link SqlSecurityChecker#validate}，再 {@link SqlSecurityChecker#enforceLimit}，
 * 顺序固定，模型跳过 validate_sql 也无法绕过。
 *
 * <p><b>参数拼接口</b>：{@code sample_data} 的表名需要拼进 SQL，是唯一由模型文本
 * 参与 SQL 构造的入口。除构造后整体过校验器外，表名本身先按标识符白名单
 * （{@code [库名.]表名}）过滤，双重拦截拼接注入。
 *
 * <p><b>并发与只读标志取值</b>：
 * <ul>
 *   <li>{@code readOnly = true}：5 个工具均只读，不含任何写库路径。</li>
 *   <li>{@code concurrencySafe = true}：list_tables / get_schema / sample_data /
 *       validate_sql。元数据读取与带 LIMIT 的抽样查询相互独立，适配器每次从连接池
 *       取独立连接、元数据服务只读自己的缓存，并行调用无共享可变状态。</li>
 *   <li>{@code concurrencySafe = false}：execute_sql。虽然只读，但执行的是模型现编
 *       的任意 SQL，代价不可预知（可能全表扫描大表）。串行化避免同一轮对话里多条
 *       重查询同时压向目标数据源，也让"校验 → 执行"的日志顺序保持确定。</li>
 * </ul>
 *
 * @see MetadataService
 * @see SqlSecurityChecker
 */
@Component
@Slf4j
public class NL2SQLTools {

    /** sample_data 未显式传 limit 时的默认样例行数 */
    static final int DEFAULT_SAMPLE_LIMIT = 3;

    /** 允许拼接进 SQL 的表名形态：普通标识符，可选带库名（schema）前缀 */
    private static final Pattern SAFE_TABLE_NAME =
            Pattern.compile("[A-Za-z0-9_$]+(?:\\.[A-Za-z0-9_$]+)?");

    private final MetadataService metadataService;
    private final DataSourceService dataSourceService;
    private final DataSourceFactory dataSourceFactory;
    private final SqlSecurityChecker sqlSecurityChecker;
    /** 单次查询最大返回行数（同时也是自动追加的 LIMIT 值） */
    private final int maxLimit;

    /**
     * 构造注入。
     *
     * @param metadataService    元数据服务（表清单 / 列信息）
     * @param dataSourceService  数据源服务（按 ID 加载数据源实体）
     * @param dataSourceFactory  适配器工厂（按数据源创建并缓存适配器）
     * @param sqlSecurityChecker SQL 安全校验器（三层校验 + LIMIT 强制）
     * @param maxLimit           最大返回行数，来自 {@code nl2sql.sql-security.max-limit}
     */
    public NL2SQLTools(MetadataService metadataService,
                       DataSourceService dataSourceService,
                       DataSourceFactory dataSourceFactory,
                       SqlSecurityChecker sqlSecurityChecker,
                       @Value("${nl2sql.sql-security.max-limit:100}") int maxLimit) {
        this.metadataService = metadataService;
        this.dataSourceService = dataSourceService;
        this.dataSourceFactory = dataSourceFactory;
        this.sqlSecurityChecker = sqlSecurityChecker;
        this.maxLimit = maxLimit;
    }

    /**
     * 列出指定数据源中的所有表名与表注释。
     *
     * @param datasourceId 数据源 ID
     * @return 表信息列表（表名 + 注释）
     */
    @Tool(name = "list_tables",
            description = "列出指定数据源中的所有表名与表注释。NL2SQL 的第一步：先调用本工具了解目标库有哪些表，"
                    + "再对候选表调用 get_schema 查看字段。参数 datasource_id 为数据源 ID，例如 {\"datasource_id\": 1}。只读操作。",
            readOnly = true, concurrencySafe = true)
    public List<TableInfo> listTables(
            @ToolParam(name = "datasource_id", required = true,
                    description = "数据源 ID（整数），由会话上下文指定，例如 1") Long datasourceId) {
        List<TableInfo> tables = metadataService.getTables(datasourceId);
        log.info("[list_tables] datasource_id={} tables={}", datasourceId, tables.size());
        return tables;
    }

    /**
     * 获取指定表的列定义。
     *
     * @param datasourceId 数据源 ID
     * @param tableName    表名（来自 list_tables 的结果，不区分大小写）
     * @return 列信息列表；表不存在时返回空列表
     */
    @Tool(name = "get_schema",
            description = "获取指定表的列定义（列名、数据类型、是否可空、主键、注释），生成 SQL 前用它确认字段名与类型。"
                    + "例如 {\"datasource_id\": 1, \"table_name\": \"users\"}。表不存在时返回空列表。只读操作。",
            readOnly = true, concurrencySafe = true)
    public List<ColumnInfo> getSchema(
            @ToolParam(name = "datasource_id", required = true, description = "数据源 ID（整数），例如 1") Long datasourceId,
            @ToolParam(name = "table_name", required = true,
                    description = "表名，取自 list_tables 的返回结果，例如 users") String tableName) {
        List<ColumnInfo> columns = metadataService.getColumns(datasourceId, tableName);
        log.info("[get_schema] datasource_id={} table={} columns={}", datasourceId, tableName, columns.size());
        return columns;
    }

    /**
     * 抽样查看指定表的前 N 行数据。
     *
     * <p>构造的 SQL 形如 {@code SELECT * FROM <table_name> LIMIT <n>}。表名先按
     * 标识符白名单过滤，再对整条 SQL 执行安全校验，任一不通过即拒绝执行。
     *
     * @param datasourceId 数据源 ID
     * @param tableName    表名
     * @param limit        样例行数，可为 null（默认 {@value #DEFAULT_SAMPLE_LIMIT}），上限为配置的 max-limit
     * @return 查询结果（列名 + 样例行）
     * @throws BusinessException SQL 未通过安全校验（含表名非法）时抛出
     */
    @Tool(name = "sample_data",
            description = "抽样查看指定表的前 N 行真实数据，用于确认字段取值格式（日期、枚举、NULL、金额单位等），"
                    + "避免生成的 SQL 条件写错。limit 默认 3，超过系统上限时按上限截断。"
                    + "例如 {\"datasource_id\": 1, \"table_name\": \"users\", \"limit\": 3}。只读操作，内部只执行 SELECT ... LIMIT。",
            readOnly = true, concurrencySafe = true)
    public QueryResult sampleData(
            @ToolParam(name = "datasource_id", required = true, description = "数据源 ID（整数），例如 1") Long datasourceId,
            @ToolParam(name = "table_name", required = true,
                    description = "表名，取自 list_tables 的返回结果，例如 users") String tableName,
            @ToolParam(name = "limit", required = false,
                    description = "样例行数，可选，默认 3，最大不超过系统上限") Integer limit) {
        int effectiveLimit = resolveSampleLimit(limit);
        validateTableName(tableName);

        String sql = "SELECT * FROM " + tableName + " LIMIT " + effectiveLimit;
        // 表名拼接是注入面：构造完的 SQL 必须整体过校验器，不因为"是自己拼的"而免检
        ValidationResult validation = sqlSecurityChecker.validate(sql);
        if (!validation.valid()) {
            throw new BusinessException(ErrorCode.SQL_VALIDATION_FAILED,
                    "抽样查询未通过安全校验: " + validation.errorMessage());
        }

        QueryResult result = resolveAdapter(datasourceId).executeQuery(sql, effectiveLimit);
        log.info("[sample_data] datasource_id={} table={} limit={} rows={}",
                datasourceId, tableName, effectiveLimit, result.getRowCount());
        return result;
    }

    /**
     * 校验 SQL 的语法与安全性。
     *
     * <p>当前校验只依赖 SQL 文本（SQL 安全校验器与方言无关），{@code datasource_id}
     * 仅为与工具签名对齐而保留，供后续按方言差异化校验时使用，本次不参与判定。
     *
     * @param datasourceId 数据源 ID（预留，当前不参与校验）
     * @param sql          待校验的 SQL
     * @return 校验结果（是否通过 + 失败原因）
     */
    @Tool(name = "validate_sql",
            description = "校验一条 SQL 是否安全可执行：只允许单条 SELECT，拒绝写操作（INSERT/UPDATE/DELETE/DROP 等）、"
                    + "多语句堆叠、UNION 注入与注释绕过，返回 {valid, errorMessage}。生成 SQL 后先调用本工具，"
                    + "valid=false 时按 errorMessage 修正后重试。例如 {\"datasource_id\": 1, \"sql\": \"SELECT count(*) FROM users\"}。"
                    + "注意：校验只看 SQL 文本、与数据源方言无关，datasource_id 仅为签名对齐保留。",
            readOnly = true, concurrencySafe = true)
    public ValidationResult validateSql(
            @ToolParam(name = "datasource_id", required = true,
                    description = "数据源 ID（整数），当前仅用于签名对齐，校验不依赖方言") Long datasourceId,
            @ToolParam(name = "sql", required = true,
                    description = "待校验的 SQL 文本，例如 SELECT id, name FROM users WHERE age > 30") String sql) {
        log.debug("[validate_sql] datasource_id={} 仅作预留，校验与方言无关", datasourceId);
        return sqlSecurityChecker.validate(sql);
    }

    /**
     * 执行只读 SQL 查询。
     *
     * <p>防御性实现，顺序固定且不依赖模型先调 validate_sql：
     * 内部 {@code validate} → {@code enforceLimit(max-limit)} → 适配器执行。
     * 校验失败或执行失败都抛 {@link BusinessException}，让拒绝/失败原因作为工具错误
     * 回传给 Agent 学习修正。
     *
     * <p><b>执行失败必须显式失败</b>：适配器对 SQLException 采取容错（返回
     * {@code success=false} 而不抛，数据源预览接口依赖该行为），但工具层不能沿用——
     * 一旦把失败当成功返回，AgentScope 会记成工具调用成功，于是 trace / 审计
     * （{@code execution_success}）/ FINAL 的 sql&rows 全部把失败当成功。
     *
     * @param datasourceId 数据源 ID
     * @param sql          待执行的 SELECT SQL
     * @return 查询结果（列名 + 数据行），行数不超过 max-limit
     * @throws BusinessException SQL 未通过安全校验（{@link ErrorCode#SQL_VALIDATION_FAILED}）
     *                           或数据库执行失败（{@link ErrorCode#SQL_EXECUTION_FAILED}）时抛出
     */
    @Tool(name = "execute_sql",
            description = "执行一条只读 SELECT 查询并返回结果集（列名 + 数据行）。工具内部会强制安全校验并自动追加 LIMIT"
                    + "（防止全表扫描），SQL 不合法时直接拒绝并给出原因，无需先调 validate_sql。"
                    + "例如 {\"datasource_id\": 1, \"sql\": \"SELECT name, salary FROM users ORDER BY salary DESC LIMIT 10\"}。"
                    + "只读操作，任何写操作都会被拒绝。SQL 只能使用当前数据源（datasource_id 对应）的表；"
                    + "不支持跨数据源 JOIN——需要结合其他数据源或历史轮次的数据时，在本数据源内查询后用文字对比引用。",
            readOnly = true, concurrencySafe = false)
    public QueryResult executeSql(
            @ToolParam(name = "datasource_id", required = true, description = "数据源 ID（整数），例如 1") Long datasourceId,
            @ToolParam(name = "sql", required = true,
                    description = "待执行的单条 SELECT 语句，例如 SELECT name FROM users LIMIT 10") String sql) {
        // 顺序固定：校验 → 强制 LIMIT → 执行；不信任模型是否先调过 validate_sql
        ValidationResult validation = sqlSecurityChecker.validate(sql);
        if (!validation.valid()) {
            log.warn("[execute_sql] 拒绝执行: datasource_id={} reason={}", datasourceId, validation.errorMessage());
            throw new BusinessException(ErrorCode.SQL_VALIDATION_FAILED,
                    "SQL 未通过安全校验: " + validation.errorMessage());
        }

        String safeSql = sqlSecurityChecker.enforceLimit(sql, maxLimit);
        QueryResult result = resolveAdapter(datasourceId).executeQuery(safeSql, maxLimit);
        if (!result.isSuccess()) {
            // 适配器把 SQLException 收敛成 success=false（数据源预览接口依赖该容错），
            // 这里必须把它升级为异常：否则 AgentScope 记成工具调用成功，下游全部失真。
            // 日志打出数据库原始错误与 SQL 摘要，只看日志即可判断本次执行没成，无需翻堆栈。
            log.warn("[execute_sql] 执行失败: datasource_id={} error={} sql={}",
                    datasourceId, result.getErrorMessage(), safeSql);
            throw new BusinessException(ErrorCode.SQL_EXECUTION_FAILED,
                    "SQL 执行失败：" + result.getErrorMessage()
                            + "。请先用 get_schema 确认表名与列名，再重试 execute_sql。");
        }
        log.info("[execute_sql] 执行成功: datasource_id={} rows={} cost={}ms",
                datasourceId, result.getRowCount(), result.getExecutionTimeMs());
        return result;
    }

    /**
     * 按数据源 ID 解析已连接的适配器。
     *
     * <p>与 {@link MetadataService} 走同一条路径与同一套报错风格：数据源不存在时由
     * {@link DataSourceService#getById} 抛 {@link BusinessException}（1001）。
     *
     * @param datasourceId 数据源 ID
     * @return 该数据源的适配器
     */
    private DataSourceAdapter resolveAdapter(Long datasourceId) {
        DataSource ds = dataSourceService.getById(datasourceId);
        return dataSourceFactory.getAdapter(datasourceId, ds.getType(), ds.toAdapterConfig());
    }

    /**
     * 归一化抽样行数：null 或非正数取默认值，并截断到 max-limit 以内。
     *
     * @param limit 模型传入的行数，可为 null
     * @return 实际生效的行数
     */
    private int resolveSampleLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return Math.min(DEFAULT_SAMPLE_LIMIT, maxLimit);
        }
        return Math.min(limit, maxLimit);
    }

    /**
     * 校验表名形态，只允许 {@code [库名.]表名} 形式的普通标识符。
     *
     * @param tableName 模型传入的表名
     * @throws BusinessException 表名为空或含标识符之外的字符时抛出
     */
    private void validateTableName(String tableName) {
        if (tableName == null || tableName.isBlank() || !SAFE_TABLE_NAME.matcher(tableName.trim()).matches()) {
            throw new BusinessException(ErrorCode.SQL_VALIDATION_FAILED,
                    "非法的表名: " + tableName + "，只允许普通标识符（字母/数字/下划线，可带库名前缀）");
        }
    }
}
