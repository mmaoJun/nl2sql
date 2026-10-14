package com.nl2sql.tool;

import com.nl2sql.adapter.DataSourceAdapter;
import com.nl2sql.adapter.DataSourceFactory;
import com.nl2sql.common.BusinessException;
import com.nl2sql.common.ErrorCode;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.dto.QueryResult;
import com.nl2sql.security.SqlSecurityChecker;
import com.nl2sql.service.DataSourceService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link NL2SQLTools#executeSql} 执行失败路径的纯单元测试（不依赖 Spring / 数据库）。
 *
 * <p>回归一条已定位的缺陷链：适配器把 SQLException 收敛成 {@code success=false} 而不抛
 * （数据源预览接口依赖该容错），若工具层照旧返回，AgentScope 会记成工具调用成功，
 * 于是 trace / 审计 / FINAL 的 sql&rows 全部把失败当成功。本用例锁定新语义：
 * 执行失败必须抛 {@link BusinessException}，且异常消息带上数据库原始错误与自纠引导。
 */
class NL2SQLToolsExecuteFailureTest {

    /** 数据库原始错误（适配器会加"查询执行失败: "前缀，这里模拟真实形态） */
    private static final String DB_ERROR = "Unknown column 'oi.user_id' in 'on clause'";

    @Test
    void executeSqlThrowsWhenAdapterReportsFailure() {
        // 适配器返回 success=false 的失败结果（模拟 SQLException 被吞后的形态）
        QueryResult failed = new QueryResult();
        failed.setSuccess(false);
        failed.setErrorMessage("查询执行失败: " + DB_ERROR);

        DataSourceAdapter adapter = mock(DataSourceAdapter.class);
        when(adapter.executeQuery(anyString(), anyInt())).thenReturn(failed);

        DataSource ds = new DataSource();
        ds.setId(1L);
        ds.setType("mysql");

        DataSourceFactory factory = mock(DataSourceFactory.class);
        when(factory.getAdapter(eq(1L), anyString(), anyMap())).thenReturn(adapter);

        DataSourceService dataSourceService = mock(DataSourceService.class);
        when(dataSourceService.getById(1L)).thenReturn(ds);

        // 校验器用真实实例：SQL 本身合法，失败发生在执行环节
        NL2SQLTools tools = new NL2SQLTools(null, dataSourceService, factory, new SqlSecurityChecker(), 100);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tools.executeSql(1L, "SELECT id FROM users"),
                "执行失败必须抛异常，不能伪装成功返回");

        assertEquals(ErrorCode.SQL_EXECUTION_FAILED, ex.getCode(), "应使用执行失败错误码 2003");
        assertTrue(ex.getMessage().contains(DB_ERROR),
                "异常消息应带上数据库原始错误: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("get_schema") && ex.getMessage().contains("execute_sql"),
                "异常消息应带自纠引导（get_schema / execute_sql）: " + ex.getMessage());
    }

    @Test
    void executeSqlStillRejectsUnsafeSqlBeforeTouchingDatasource() {
        // 校验失败仍走 SQL_VALIDATION_FAILED，证明 2003 只在执行环节产生
        NL2SQLTools tools = new NL2SQLTools(null, mock(DataSourceService.class),
                mock(DataSourceFactory.class), new SqlSecurityChecker(), 100);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tools.executeSql(1L, "DELETE FROM users WHERE id = 1"),
                "写操作必须在校验环节被拒");
        assertEquals(ErrorCode.SQL_VALIDATION_FAILED, ex.getCode());
    }
}
