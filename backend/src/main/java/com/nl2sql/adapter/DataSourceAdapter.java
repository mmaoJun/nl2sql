package com.nl2sql.adapter;

import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.TableInfo;
import com.nl2sql.model.dto.QueryResult;

import java.util.List;
import java.util.Map;

/**
 * 数据源适配器顶层接口。
 *
 * <p>屏蔽底层数据源差异，向上层（元数据服务、NL2SQL 引擎）暴露统一的能力：
 * 连接管理、元数据获取、SQL 执行。所有数据源类型都必须实现本接口。
 *
 * <p><b>扩展新数据源的方式</b>：
 * <ul>
 *   <li>JDBC 系（Oracle、达梦、GaussDB、Hive、Spark SQL 等）：继承
 *       {@link com.nl2sql.adapter.jdbc.JdbcDataSourceAdapter}，通常只需实现
 *       {@code buildJdbcUrl()}，连接池、查询执行、基于 JDBC DatabaseMetaData 的
 *       通用元数据读取均由基类提供；如需保留注释/主键等方言细节再覆写
 *       {@code getTables()/getColumns()}。</li>
 *   <li>非 JDBC 系（REST API、GraphQL、HDFS 文件等）：直接实现本接口。</li>
 *   <li>无论哪种方式，配套提供一个 {@link DataSourceAdapterFactory} 的 Spring Bean
 *       （标注 {@code @Component}），工厂启动时自动注册，无需修改任何既有代码。</li>
 * </ul>
 *
 * @see DataSourceAdapterFactory
 * @see DataSourceFactory
 */
public interface DataSourceAdapter {

    /**
     * 建立数据源连接。
     *
     * @param config 连接配置（host、port、databaseName、username、password，
     *               以及 extraConfig 合并进来的方言专属参数）
     * @return true 表示连接成功
     */
    boolean connect(Map<String, Object> config);

    /**
     * 断开数据源连接，释放连接池等资源。
     */
    void disconnect();

    /**
     * 测试数据源连接是否可用（创建临时资源，不影响已缓存的连接）。
     *
     * @param config 连接配置
     * @return true 表示连接可用
     */
    boolean testConnection(Map<String, Object> config);

    /**
     * 获取当前数据库的所有表结构信息（含列信息）。
     *
     * @return 表信息列表
     */
    List<TableInfo> getTables();

    /**
     * 获取指定表的列信息。
     *
     * @param tableName 表名
     * @return 列信息列表
     */
    List<ColumnInfo> getColumns(String tableName);

    /**
     * 执行 SQL 查询并返回结果。
     *
     * @param sql   待执行的 SQL（上层已保证为只读 SELECT）
     * @param limit 最大返回行数
     * @return 查询结果
     */
    QueryResult executeQuery(String sql, int limit);

    /**
     * 获取数据库方言标识，用于 Prompt 生成时选择正确的 SQL 语法。
     *
     * @return 方言名称（如 mysql、postgresql）
     */
    String getDialect();
}
