package com.nl2sql.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.adapter.jdbc.MySQLAdapter;
import com.nl2sql.mapper.DataSourceMapper;
import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.TableInfo;
import com.nl2sql.model.dto.QueryResult;
import com.nl2sql.service.DataSourceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据源适配器与连接测试的直连本机 MySQL 集成验证。
 *
 * <p>只验单元测试验不出来的东西：{@code datasources} 表记录的字段映射真的对得上、
 * {@link DataSource#toAdapterConfig()} 产出的配置真能连通目标库、
 * MySQL 适配器对真实库的元数据读取与只读查询真的返回数据。
 * 数据源记录按类型（mysql）取首条，不硬编码 id；需要本机 MySQL(nlp2sql_meta)
 * 在跑，未启动时跳过而不是失败。
 *
 * <p>命名用 {@code *Test} 而不是 {@code *IT}：surefire 默认只收 {@code *Test}，
 * 叫 IT 的话 {@code mvn test} 会静默跳过它，看起来全绿其实一行都没跑。
 */
@SpringBootTest
class DataSourceAdapterIntegrationTest {

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Autowired
    private DataSourceFactory dataSourceFactory;

    @Autowired
    private DataSourceService dataSourceService;

    /** 本用例内经工厂缓存的适配器对应的数据源 id，结束时清理,避免连接池悬挂 */
    private Long cachedAdapterId;

    @AfterEach
    void cleanup() {
        if (cachedAdapterId != null) {
            dataSourceFactory.removeAdapter(cachedAdapterId);
        }
    }

    @Test
    void factoryRegistersMysqlTypeMetadata() {
        assertTrue(dataSourceFactory.isSupported("mysql"), "mysql 类型应已注册");
        assertEquals("mysql", dataSourceFactory.getDialect("mysql"));
        assertInstanceOf(MySQLAdapter.class, dataSourceFactory.createAdapter("mysql"));

        DataSourceTypeInfo mysql = dataSourceFactory.supportedTypes().stream()
                .filter(t -> "mysql".equals(t.code()))
                .findFirst()
                .orElseThrow();
        assertEquals("MySQL", mysql.displayName());
        assertEquals(3306, mysql.defaultPort());
    }

    @Test
    void mysqlAdapterReadsMetadataAndExecutesReadOnlyQuery() {
        Assumptions.assumeTrue(mysqlReachable(), "本机 MySQL 未启动，跳过适配器集成验证");
        DataSource ds = firstMysqlDatasource();

        DataSourceAdapter adapter = dataSourceFactory.getAdapter(ds.getId(), ds.getType(), ds.toAdapterConfig());
        cachedAdapterId = ds.getId();
        assertEquals("mysql", adapter.getDialect());

        List<TableInfo> tables = adapter.getTables();
        assertFalse(tables.isEmpty(), "目标库 " + ds.getDatabaseName() + " 应有表");

        String tableName = tables.get(0).getTableName();
        assertNotNull(tableName);
        List<ColumnInfo> columns = adapter.getColumns(tableName);
        assertFalse(columns.isEmpty(), "表 " + tableName + " 应有列");

        QueryResult result = adapter.executeQuery("SELECT * FROM " + tableName + " LIMIT 5", 5);
        assertTrue(result.isSuccess(), "只读查询失败: " + result.getErrorMessage());
        assertFalse(result.getColumns().isEmpty(), "结果应包含列名");
        assertFalse(result.getRows().isEmpty(), "表 " + tableName + " 应有样例行");
        assertTrue(result.getRowCount() <= 5);
    }

    @Test
    void testConnectionCoversFactoryAndService() {
        Assumptions.assumeTrue(mysqlReachable(), "本机 MySQL 未启动，跳过连接测试验证");
        DataSource ds = firstMysqlDatasource();

        assertTrue(dataSourceFactory.testConnection(ds.getType(), ds.toAdapterConfig()),
                "工厂临时适配器连接测试应通过");
        assertTrue(dataSourceService.testConnection(ds.getId()),
                "服务层连接测试应通过");
    }

    /** 按类型取首条 mysql 记录，避免硬编码数据源 id。 */
    private DataSource firstMysqlDatasource() {
        DataSource ds = dataSourceMapper.selectOne(new LambdaQueryWrapper<DataSource>()
                .eq(DataSource::getType, "mysql")
                .orderByAsc(DataSource::getId)
                .last("LIMIT 1"));
        Assumptions.assumeTrue(ds != null, "nlp2sql_meta.datasources 缺少 mysql 类型记录");
        return ds;
    }

    private boolean mysqlReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 3306), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
