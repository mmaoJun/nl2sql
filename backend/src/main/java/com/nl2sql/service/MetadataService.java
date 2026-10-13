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
     * 预览接口未显式传 limit 时的默认预览行数。
     *
     * <p>与 tool 层 {@code sample_data} 的默认值保持一致（均为 3），避免同一语义在两处
     * 给出不同缺省；tool 层常量属 tool 包，本层不反向依赖，故此处独立定义。
     */
    private static final int DEFAULT_PREVIEW_LIMIT = 3;

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
     * 预览指定表的前 N 行数据（供前端数据源详情页使用，不经 Agent 工具层）。
     *
     * <p>构造的 SQL 形如 {@code SELECT * FROM <tableName> LIMIT <n>}。表名是被拼进 SQL 的
     * 外部入参，是本接口唯一的拼接注入面，因此做双重拦截：
     * <ol>
     *   <li>表名先按标识符白名单（{@code [库名.]表名}）过滤，并确认它确实存在于该数据源的
     *       Schema 中（忽略大小写），杜绝任意标识符进入 SQL；</li>
     *   <li>拼接完成的整条 SQL 仍整体交给 {@link SqlSecurityChecker#validate}，
     *       不因为"是自己拼的"而免检。</li>
     * </ol>
     * 任一不通过即抛 {@link BusinessException}，绝不把可疑 SQL 交给适配器执行。
     *
     * @param datasourceId 数据源 ID
     * @param tableName    表名（不区分大小写，需为该数据源中真实存在的表）
     * @param limit        预览行数，可为 null（默认 {@value #DEFAULT_PREVIEW_LIMIT}），上限为配置的 max-limit
     * @return 查询结果（列名 + 预览行）
     * @throws BusinessException 表名非法、表不存在或构造出的 SQL 未通过安全校验时抛出
     */
    public QueryResult previewTable(Long datasourceId, String tableName, Integer limit) {
        // 第一重：标识符形态白名单 + 必须是该数据源中真实存在的表
        validateTableName(tableName);
        ensureTableExists(datasourceId, tableName);

        int effectiveLimit = resolvePreviewLimit(limit);
        String sql = "SELECT * FROM " + tableName + " LIMIT " + effectiveLimit;
        // 第二重：表名拼接是注入面，构造完的 SQL 必须整体过校验器，不因为"是自己拼的"而免检
        ValidationResult validation = sqlSecurityChecker.validate(sql);
        if (!validation.valid()) {
            throw new BusinessException(ErrorCode.SQL_VALIDATION_FAILED,
                    "数据预览未通过安全校验: " + validation.errorMessage());
        }

        QueryResult result = resolveAdapter(datasourceId).executeQuery(sql, effectiveLimit);
        log.info("[previewTable] datasourceId={} table={} limit={} rows={}",
                datasourceId, tableName, effectiveLimit, result.getRowCount());
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
     * 归一化预览行数：null 或非正数取默认值 {@value #DEFAULT_PREVIEW_LIMIT}，并截断到 max-limit 以内。
     *
     * @param limit 调用方传入的行数，可为 null
     * @return 实际生效的行数
     */
    private int resolvePreviewLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return Math.min(DEFAULT_PREVIEW_LIMIT, maxLimit);
        }
        return Math.min(limit, maxLimit);
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
