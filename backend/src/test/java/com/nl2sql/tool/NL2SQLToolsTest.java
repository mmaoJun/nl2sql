package com.nl2sql.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.adapter.DataSourceFactory;
import com.nl2sql.mapper.DataSourceMapper;
import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.TableInfo;
import com.nl2sql.model.dto.QueryResult;
import com.nl2sql.security.SqlSecurityChecker;
import com.nl2sql.security.SqlSecurityChecker.ValidationResult;
import com.nl2sql.service.DataSourceService;
import com.nl2sql.service.MetadataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5 个工具对 {@code nlp2sql_test} 的真实调用验证（W2 检查点：工具可独立调用）。
 *
 * <p>{@link NL2SQLTools} 在测试里用 collaborators 直接 new 出来，逐个调用工具方法并断言，
 * 不经过 LLM、不经过 Agent、不经过 Toolkit —— 证明每个工具离开模型也能独立工作。
 * 数据源按 {@code database_name='nlp2sql_test'} 查询定位，不硬编码 id。
 *
 * <p>命名用 {@code *Test} 而不是 {@code *IT}：surefire 默认只收 {@code *Test}。
 */
@SpringBootTest
class NL2SQLToolsTest {

    private static final String TARGET_DATABASE = "nlp2sql_test";
    private static final String KNOWN_TABLE = "users";

    @Autowired
    private MetadataService metadataService;

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private DataSourceFactory dataSourceFactory;

    @Autowired
    private SqlSecurityChecker sqlSecurityChecker;

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Value("${nl2sql.sql-security.max-limit:100}")
    private int maxLimit;

    /** 直接实例化的被测工具集，不依赖 Spring 代理或 Agent 装配 */
    private NL2SQLTools tools;

    /** 本次用例经工厂缓存的适配器对应的数据源 id，结束时清理，避免连接池悬挂 */
    private Long cachedAdapterId;

    @BeforeEach
    void setUp() {
        tools = new NL2SQLTools(metadataService, dataSourceService, dataSourceFactory, sqlSecurityChecker, maxLimit);
    }

    @AfterEach
    void cleanup() {
        if (cachedAdapterId != null) {
            dataSourceFactory.removeAdapter(cachedAdapterId);
        }
    }

    @Test
    void listTablesReturnsTableListOfTargetDatabase() {
        Assumptions.assumeTrue(mysqlReachable(), "本机 MySQL 未启动，跳过工具集成验证");
        DataSource ds = nlp2sqlTestDatasource();
        cachedAdapterId = ds.getId();

        List<TableInfo> tables = tools.listTables(ds.getId());

        assertFalse(tables.isEmpty(), "数据源 " + TARGET_DATABASE + " 的表清单不应为空");
        assertTrue(tables.size() >= 10, "表数量应 >= 10，实际 " + tables.size());
        assertTrue(tables.stream().allMatch(t -> t.getTableName() != null && !t.getTableName().isBlank()),
                "表名不应为空");
        assertTrue(tables.stream().anyMatch(t -> KNOWN_TABLE.equals(t.getTableName())),
                "表清单应包含 " + KNOWN_TABLE);
        System.out.println("[T4] list_tables(datasource_id=" + ds.getId() + ") -> tables=" + tables.size()
                + " sample=" + tables.stream().limit(5).map(TableInfo::getTableName).toList());
    }

    @Test
    void getSchemaReturnsColumnsOfKnownTable() {
        Assumptions.assumeTrue(mysqlReachable(), "本机 MySQL 未启动，跳过工具集成验证");
        DataSource ds = nlp2sqlTestDatasource();
        cachedAdapterId = ds.getId();

        List<ColumnInfo> columns = tools.getSchema(ds.getId(), KNOWN_TABLE);

        assertFalse(columns.isEmpty(), "表 " + KNOWN_TABLE + " 应返回列信息");
        assertTrue(columns.stream().allMatch(c -> c.getColumnName() != null && !c.getColumnName().isBlank()),
                "列名不应为空");
        assertTrue(columns.stream().allMatch(c -> c.getDataType() != null && !c.getDataType().isBlank()),
                "列类型不应为空");
        assertTrue(columns.stream().anyMatch(c -> "PRI".equals(c.getColumnKey())), "应识别出主键列");
        System.out.println("[T4] get_schema(table=" + KNOWN_TABLE + ") -> columns=" + columns.size()
                + " sample=" + columns.stream().limit(5)
                        .map(c -> c.getColumnName() + ":" + c.getDataType()).toList());
    }

