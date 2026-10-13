package com.nl2sql.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 元数据服务。
 *
 * <p>从数据源获取表结构信息（表名、列名、类型、注释），支持 Redis 缓存。
 * 缓存 Key 格式为 {@code schema:{datasourceId}}，TTL 默认 3600 秒。
 *
 * <p><b>缓存降级</b>：Redis 不可用时读、写、清除三条路径全部优雅降级为
 * 直连查询，仅记录 WARN 日志，不向调用方抛出异常（相对旧实现的增强，
 * 旧版 {@code refreshCache} 清除缓存时的异常会直接向上传播）。
 *
 * @see DataSourceAdapter
 * @see DataSourceFactory
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetadataService {

    private static final String SCHEMA_CACHE_PREFIX = "schema:";

    /**
     * 预览接口未显式传 pageSize 时的默认每页行数。
     *
     * <p>取 50 而非 tool 层 {@code sample_data} 的 3：数据预览是给人翻页看的列表页，
     * 每页 3 行毫无意义；50 行在"一屏能看完"与"请求体不至于过大"之间取平衡，
     * 且与前端分页组件默认的"50 行/页"选项对齐。真正生效前仍会被
     * {@code nl2sql.sql-security.max-limit} 截断，缺省值调大不会突破安全上限。
     */
    private static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * 允许拼接进 SQL 的表名形态：普通标识符，可选带库名（schema）前缀。
     *
     * <p>与 tool 层 {@code sample_data} 使用同一规则，用于预览接口在拼 SQL 前对
     * 路径变量做标识符白名单校验。
     */
    private static final Pattern SAFE_TABLE_NAME =
            Pattern.compile("[A-Za-z0-9_$]+(?:\\.[A-Za-z0-9_$]+)?");

    private final RedisTemplate<String, Object> redisTemplate;
    private final DataSourceFactory dataSourceFactory;
    private final DataSourceService dataSourceService;
    private final SqlSecurityChecker sqlSecurityChecker;

    @Value("${nl2sql.schema-cache.ttl-seconds:3600}")
    private long cacheTtlSeconds;

    /** 单次预览查询最大返回行数，复用 tool 层同一配置项 {@code nl2sql.sql-security.max-limit} */
    @Value("${nl2sql.sql-security.max-limit:100}")
    private int maxLimit;

    /**
     * 获取数据源的完整 Schema（含列信息）。
     *
     * <p>优先从 Redis 缓存读取；缓存未命中时通过适配器直连数据源查询，
     * 查询结果写入缓存。Redis 异常时自动降级为直连模式。
     *
     * @param datasourceId 数据源 ID
     * @return 表结构列表，每张表包含列名、类型、注释等信息
     */
    public List<TableInfo> getSchema(Long datasourceId) {
        String cacheKey = SCHEMA_CACHE_PREFIX + datasourceId;

        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof List) {
                @SuppressWarnings("unchecked")
                List<TableInfo> tables = (List<TableInfo>) cached;
                if (!tables.isEmpty()) {
                    log.debug("从缓存获取 Schema: datasourceId={}", datasourceId);
                    return tables;
                }
            }
        } catch (Exception e) {
            log.warn("Redis 缓存读取失败，降级为直连查询: datasourceId={}, cause={}", datasourceId, e.getMessage());
        }

        DataSource ds = dataSourceService.getById(datasourceId);
        DataSourceAdapter adapter = dataSourceFactory.getAdapter(
                datasourceId, ds.getType(), ds.toAdapterConfig());
        List<TableInfo> tables = adapter.getTables();

        try {
            redisTemplate.opsForValue().set(cacheKey, tables, cacheTtlSeconds, TimeUnit.SECONDS);
            log.debug("Schema 已写入缓存: datasourceId={}, tables={}", datasourceId, tables.size());
        } catch (Exception e) {
            log.warn("Redis 缓存写入失败，本次结果仅直连返回: datasourceId={}, cause={}", datasourceId, e.getMessage());
        }

        return tables;
    }

    /**
     * 强制刷新 Schema 缓存。
     *
     * <p>清除 Redis 缓存和适配器缓存后重新加载 Schema，用于数据源结构变更后的手动刷新。
     * Redis 不可用时仅记录 WARN 日志并继续刷新，保证刷新动作始终可用。
     *
     * @param datasourceId 数据源 ID
     */
    public void refreshCache(Long datasourceId) {
        String cacheKey = SCHEMA_CACHE_PREFIX + datasourceId;
        try {
            redisTemplate.delete(cacheKey);
            log.debug("Schema 缓存已清除: datasourceId={}", datasourceId);
        } catch (Exception e) {
            log.warn("Redis 缓存清除失败，降级为直连刷新: datasourceId={}, cause={}", datasourceId, e.getMessage());
        }
        dataSourceFactory.removeAdapter(datasourceId);
        getSchema(datasourceId);
    }

    /**
     * 获取数据源的表列表（不含列信息）。
     *
     * <p>仅返回表名、注释与列数量，用于前端表列表等轻量场景（列表页可直接展示"列数"，
     * 无需逐表再发一次 columns 请求）。列数量由完整 Schema 的 columns 大小在内存里映射
     * 得到，{@code columns} 本身仍保持为 null，本接口不会变重。
     *
     * @param datasourceId 数据源 ID
     * @return 表信息列表（表名 + 注释 + 列数量）
     */
    public List<TableInfo> getTables(Long datasourceId) {
        List<TableInfo> tables = getSchema(datasourceId);
        return tables.stream().map(t -> {
            TableInfo info = new TableInfo();
            info.setTableName(t.getTableName());
            info.setComment(t.getComment());
            List<ColumnInfo> columns = t.getColumns();
            info.setColumnCount(columns == null ? 0 : columns.size());
            return info;
        }).collect(Collectors.toList());
    }

    /**
     * 获取指定表的列信息。
     *
     * @param datasourceId 数据源 ID
     * @param tableName    表名（不区分大小写）
     * @return 列信息列表；表不存在时返回空列表
     */
    public List<ColumnInfo> getColumns(Long datasourceId, String tableName) {
        List<TableInfo> tables = getSchema(datasourceId);
        return tables.stream()
                .filter(t -> t.getTableName().equalsIgnoreCase(tableName))
                .findFirst()
                .map(TableInfo::getColumns)
                .orElse(Collections.emptyList());
    }

    /**
     * 分页预览指定表的数据（供前端数据源详情页使用，不经 Agent 工具层）。
     *
     * <p>分页需要两条 SQL：取数用 {@code SELECT * FROM <tableName> LIMIT <pageSize> OFFSET <offset>}
     * （{@code offset = (page - 1) * pageSize}），计数用 {@code SELECT COUNT(*) FROM <tableName>}；
     * 总行数写回结果对象的 {@code total}，前端据此渲染页码（否则只能"下一页/上一页"盲翻）。
     *
     * <p><b>安全双重拦截（不得削弱）</b>：表名是被拼进 SQL 的外部入参，是本接口唯一的
     * 拼接注入面，因此：
     * <ol>
     *   <li>表级校验先做一次——表名按标识符白名单（{@code [库名.]表名}）过滤，并确认它
     *       确实存在于该数据源的 Schema 中（忽略大小写），杜绝任意标识符进入 SQL；</li>
     *   <li>拼接完成的<b>每一条</b> SQL（取数与计数各一条）都整体交给
     *       {@link SqlSecurityChecker#validate}，不因为"是自己拼的"而免检：新增的
     *       COUNT 语句同样是拼接产物，同样必须过校验器，不能只校验取数语句。</li>
     * </ol>
     * 任一不通过即抛 {@link BusinessException}，绝不把可疑 SQL 交给适配器执行。
     * 计数失败只降级不报错：拿不到总行数时以本页实际行数兜底，保证列表页始终可打开。
     *
     * @param datasourceId 数据源 ID
     * @param tableName    表名（不区分大小写，需为该数据源中真实存在的表）
     * @param page         页码，从 1 开始，可为 null（默认 1），小于 1 时归一为 1
     * @param pageSize     每页行数，可为 null（默认 {@value #DEFAULT_PAGE_SIZE}），非正数时取默认值，
     *                     上限为配置的 max-limit
     * @return 查询结果（列名 + 当前页数据行 + 整表总行数 total）
     * @throws BusinessException 表名非法、表不存在或构造出的 SQL 未通过安全校验时抛出
     */
    public QueryResult previewTable(Long datasourceId, String tableName, Integer page, Integer pageSize) {
        // 第一重：标识符形态白名单 + 必须是该数据源中真实存在的表（表级，两条 SQL 共用一次校验）
        validateTableName(tableName);
        ensureTableExists(datasourceId, tableName);

        int effectivePage = resolvePage(page);
        int effectivePageSize = resolvePageSize(pageSize);
        long offset = (long) (effectivePage - 1) * effectivePageSize;

        String rowsSql = "SELECT * FROM " + tableName
                + " LIMIT " + effectivePageSize + " OFFSET " + offset;
        String countSql = "SELECT COUNT(*) FROM " + tableName;
        // 第二重：两条 SQL 都是表名拼接产物，必须各自整体过校验器，不因为"是自己拼的"而免检
        validateConstructedSql(rowsSql);
        validateConstructedSql(countSql);

        DataSourceAdapter adapter = resolveAdapter(datasourceId);
        QueryResult result = adapter.executeQuery(rowsSql, effectivePageSize);
        result.setTotal(resolveTotal(adapter, countSql, result));
        log.info("[previewTable] datasourceId={} table={} page={} pageSize={} offset={} rows={} total={}",
                datasourceId, tableName, effectivePage, effectivePageSize, offset,
                result.getRowCount(), result.getTotal());
        return result;
    }

    /**
     * 按数据源 ID 解析已连接的适配器。
     *
     * <p>与 tool 层走同一条路径与同一套报错风格：数据源不存在时由
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
     * 归一化页码：null 或小于 1 一律取第 1 页。
     *
     * <p>只做下界收敛，不设上界：页码越界时 COUNT 会告诉前端真实总页数，这里强行
     * 猜测上界反而会把用户翻到空白页；越界查询本身也只是返回空行，无安全影响。
     *
     * @param page 调用方传入的页码，可为 null
     * @return 实际生效的页码，恒 ≥ 1
     */
    private int resolvePage(Integer page) {
        if (page == null || page < 1) {
            return 1;
        }
        return page;
    }

    /**
     * 归一化每页行数：null 或非正数取默认值 {@value #DEFAULT_PAGE_SIZE}，并截断到 max-limit 以内。
     *
     * <p>截断到 max-limit 是硬约束：pageSize 由前端传入，若不设上限，调用方可以传
     * 一个极大的值把分页退化成全表拉取，绕过限流。
     *
     * @param pageSize 调用方传入的每页行数，可为 null
     * @return 实际生效的每页行数
     */
    private int resolvePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return Math.min(DEFAULT_PAGE_SIZE, maxLimit);
        }
        return Math.min(pageSize, maxLimit);
    }

    /**
     * 校验拼接完成的 SQL，不通过即抛异常，绝不返回给调用方继续执行。
     *
     * @param sql 由表名拼接出的完整 SQL
     * @throws BusinessException 未通过安全校验时抛出
     */
    private void validateConstructedSql(String sql) {
        ValidationResult validation = sqlSecurityChecker.validate(sql);
        if (!validation.valid()) {
            throw new BusinessException(ErrorCode.SQL_VALIDATION_FAILED,
                    "数据预览未通过安全校验: " + validation.errorMessage());
        }
    }

    /**
     * 查询整表总行数，失败时以本页实际行数兜底。
     *
     * <p>计数列名由后端与数据库方言共同决定（可能是 {@code COUNT(*)}、{@code count(*)}、
     * {@code total} 等），因此不硬编码 key，而是取首行首个值并按 {@link Number} 转换。
     * 计数失败或返回空集属于"分页信息缺失"而非查询失败，不应让整个预览接口报错，
     * 故降级为返回本页行数，前端至少还能翻页而不是白屏。
     *
     * @param adapter  已解析出的适配器（与取数复用同一连接）
     * @param countSql 已通过安全校验的计数 SQL
     * @param rows     取数结果，作为兜底行数来源
     * @return 整表总行数；无法获取时返回本页实际行数
     */
    private long resolveTotal(DataSourceAdapter adapter, String countSql, QueryResult rows) {
        try {
            QueryResult countResult = adapter.executeQuery(countSql, 1);
            Long total = firstNumericValue(countResult);
            if (total != null) {
                return total;
            }
            log.warn("计数查询未返回可用数值，total 降级为本页行数: countSql={}", countSql);
        } catch (Exception e) {
            log.warn("计数查询失败，total 降级为本页行数: countSql={}, cause={}", countSql, e.getMessage());
        }
        return fallbackTotal(rows);
    }

    /**
     * 从计数结果中取首个数值，取不到返回 null。
     *
     * @param countResult 计数查询结果，可为 null
     * @return 计数结果首行首个值转换出的 long，取不到时为 null
     */
    private Long firstNumericValue(QueryResult countResult) {
        if (countResult == null || countResult.getRows() == null || countResult.getRows().isEmpty()) {
            return null;
        }
        Map<String, Object> firstRow = countResult.getRows().get(0);
        if (firstRow == null || firstRow.isEmpty()) {
            return null;
        }
        Object value = firstRow.values().iterator().next();
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 总行数兜底值：本页实际返回的行数。
     *
     * @param rows 取数结果，可为 null
     * @return 本页行数，结果为空时返回 0
     */
    private long fallbackTotal(QueryResult rows) {
        if (rows == null || rows.getRows() == null) {
            return 0L;
        }
        return rows.getRows().size();
    }

    /**
     * 校验表名形态，只允许 {@code [库名.]表名} 形式的普通标识符。
     *
     * @param tableName 调用方传入的表名
     * @throws BusinessException 表名为空或含标识符之外的字符时抛出
     */
    private void validateTableName(String tableName) {
        if (tableName == null || tableName.isBlank()
                || !SAFE_TABLE_NAME.matcher(tableName.trim()).matches()) {
            throw new BusinessException(ErrorCode.SQL_VALIDATION_FAILED,
                    "非法的表名: " + tableName + "，只允许普通标识符（字母/数字/下划线，可带库名前缀）");
        }
    }

    /**
     * 确认表名确实存在于该数据源的 Schema 中（忽略大小写）。
     *
     * @param datasourceId 数据源 ID
     * @param tableName    表名
     * @throws BusinessException 该数据源中不存在此表时抛出
     */
    private void ensureTableExists(Long datasourceId, String tableName) {
        boolean exists = getSchema(datasourceId).stream()
                .anyMatch(t -> t.getTableName().equalsIgnoreCase(tableName.trim()));
        if (!exists) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "表不存在: " + tableName);
        }
    }
}
