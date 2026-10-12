package com.nl2sql.adapter.jdbc;

import java.util.Map;

/**
 * PostgreSQL 数据源适配器。
 *
 * <p>完整复用 {@link JdbcDataSourceAdapter} 基于 JDBC 标准 DatabaseMetaData 的
 * 通用元数据读取（pgjdbc 驱动会填充表/列注释与主键信息），
 * 子类仅需提供 JDBC URL 与 schema 定位，体现了适配器架构对新增
 * 关系型数据库的零成本扩展能力。
 *
 * <p>支持的 extraConfig 扩展参数：
 * <ul>
 *   <li>{@code schema} —— 指定元数据读取的 schema，默认 {@code public}</li>
 * </ul>
 *
 * @see JdbcDataSourceAdapter
 */
public class PostgreSQLAdapter extends JdbcDataSourceAdapter {

    /** {@inheritDoc} */
    @Override
    protected String buildJdbcUrl(Map<String, Object> config) {
        return String.format(
                "jdbc:postgresql://%s:%d/%s?currentSchema=%s&ApplicationName=nlp2sql",
                config.get("host"), port(config), database(config), resolveSchema(config));
    }

    /** {@inheritDoc} */
    @Override
    protected String schemaPattern() {
        return resolveSchema(getConfig());
    }

    /** {@inheritDoc} */
    @Override
    public String getDialect() {
        return "postgresql";
    }
}