    @Test
    void sampleDataHonoursDefaultAndConfiguredCaps() {
        Assumptions.assumeTrue(mysqlReachable(), "本机 MySQL 未启动，跳过工具集成验证");
        DataSource ds = nlp2sqlTestDatasource();
        cachedAdapterId = ds.getId();

        // 缺省 limit=3
        QueryResult defaulted = tools.sampleData(ds.getId(), KNOWN_TABLE, null);
        assertTrue(defaulted.isSuccess(), "抽样查询应成功: " + defaulted.getErrorMessage());
        assertEquals(NL2SQLTools.DEFAULT_SAMPLE_LIMIT, defaulted.getRowCount(), "缺省应返回 3 行");
        assertFalse(defaulted.getColumns().isEmpty(), "抽样结果应含列名");

        // 显式 limit=5
        QueryResult explicit = tools.sampleData(ds.getId(), KNOWN_TABLE, 5);
        assertTrue(explicit.isSuccess(), "抽样查询应成功: " + explicit.getErrorMessage());
        assertEquals(5, explicit.getRowCount(), "limit=5 应返回 5 行");

        // limit 超过 max-limit 时按上限截断
        QueryResult capped = tools.sampleData(ds.getId(), KNOWN_TABLE, 100000);
        assertTrue(capped.isSuccess(), "抽样查询应成功: " + capped.getErrorMessage());
        assertTrue(capped.getRowCount() <= maxLimit,
                "抽样行数不应超过 max-limit=" + maxLimit + "，实际 " + capped.getRowCount());

        System.out.println("[T4] sample_data(table=" + KNOWN_TABLE + ") -> default=" + defaulted.getRowCount()
                + " rows, limit=5 -> " + explicit.getRowCount() + " rows, limit=100000 -> "
                + capped.getRowCount() + " rows (max-limit=" + maxLimit + "), columns="
                + defaulted.getColumns().size() + " sample=" + defaulted.getRows().stream().limit(1).toList());
    }

    @Test
    void validateSqlAcceptsSelectAndRejectsWrite() {
        // 校验是纯函数，不触达数据库
        ValidationResult ok = tools.validateSql(1L, "SELECT id, name FROM users WHERE age > 30");
        assertTrue(ok.valid(), "合法 SELECT 应通过校验: " + ok.errorMessage());
        assertNull(ok.errorMessage(), "通过校验时不应有错误信息");

        ValidationResult rejected = tools.validateSql(1L, "DELETE FROM users WHERE id = 1");
        assertFalse(rejected.valid(), "DELETE 不应通过校验");
        assertNotNull(rejected.errorMessage(), "拒绝时应给出原因");

        System.out.println("[T4] validate_sql -> SELECT: valid=" + ok.valid()
                + "; DELETE: valid=" + rejected.valid() + " reason=" + rejected.errorMessage());
    }

    @Test
    void executeSqlRunsSelectWithinLimit() {
        Assumptions.assumeTrue(mysqlReachable(), "本机 MySQL 未启动，跳过工具集成验证");
        DataSource ds = nlp2sqlTestDatasource();
        cachedAdapterId = ds.getId();

        QueryResult result = tools.executeSql(ds.getId(),
                "SELECT id, name FROM " + KNOWN_TABLE + " ORDER BY id LIMIT 5");

        assertTrue(result.isSuccess(), "只读查询应成功: " + result.getErrorMessage());
        assertFalse(result.getColumns().isEmpty(), "结果应含列名");
        assertEquals(5, result.getRowCount(), "LIMIT 5 应返回 5 行");
        assertTrue(result.getRowCount() <= maxLimit, "行数不应超过 max-limit=" + maxLimit);
        assertTrue(result.getExecutionTimeMs() >= 0, "应记录执行耗时");

        System.out.println("[T4] execute_sql -> rows=" + result.getRowCount()
                + " columns=" + result.getColumns() + " firstRow=" + result.getRows().get(0)
                + " cost=" + result.getExecutionTimeMs() + "ms");
    }

    /** 按库名定位 nlp2sql_test 数据源，不硬编码 id；查不到直接断言失败。 */
    private DataSource nlp2sqlTestDatasource() {
        DataSource ds = dataSourceMapper.selectOne(new LambdaQueryWrapper<DataSource>()
                .eq(DataSource::getDatabaseName, TARGET_DATABASE)
                .orderByAsc(DataSource::getId)
                .last("LIMIT 1"));
        assertNotNull(ds, "nlp2sql_meta.datasources 中缺少 database_name='" + TARGET_DATABASE
                + "' 的记录，请先在本机元数据库补一条指向该库的 mysql 数据源");
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
