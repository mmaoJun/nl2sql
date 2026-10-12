package com.nl2sql.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.adapter.DataSourceFactory;
import com.nl2sql.mapper.DataSourceMapper;
import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.TableInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 元数据服务与 Schema 压缩的直连本机 MySQL 集成验证。
 *
 * <p>只验单元测试验不出来的东西：{@code nlp2sql_test} 数据源记录真的能按库名定位、
 * {@link MetadataService} 经适配器读到的真实表结构与列信息真的非空、
 * {@link SchemaCompressor} 对真实 Schema 产出的压缩文本真的可用，
 * 以及 Redis 缓存读/写/刷新两条路径（Redis 运行 → 缓存命中；Redis 未运行 → 优雅降级）
 * 都能正常返回。
 *
 * <p>数据源按 {@code database_name='nlp2sql_test'} 定位，不硬编码 id；查不到时
 * 直接断言失败并提示补记录。Redis 走哪条路径由端口探测自动判定，两种环境均须通过。
 *
 * <p>命名用 {@code *Test} 而不是 {@code *IT}：surefire 默认只收 {@code *Test}。
 */
@SpringBootTest
class MetadataServiceIntegrationTest {

    private static final String TARGET_DATABASE = "nlp2sql_test";
    private static final String CACHE_KEY_PREFIX = "schema:";
    private static final String SAMPLE_QUESTION = "统计每个商品分类的订单总金额，按金额降序取前 10 名";

    @Autowired
    private MetadataService metadataService;

    @Autowired
    private SchemaCompressor schemaCompressor;

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Autowired
    private DataSourceFactory dataSourceFactory;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** 本用例内经工厂缓存的适配器对应的数据源 id，结束时清理，避免连接池悬挂 */
    private Long cachedAdapterId;

    @AfterEach
    void cleanup() {
        if (cachedAdapterId != null) {
            dataSourceFactory.removeAdapter(cachedAdapterId);
        }
    }

    @Test
    void getTablesReturnsFullTableListOfTargetDatabase() {
        Assumptions.assumeTrue(mysqlReachable(), "本机 MySQL 未启动，跳过元数据服务集成验证");
        DataSource ds = nlp2sqlTestDatasource();
        cachedAdapterId = ds.getId();

        List<TableInfo> tables = metadataService.getTables(ds.getId());

        assertFalse(tables.isEmpty(), "数据源 " + TARGET_DATABASE + " 的表清单不应为空");
        assertTrue(tables.size() >= 10, "表数量应 >= 10，实际 " + tables.size());
        assertTrue(tables.stream().allMatch(t -> t.getTableName() != null && !t.getTableName().isBlank()),
                "表名不应为空");
        System.out.println("[T7] datasource id=" + ds.getId() + " db=" + ds.getDatabaseName()
                + " getTables=" + tables.size()
                + " sample=" + tables.stream().limit(5).map(TableInfo::getTableName).toList());
    }

    @Test
    void getColumnsReturnsColumnsForKnownTable() {
        Assumptions.assumeTrue(mysqlReachable(), "本机 MySQL 未启动，跳过元数据服务集成验证");
        DataSource ds = nlp2sqlTestDatasource();
        cachedAdapterId = ds.getId();

        List<TableInfo> tables = metadataService.getTables(ds.getId());
        String tableName = tables.stream()
                .map(TableInfo::getTableName)
                .filter("users"::equals)
                .findFirst()
                .orElseGet(() -> tables.get(0).getTableName());

        List<ColumnInfo> columns = metadataService.getColumns(ds.getId(), tableName);

        assertFalse(columns.isEmpty(), "表 " + tableName + " 应返回列信息");
        assertTrue(columns.stream().allMatch(c -> c.getColumnName() != null && !c.getColumnName().isBlank()),
                "列名不应为空");
        assertTrue(columns.stream().allMatch(c -> c.getDataType() != null && !c.getDataType().isBlank()),
                "列类型不应为空");
        System.out.println("[T7] getColumns table=" + tableName + " columns=" + columns.size()
                + " sample=" + columns.stream().limit(5)
                        .map(c -> c.getColumnName() + ":" + c.getDataType()).toList());
    }

    @Test
    void compressProducesUsableSchemaText() {
        Assumptions.assumeTrue(mysqlReachable(), "本机 MySQL 未启动，跳过 Schema 压缩集成验证");
        DataSource ds = nlp2sqlTestDatasource();
        cachedAdapterId = ds.getId();

        // 与后续工具层一致的两级输入：轻量表清单（无列）与完整 Schema（含列）
        List<TableInfo> tables = metadataService.getTables(ds.getId());
        String compressedLight = schemaCompressor.compress(tables, SAMPLE_QUESTION);
        assertNotNull(compressedLight, "压缩结果不应为 null");
        assertFalse(compressedLight.isBlank(), "压缩结果不应为空");
        assertTrue(tables.stream().anyMatch(t -> compressedLight.contains(t.getTableName())),
                "压缩结果应包含至少一个表名");

        List<TableInfo> fullSchema = metadataService.getSchema(ds.getId());
        String compressedFull = schemaCompressor.compress(fullSchema, SAMPLE_QUESTION);
        assertFalse(compressedFull.isBlank(), "含列 Schema 的压缩结果不应为空");

        System.out.println("[T7] compress(question=" + SAMPLE_QUESTION + ") light-len=" + compressedLight.length()
                + " full-len=" + compressedFull.length());
        System.out.println("[T7] compress sample(first 500 chars):\n" + truncate(compressedFull, 500));
    }

    @Test
    void cacheReadWriteAndRefreshFollowRedisAvailability() {
        Assumptions.assumeTrue(mysqlReachable(), "本机 MySQL 未启动，跳过缓存路径集成验证");
        DataSource ds = nlp2sqlTestDatasource();
        cachedAdapterId = ds.getId();
        Long id = ds.getId();
        boolean redisUp = redisReachable();

        List<TableInfo> first = metadataService.getSchema(id);
        assertFalse(first.isEmpty(), "首次加载 Schema 不应为空");

        if (redisUp) {
            // 命中路径：首次加载写入缓存，二次读取应命中且反序列化仍是 TableInfo
            assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(CACHE_KEY_PREFIX + id)),
                    "Redis 可用时首次加载应写入缓存键 " + CACHE_KEY_PREFIX + id);
            List<TableInfo> second = metadataService.getSchema(id);
            assertEquals(first.size(), second.size(), "缓存命中应返回与首次相同的表数量");
            assertInstanceOf(TableInfo.class, second.get(0), "缓存反序列化应还原为 TableInfo");
        }

        // 两条路径都必须正常返回：Redis 不可用时读/写/refreshCache 全部降级不抛异常
        metadataService.refreshCache(id);
        List<TableInfo> reloaded = metadataService.getSchema(id);
        assertFalse(reloaded.isEmpty(), "刷新后 Schema 不应为空");
        assertEquals(first.size(), reloaded.size(), "刷新前后表数量应一致");

        if (redisUp) {
            assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(CACHE_KEY_PREFIX + id)),
                    "刷新后应重新写入缓存");
        }

        System.out.println("[T7] cache path=" + (redisUp ? "HIT(Redis 运行中：写入/命中/刷新)" : "DEGRADE(Redis 未运行：读/写/refreshCache 降级)")
                + " tables=" + reloaded.size());
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
        return portReachable(3306);
    }

    /** Redis 是否监听本机 6379，判定测试走缓存命中还是降级路径。 */
    private boolean redisReachable() {
        return portReachable(6379);
    }

    private boolean portReachable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
