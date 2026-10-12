package com.nl2sql.adapter.jdbc;

import java.util.Map;

/**
 * Hive 数据源适配器（HiveServer2 JDBC）。
 *
 * <p>连接池、查询执行与基于 JDBC DatabaseMetaData 的通用元数据读取全部复用
 * {@link JdbcDataSourceAdapter}；方言适配仅两处：
 * <ul>
 *   <li><b>schema 语义</b>——Hive 无 catalog，database 即 schema。实测
 *       {@code getTables/getColumns(null, null, ...)} 会返回<b>所有库</b>的表
 *       （跨库泄漏，用第二个库验证过），因此 {@link #schemaPattern()} 必须返回
 *       连接配置里的 database 名，将元数据收敛到目标库；database 为空时回退
 *       {@code default}（无 database 的 URL 会话默认库，实测 getSchema()=default）。</li>
 *   <li><b>无主键/注释</b>——Hive 表无主键（{@code getPrimaryKeys} 返回空结果集）、
 *       列注释不随 metastore 返回，基类对空主键与 null REMARKS 均已兼容，
 *       无需覆写 {@code getTables()/getColumns()}。</li>
 * </ul>
 *
 * <p><b>驱动与 HikariCP 兼容性</b>（standalone 驱动实测）：
 * {@code HiveConnection.isValid()} 为真实往返探测，直接用作池连接校验，
 * 未设置 {@code connectionTestQuery}；{@code Statement.setQueryTimeout} 亦被支持，
 * 基类的查询超时机制无需调整。
 *
 * @see JdbcDataSourceAdapter
 */
public class HiveAdapter extends JdbcDataSourceAdapter {

    /** Hive 会话默认库（JDBC URL 未带 database 时生效）。 */
    private static final String DEFAULT_DATABASE = "default";

    /** {@inheritDoc} */
    @Override
    protected String buildJdbcUrl(Map<String, Object> config) {
        String base = String.format("jdbc:hive2://%s:%d", config.get("host"), port(config));
        String database = database(config);
        return (database == null || database.isBlank()) ? base : base + "/" + database;
    }

    /** {@inheritDoc} */
    @Override
    protected String schemaPattern() {
        String database = database(getConfig());
        return (database == null || database.isBlank()) ? DEFAULT_DATABASE : database;
    }

    /** {@inheritDoc} */
    @Override
    public String getDialect() {
        return "hive";
    }
}
