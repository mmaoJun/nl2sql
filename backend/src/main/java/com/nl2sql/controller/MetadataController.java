package com.nl2sql.controller;

import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.TableInfo;
import com.nl2sql.model.dto.ApiResponse;
import com.nl2sql.model.dto.QueryResult;
import com.nl2sql.service.MetadataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 元数据浏览控制器（T12 从旧项目最小移植，路径与形状逐字对齐）。
 *
 * <p>提供数据源的表结构查询能力，包括表列表、字段详情、数据预览、缓存刷新。
 * 元数据通过 Redis 缓存（默认 TTL 3600s），Redis 不可用时由
 * {@link MetadataService} 内部降级为直连查询，对调用方透明。
 *
 * <p>路径落在 {@code /api/v1/metadata}，被 {@code SecurityConfig} 的
 * {@code anyRequest().authenticated()} 覆盖（未认证返回 403），无需改安全配置。
 *
 * @see MetadataService
 */
@RestController
@RequestMapping("/api/v1/metadata")
@RequiredArgsConstructor
@Tag(name = "元数据管理", description = "表结构查询与缓存刷新")
public class MetadataController {

    private final MetadataService metadataService;

    /**
     * 获取指定数据源的表列表（精简信息，不含字段详情）。
     *
     * @param datasourceId 数据源 ID
     * @return 表信息列表，每项包含表名、表注释与列数量（columns 仍为 null）
     */
    @GetMapping("/tables")
    @Operation(summary = "获取表列表", description = "查询指定数据源的所有表（精简信息，含列数量）")
    public ApiResponse<List<TableInfo>> getTables(@RequestParam Long datasourceId) {
        return ApiResponse.ok(metadataService.getTables(datasourceId));
    }

    /**
     * 获取指定表的字段详情。
     *
     * @param datasourceId 数据源 ID
     * @param tableName    表名
     * @return 字段信息列表，包含列名、数据类型、注释、主键标识等
     */
    @GetMapping("/tables/{tableName}/columns")
    @Operation(summary = "获取字段列表", description = "查询指定表的字段详情")
    public ApiResponse<List<ColumnInfo>> getColumns(@RequestParam Long datasourceId,
                                                    @PathVariable String tableName) {
        return ApiResponse.ok(metadataService.getColumns(datasourceId, tableName));
    }

    /**
     * 分页预览指定表的数据（数据源详情页使用）。
     *
     * <p>只读抽样，内部执行 {@code SELECT * FROM <表名> LIMIT <pageSize> OFFSET <offset>}
     * 取当前页，另执行 {@code SELECT COUNT(*) FROM <表名>} 取整表总行数，一并返回给前端渲染页码。
     * 表名是被拼进 SQL 的外部入参，是唯一的拼接注入面，因此做了双重拦截：先按标识符白名单
     * （{@code [库名.]表名}）过滤并确认它确实存在于该数据源的 Schema 中，再把拼接完成的
     * <b>每一条</b> SQL（取数 + 计数）整体过 {@link com.nl2sql.security.SqlSecurityChecker}，
     * 不因为"是自己拼的"而免检。
     *
     * @param datasourceId 数据源 ID
     * @param tableName    表名（需为该数据源中真实存在的表）
     * @param page         页码，可选，默认 1，小于 1 时按 1 处理
     * @param pageSize     每页行数，可选，默认 50，非正数按默认值处理，超过系统上限时按上限截断
     * @return 预览查询结果（列名 + 当前页数据行 + 整表总行数 total）
     */
    @GetMapping("/tables/{tableName}/preview")
    @Operation(summary = "分页预览表数据",
            description = "分页查看指定表的数据（只读 SELECT）。page 默认 1，pageSize 默认 50，"
                    + "超过系统上限时按上限截断；响应中的 total 为整表总行数，供前端渲染页码")
    public ApiResponse<QueryResult> previewTable(@RequestParam Long datasourceId,
                                                 @PathVariable String tableName,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.ok(metadataService.previewTable(datasourceId, tableName, page, pageSize));
    }

    /**
     * 手动刷新元数据缓存。
     *
     * <p>清除 Redis 中的 Schema 缓存和适配器连接缓存，重新从数据源拉取最新表结构。
     *
     * @param datasourceId 数据源 ID
     * @return 空响应体
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新缓存", description = "清除元数据缓存并重新加载表结构")
    public ApiResponse<Void> refreshCache(@RequestParam Long datasourceId) {
        metadataService.refreshCache(datasourceId);
        return ApiResponse.ok();
    }
}
