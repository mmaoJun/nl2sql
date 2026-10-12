package com.nl2sql.adapter.api;

import com.nl2sql.adapter.DataSourceAdapter;
import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.TableInfo;
import com.nl2sql.model.dto.QueryResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST API 数据源适配器（直接实现 {@link DataSourceAdapter}，非 JDBC 系）。
 *
 * <p>把 REST 接口返回的 JSON 当作「表」：表清单来自 {@code extra_config.tables}
 * 声明（每张表一个 {@code path}，相对 {@code database_name} 提供的 basePath），
 * 行集经 H2 内存表执行 SQL（见 {@link JsonTableExecutor}）。
 * 方法语义与 JDBC / HDFS 适配器对齐：connect/testConnection 返回可用性布尔、
 * getTables/getColumns 失败返回空列表并记日志、executeQuery 失败返回
 * {@code success=false} 的 {@link QueryResult} 而非抛异常。
 *
 * <p>鉴权：{@code extra_config.headers} 中的请求头（如 {@code X-API-KEY}）会
 * 随每次数据请求发送；401 会被翻译成含 "401" 与修复提示的错误消息。
 *
 * <p>配置语义详见 {@link ApiConfig}；响应 → 行集约定详见 {@link ApiDataClient}。
 *
 * @see DataSourceAdapter
 */
@Slf4j
public class RestApiAdapter implements DataSourceAdapter {

    /** 连接配置解析结果（connect 之后有效）。 */
    private ApiConfig settings;

    /** {@inheritDoc} */
    @Override
    public boolean connect(Map<String, Object> config) {
        try {
            this.settings = ApiConfig.parse(config);
            this.settings.requireRestTables();
            return this.settings.health();
        } catch (Exception e) {
            log.error("REST API 连接失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void disconnect() {
        // 无持久连接：每次查询实时拉取 JSON、自建一次性 H2 内存库
        this.settings = null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean testConnection(Map<String, Object> config) {
        try {
            ApiConfig probe = ApiConfig.parse(config);
            probe.requireRestTables();
            return probe.health();
        } catch (Exception e) {
            log.error("REST API 连接测试失败: {}", e.getMessage());
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<TableInfo> getTables() {
        List<TableInfo> tables = new ArrayList<>();
        try {
            for (ApiConfig.ApiTable table : settings.requireRestTables()) {
                TableInfo info = new TableInfo();
                info.setTableName(table.name());
                info.setComment(table.describe());
                info.setColumns(getColumns(table.name()));
                tables.add(info);
            }
        } catch (Exception e) {
            log.error("获取表列表失败(rest): {}", e.getMessage(), e);
        }
        return tables;
    }

    /** {@inheritDoc} */
    @Override
    public List<ColumnInfo> getColumns(String tableName) {
        ApiConfig.ApiTable table = settings == null ? null : settings.findTable(tableName);
        if (table == null) {
            log.warn("表 {} 未在 extra_config.tables 中声明", tableName);
            return List.of();
        }
        try {
            // 拉取一次真实响应，按样例行推断列（与执行期同一套推断口径）
            return JsonTableExecutor.inferColumns(ApiDataClient.fetchRest(settings, table));
        } catch (Exception e) {
            log.error("获取表 {} 字段失败(rest): {}", tableName, e.getMessage(), e);
            return List.of();
        }
    }

    /** {@inheritDoc} */
    @Override
    public QueryResult executeQuery(String sql, int limit) {
        if (settings == null) {
            QueryResult result = new QueryResult();
            result.setSuccess(false);
            result.setErrorMessage("适配器未连接，请先调用 connect");
            return result;
        }
        return JsonTableExecutor.executeQuery(sql, limit, settings,
                table -> ApiDataClient.fetchRest(settings, table));
    }

    /** {@inheritDoc} */
    @Override
    public String getDialect() {
        // SQL 实际由 H2 内存表执行（LIMIT 等语法按 H2），而非 REST 服务本身
        return "h2";
    }
}
