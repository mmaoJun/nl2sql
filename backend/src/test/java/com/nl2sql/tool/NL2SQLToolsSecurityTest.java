package com.nl2sql.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.adapter.DataSourceFactory;
import com.nl2sql.common.BusinessException;
import com.nl2sql.common.ErrorCode;
import com.nl2sql.mapper.DataSourceMapper;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.dto.QueryResult;
import com.nl2sql.security.SqlSecurityChecker;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * execute_sql / sample_data 的安全兜底验证：不依赖模型先调 validate_sql。
 *
 * <p>覆盖课题硬性要求"只读 100% 生效"的三个面：
 * <ul>
 *   <li>DELETE 等写操作直接被拒，且拒绝前后数据行数不变（真的没执行）</li>
 *   <li>堆叠注入（分号拼接第二条语句）被拒</li>
 *   <li>无 LIMIT 的 SELECT 会被自动追加 LIMIT，大数据表也只回 max-limit 行</li>
 * </ul>
 * 拒绝对外统一是 {@link BusinessException} + {@link ErrorCode#SQL_VALIDATION_FAILED}，
 * 让拒绝原因能被 Agent 学到。
 */
@SpringBootTest
class NL2SQLToolsSecurityTest {

    private static final String TARGET_DATABASE = "nlp2sql_test";
    private static final String KNOWN_TABLE = "users";
    /** 行数远大于 max-limit 的表，用于验证自动 LIMIT 真的截住了结果 */
    private static final String BIG_TABLE = "api_access_logs";

    @Autowired
    private MetadataService metadataService;

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private DataSourceFactory dataSourceFactory;

    /** 记录 execute_sql 实际交给适配器的 SQL 文本，验证 enforceLimit 这一步真的发生了 */
    private RecordingChecker recordingChecker;

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Value("${nl2sql.sql-security.max-limit:100}")
    private int maxLimit;

    private NL2SQLTools tools;

    private Long cachedAdapterId;

    @BeforeEach
    void setUp() {
        recordingChecker = new RecordingChecker();
        tools = new NL2SQLTools(metadataService, dataSourceService, dataSourceFactory, recordingChecker, maxLimit);
    }

    @AfterEach
    void cleanup() {
        if (cachedAdapterId != null) {
            dataSourceFactory.removeAdapter(cachedAdapterId);
        }
    }

    @Test
    void executeSqlRejectsDeleteWithoutModelSideValidation() {
        Assumptions.assumeTrue(mysqlReachable(), "本机 MySQL 未启动，跳过安全兜底验证");
        DataSource ds = nlp2sqlTestDatasource();
        cachedAdapterId = ds.getId();

        long before = countRows(ds.getId(), KNOWN_TABLE);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tools.executeSql(ds.getId(), "DELETE FROM " + KNOWN_TABLE + " WHERE id = 1"),
                "DELETE 必须被拒绝");
        assertEquals(ErrorCode.SQL_VALIDATION_FAILED, ex.getCode(), "拒绝应使用 2002 校验失败错误码");
        assertTrue(ex.getMessage().contains("DELETE"), "拒绝原因应指出具体关键词: " + ex.getMessage());

        long after = countRows(ds.getId(), KNOWN_TABLE);
        assertEquals(before, after, "被拒的 DELETE 不应影响数据行数");

        System.out.println("[T4] execute_sql(DELETE) -> rejected code=" + ex.getCode()
                + " message=" + ex.getMessage()
                + "; rows before=" + before + " after=" + after);
    }

    @Test
    void executeSqlRejectsStackedAndUnionInjection() {
        Assumptions.assumeTrue(mysqlReachable(), "本机 MySQL 未启动，跳过安全兜底验证");
        DataSource ds = nlp2sqlTestDatasource();
        cachedAdapterId = ds.getId();

        String stacked = "SELECT * FROM " + KNOWN_TABLE + " LIMIT 1; DROP TABLE " + KNOWN_TABLE;
        BusinessException stackedEx = assertThrows(BusinessException.class,
                () -> tools.executeSql(ds.getId(), stacked), "堆叠注入必须被拒绝");
        assertEquals(ErrorCode.SQL_VALIDATION_FAILED, stackedEx.getCode());

        String unionInjection = "SELECT id FROM " + KNOWN_TABLE + " WHERE id = 1 UNION SELECT id FROM orders";
        BusinessException unionEx = assertThrows(BusinessException.class,
                () -> tools.executeSql(ds.getId(), unionInjection), "UNION 注入必须被拒绝");
        assertEquals(ErrorCode.SQL_VALIDATION_FAILED, unionEx.getCode());

        // 表仍然在，说明堆叠的第二条语句一次都没跑到
        assertTrue(countRows(ds.getId(), KNOWN_TABLE) > 0, "目标表应完好无损");

        System.out.println("[T4] execute_sql(stacked) -> rejected: " + stackedEx.getMessage()
                + "; execute_sql(union) -> rejected: " + unionEx.getMessage());
    }

    @Test
    void sampleDataRejectsInjectedTableName() {
        Assumptions.assumeTrue(mysqlReachable(), "本机 MySQL 未启动，跳过安全兜底验证");
        DataSource ds = nlp2sqlTestDatasource();
        cachedAdapterId = ds.getId();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tools.sampleData(ds.getId(), KNOWN_TABLE + "; DROP TABLE " + KNOWN_TABLE, 3),
                "拼接注入的表名必须被拒绝");
        assertEquals(ErrorCode.SQL_VALIDATION_FAILED, ex.getCode());
        assertTrue(countRows(ds.getId(), KNOWN_TABLE) > 0, "目标表应完好无损");

        System.out.println("[T4] sample_data(注入表名) -> rejected: " + ex.getMessage());
    }

    @Test
    void executeSqlAutoAppendsLimitForBareSelect() {
        Assumptions.assumeTrue(mysqlReachable(), "本机 MySQL 未启动，跳过安全兜底验证");
        DataSource ds = nlp2sqlTestDatasource();
        cachedAdapterId = ds.getId();

        QueryResult result = tools.executeSql(ds.getId(), "SELECT * FROM " + BIG_TABLE);
        String enforcedBare = recordingChecker.lastEnforcedSql;

        assertTrue(result.isSuccess(), "只读查询应成功: " + result.getErrorMessage());
        assertTrue(result.getRowCount() <= maxLimit,
                "无 LIMIT 的 SELECT 行数不应超过 max-limit=" + maxLimit + "，实际 " + result.getRowCount());
        assertEquals("SELECT * FROM " + BIG_TABLE + " LIMIT " + maxLimit, enforcedBare,
                "execute_sql 应先 enforceLimit 再交给适配器");

        // 已带 LIMIT 的语句不应被重复追加
        tools.executeSql(ds.getId(), "SELECT * FROM " + BIG_TABLE + " LIMIT 2");
        assertEquals("SELECT * FROM " + BIG_TABLE + " LIMIT 2", recordingChecker.lastEnforcedSql,
                "已有限制的语句应原样保留");

        // 上限收到 3 时，对近两千行的表也只回 3 行，证明截断真的生效
        NL2SQLTools tightTools = new NL2SQLTools(
                metadataService, dataSourceService, dataSourceFactory, recordingChecker, 3);
        QueryResult tight = tightTools.executeSql(ds.getId(), "SELECT * FROM " + BIG_TABLE);

        assertEquals(3, tight.getRowCount(), "max-limit=3 时大数据表也应只回 3 行");

        System.out.println("[T4] execute_sql(无 LIMIT) -> rows=" + result.getRowCount()
                + " max-limit=" + maxLimit + " enforcedSql=[" + enforcedBare + "]"
                + "; max-limit=3 -> rows=" + tight.getRowCount());
    }

    /** 数行数用同一工具走只读查询，避免为测试引入额外的直连通道。 */
    private long countRows(Long datasourceId, String tableName) {
        QueryResult result = tools.executeSql(datasourceId, "SELECT COUNT(*) AS cnt FROM " + tableName);
        assertTrue(result.isSuccess(), "计数查询应成功: " + result.getErrorMessage());
        assertNotNull(result.getRows().get(0).get("cnt"));
        return ((Number) result.getRows().get(0).get("cnt")).longValue();
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

    /** 记录 enforceLimit 输出的校验器替身，仅用于断言"先加 LIMIT 再执行"。 */
    private static class RecordingChecker extends SqlSecurityChecker {

        private String lastEnforcedSql;

        @Override
        public String enforceLimit(String sql, int maxLimit) {
            this.lastEnforcedSql = super.enforceLimit(sql, maxLimit);
            return this.lastEnforcedSql;
        }
    }
}
