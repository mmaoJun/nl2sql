package com.nl2sql.adapter.jdbc;

import com.nl2sql.adapter.DataSourceAdapter;
import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.TableInfo;
import com.nl2sql.model.dto.QueryResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JDBC 系数据源适配器通用基类。
 *
 * <p>统一实现连接池生命周期（HikariCP）、连接测试、SQL 执行，以及基于
 * JDBC 标准 {@link DatabaseMetaData} 的通用元数据读取。子类通常只需提供
 * {@link #buildJdbcUrl(Map)}；仅当标准元数据接口无法返回注释/主键等
 * 方言特有信息时（如 MySQL 的 COLUMN_TYPE），再覆写
 * {@link #getTables()} / {@link #getColumns(String)}。
 *
 * <p>适用扩展：Oracle、达梦、GaussDB、Hive、Spark SQL 等一切 JDBC 驱动数据源。
 *
 * @see DataSourceAdapter
 */
@Slf4j
public abstract class JdbcDataSourceAdapter implements DataSourceAdapter {

    /** 连接池最大连接数 */
    private static final int MAX_POOL_SIZE = 5;
    /** 获取连接超时（毫秒） */
    private static final int CONNECTION_TIMEOUT_MS = 10000;
    /** 空闲连接超时（毫秒） */
    private static final int IDLE_TIMEOUT_MS = 300000;
    /** 连接有效性检测超时（秒） */
    private static final int VALID_TIMEOUT_S = 5;
    /** 默认查询超时（秒） */
    private static final int QUERY_TIMEOUT_S = 10;
    /** 默认 schema（PG 等按 schema 组织表的数据库使用） */
    private static final String DEFAULT_SCHEMA = "public";

    private HikariDataSource dataSource;
    private Map<String, Object> config;

    /**
     * 构建 JDBC URL，子类唯一的必需实现点。
     *
     * @param config 连接配置
     * @return 完整的 JDBC URL
     */
    protected abstract String buildJdbcUrl(Map<String, Object> config);

    /**
     * 元数据读取使用的 schema 模式；返回 null 表示按驱动默认行为（MySQL 场景）。
     *
     * @return schema 名称或 null
     */
    protected String schemaPattern() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean connect(Map<String, Object> config) {
        try {
            this.config = config;
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(buildJdbcUrl(config));
            hikariConfig.setUsername(str(config, "username"));
            hikariConfig.setPassword(str(config, "password"));
            hikariConfig.setMaximumPoolSize(MAX_POOL_SIZE);
            hikariConfig.setMinimumIdle(1);
            hikariConfig.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
            hikariConfig.setIdleTimeout(IDLE_TIMEOUT_MS);

            this.dataSource = new HikariDataSource(hikariConfig);

            try (Connection conn = dataSource.getConnection()) {
                return conn.isValid(VALID_TIMEOUT_S);
            }
        } catch (Exception e) {
            log.error("{} 连接失败: {}", getDialect(), e.getMessage(), e);
            return false;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean testConnection(Map<String, Object> config) {
        HikariDataSource testDs = null;
        try {
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(buildJdbcUrl(config));
            hikariConfig.setUsername(str(config, "username"));
            hikariConfig.setPassword(str(config, "password"));
            hikariConfig.setMaximumPoolSize(1);
            hikariConfig.setConnectionTimeout(5000);

            testDs = new HikariDataSource(hikariConfig);
            try (Connection conn = testDs.getConnection()) {
                return conn.isValid(VALID_TIMEOUT_S);
            }
        } catch (Exception e) {
            log.error("{} 连接测试失败: {}", getDialect(), e.getMessage());
            return false;
        } finally {
            if (testDs != null && !testDs.isClosed()) {
                testDs.close();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<TableInfo> getTables() {
        List<TableInfo> tables = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, schemaPattern(), "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    TableInfo table = new TableInfo();
                    table.setTableName(rs.getString("TABLE_NAME"));
                    table.setComment(nullToEmpty(rs.getString("REMARKS")));
                    tables.add(table);
                }
            }
            for (TableInfo table : tables) {
                table.setColumns(getColumns(table.getTableName()));
            }
        } catch (SQLException e) {
            log.error("获取表列表失败: {}", e.getMessage(), e);
        }
        return tables;
    }

    /** {@inheritDoc} */
    @Override
    public List<ColumnInfo> getColumns(String tableName) {
        List<ColumnInfo> columns = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            Set<String> primaryKeys = readPrimaryKeys(meta, tableName);

            try (ResultSet rs = meta.getColumns(null, schemaPattern(), tableName, "%")) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    ColumnInfo col = new ColumnInfo();
                    col.setColumnName(columnName);
                    col.setDataType(rs.getString("TYPE_NAME"));
                    col.setColumnType(formatColumnType(rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE")));
                    col.setComment(nullToEmpty(rs.getString("REMARKS")));
                    col.setColumnKey(primaryKeys.contains(columnName) ? "PRI" : "");
                    col.setIsNullable(rs.getString("IS_NULLABLE"));
                    col.setDefaultValue(rs.getString("COLUMN_DEF"));
                    columns.add(col);
                }
            }
        } catch (SQLException e) {
            log.error("获取表 {} 字段失败: {}", tableName, e.getMessage(), e);
        }
        return columns;
    }

    /** {@inheritDoc} */
    @Override
    public QueryResult executeQuery(String sql, int limit) {
        QueryResult result = new QueryResult();
        long startTime = System.currentTimeMillis();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.setQueryTimeout(QUERY_TIMEOUT_S);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                var metaData = rs.getMetaData();
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
        } catch (SQLException e) {
            log.error("SQL 执行失败: {}", e.getMessage(), e);
            result.setSuccess(false);
            result.setErrorMessage("查询执行失败: " + e.getMessage());
        }

        result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
        return result;
    }

    /**
     * 从连接池获取一个连接。
     *
     * @return JDBC 连接
     * @throws SQLException 连接池未初始化或获取失败
     */
    protected Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * 读取连接配置中的端口号。
     *
     * @param config 连接配置
     * @return 端口号
     */
    protected static int port(Map<String, Object> config) {
        Object value = config == null ? null : config.get("port");
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? -1 : Integer.parseInt(String.valueOf(value));
    }

    /**
     * 读取当前连接配置（子类构造 JDBC URL / schema 时使用）。
     *
     * @return 连接配置，connect 之前可能为 null
     */
    protected Map<String, Object> getConfig() {
        return config;
    }

    /**
     * 获取数据库名，缺省回退为空串。
     *
     * @param config 连接配置
     * @return 数据库名
     */
    protected static String database(Map<String, Object> config) {
        return str(config, "databaseName");
    }

    /**
     * 解析 schema：优先 extraConfig 合并的 schema 键，否则默认 public。
     *
     * @param config 连接配置
     * @return schema 名称
     */
    protected static String resolveSchema(Map<String, Object> config) {
        String schema = str(config, "schema");
        return (schema == null || schema.isBlank()) ? DEFAULT_SCHEMA : schema;
    }

    private Set<String> readPrimaryKeys(DatabaseMetaData meta, String tableName) throws SQLException {
        Set<String> keys = new HashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(null, schemaPattern(), tableName)) {
            while (rs.next()) {
                keys.add(rs.getString("COLUMN_NAME"));
            }
        }
        return keys;
    }

    private static String formatColumnType(String typeName, int columnSize) {
        if (columnSize > 0 && columnSize < 2147483647
                && !typeName.equalsIgnoreCase("int") && !typeName.equalsIgnoreCase("integer")
                && !typeName.equalsIgnoreCase("bigint") && !typeName.equalsIgnoreCase("smallint")) {
            return typeName + "(" + columnSize + ")";
        }
        return typeName;
    }

    private static String str(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
