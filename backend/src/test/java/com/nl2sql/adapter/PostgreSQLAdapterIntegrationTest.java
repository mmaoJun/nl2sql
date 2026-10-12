package com.nl2sql.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.mapper.DataSourceMapper;
import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.TableInfo;
import com.nl2sql.model.dto.QueryResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQLAdapter 直连本机 PostgreSQL 16（pgvector 容器，库 nlp2sql_sample）集成验证 —— T24。
 *
 * <p>适配器自 T3 迁移以来一直未做真机验证（此前仅 MySQL / Hive / HDFS / API 有实机用例）。
 * 本用例经 {@link DataSourceFactory} 用既有数据源记录（name='pg_demo'，不硬编码 id）
 * 走真实链路：元数据（表 / 列）+ 关联聚合查询，并断言样例库固定数据（5 部门 / 15 员工，
 * 与 mysql_sample 数据对齐）。
 *
 * <p>守卫：本机 5432 不可达则 skip 而非 fail，保证无环境构建通过。
 */
@SpringBootTest
class PostgreSQLAdapterIntegrationTest {

    /** 既有数据源记录名（nlp2sql_meta.datasources）。 */
    private static final String PG_DATASOURCE_NAME = "pg_demo";

    /** 样例库固定数据。 */
    private static final int DEPARTMENTS = 5;
    private static final int USERS = 15;

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Autowired
    private DataSourceFactory dataSourceFactory;

    /** 本用例经工厂缓存的适配器对应的数据源 id，结束后清理，避免连接池悬挂。 */
    private Long cachedAdapterId;

    @AfterEach
    void cleanup() {
        if (cachedAdapterId != null) {
            dataSourceFactory.removeAdapter(cachedAdapterId);
            cachedAdapterId = null;
        }
    }

    @Test
    void pgAdapterReadsMetadataAndJoinAggregation() {
        Assumptions.assumeTrue(portReachable(5432), "本机 PostgreSQL(5432) 未启动，跳过 PG 真机验证");

        DataSource ds = dataSourceMapper.selectOne(new LambdaQueryWrapper<DataSource>()
                .eq(DataSource::getName, PG_DATASOURCE_NAME)
                .eq(DataSource::getType, "postgresql")
                .last("LIMIT 1"));
        assertNotNull(ds, "缺少 name='" + PG_DATASOURCE_NAME + "' 的 postgresql 数据源记录");
        cachedAdapterId = ds.getId();
        var adapter = dataSourceFactory.getAdapter(ds.getId(), ds.getType(), ds.toAdapterConfig());

        List<String> names = adapter.getTables().stream()
                .map(TableInfo::getTableName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
        for (String expected : List.of("departments", "users", "products", "orders", "order_items")) {
            assertTrue(names.contains(expected), "表清单应含 " + expected + "，实际: " + names);
        }
        List<ColumnInfo> columns = adapter.getColumns("users");
        assertFalse(columns.isEmpty(), "users 列不应为空");

        QueryResult result = adapter.executeQuery(
                "SELECT d.name AS dept_name, COUNT(u.id) AS emp_count FROM departments d"
                        + " LEFT JOIN users u ON u.department_id = d.id"
                        + " GROUP BY d.name ORDER BY emp_count DESC LIMIT 50", 50);
        assertTrue(result.isSuccess(), "PG 关联聚合查询失败: " + result.getErrorMessage());
        assertEquals(DEPARTMENTS, result.getRows().size(), "应有 5 个部门行: " + result.getRows());
        long total = result.getRows().stream()
                .mapToLong(row -> ((Number) row.get("emp_count")).longValue())
                .sum();
        assertEquals(USERS, total, "员工总数应为 " + USERS);
    }

    /** 端口探测（与各集成测试同写法）。 */
    private static boolean portReachable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
