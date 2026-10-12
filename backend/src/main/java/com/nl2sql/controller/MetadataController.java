package com.nl2sql.controller;

import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.TableInfo;
import com.nl2sql.model.dto.ApiResponse;
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
 * <p>提供数据源的表结构查询能力，包括表列表、字段详情、缓存刷新。
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
     * @return 表信息列表，每项包含表名和表注释
     */
    @GetMapping("/tables")
    @Operation(summary = "获取表列表", description = "查询指定数据源的所有表（精简信息）")
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
