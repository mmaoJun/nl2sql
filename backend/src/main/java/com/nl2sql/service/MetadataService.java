package com.nl2sql.service;

import com.nl2sql.adapter.DataSourceAdapter;
import com.nl2sql.adapter.DataSourceFactory;
import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.TableInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

    private final RedisTemplate<String, Object> redisTemplate;
    private final DataSourceFactory dataSourceFactory;
    private final DataSourceService dataSourceService;

    @Value("${nl2sql.schema-cache.ttl-seconds:3600}")
    private long cacheTtlSeconds;

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
     * <p>仅返回表名和注释，用于前端表选择下拉框等轻量场景。
     *
     * @param datasourceId 数据源 ID
     * @return 表信息列表（仅表名 + 注释）
     */
    public List<TableInfo> getTables(Long datasourceId) {
        List<TableInfo> tables = getSchema(datasourceId);
        return tables.stream().map(t -> {
            TableInfo info = new TableInfo();
            info.setTableName(t.getTableName());
            info.setComment(t.getComment());
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
}
