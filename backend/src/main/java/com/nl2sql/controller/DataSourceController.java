package com.nl2sql.controller;

import com.nl2sql.adapter.DataSourceTypeInfo;
import com.nl2sql.model.dto.ApiResponse;
import com.nl2sql.model.dto.DataSourceDTO;
import com.nl2sql.model.vo.DataSourceVO;
import com.nl2sql.service.DataSourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据源管理控制器（T12 从旧项目最小移植，路径与形状逐字对齐）。
 *
 * <p>提供数据源的 CRUD 操作与连接测试功能。所有写操作委托 {@link DataSourceService} 处理，
 * 出参统一使用 {@link DataSourceVO}（密码脱敏），禁止 Entity 直出。
 *
 * <p>路径落在 {@code /api/v1/datasources}，被 {@code SecurityConfig} 的
 * {@code anyRequest().authenticated()} 覆盖（未认证返回 403），无需改安全配置。
 *
 * @see DataSourceService
 * @see DataSourceVO
 */
@RestController
@RequestMapping("/api/v1/datasources")
@RequiredArgsConstructor
@Tag(name = "数据源管理", description = "数据源 CRUD 与连接测试")
public class DataSourceController {

    private final DataSourceService dataSourceService;

    /**
     * 创建数据源。
     *
     * @param dto 数据源创建参数，包含名称、类型、主机、端口、数据库名、用户名、密码
     * @return 创建成功的数据源 VO（密码已脱敏）
     */
    @PostMapping
    @Operation(summary = "创建数据源", description = "新增一个数据源连接配置")
    public ApiResponse<DataSourceVO> create(@Valid @RequestBody DataSourceDTO dto) {
        return ApiResponse.ok(dataSourceService.create(dto));
    }

    /**
     * 查询所有数据源列表。
     *
     * @return 数据源 VO 列表（密码均已脱敏）
     */
    @GetMapping
    @Operation(summary = "数据源列表", description = "获取所有已配置的数据源")
    public ApiResponse<List<DataSourceVO>> list() {
        return ApiResponse.ok(dataSourceService.list());
    }

    /**
     * 更新数据源配置。
     *
     * <p>密码字段传入 "******" 时视为未修改，保留原密码不变。
     * 更新后会清除该数据源的适配器缓存，下次使用时重新建立连接。
     *
     * @param id  数据源 ID
     * @param dto 更新后的数据源参数
     * @return 更新后的数据源 VO
     */
    @PutMapping("/{id}")
    @Operation(summary = "更新数据源", description = "修改数据源连接配置，密码传 ****** 表示不修改")
    public ApiResponse<DataSourceVO> update(@PathVariable Long id,
                                            @Valid @RequestBody DataSourceDTO dto) {
        return ApiResponse.ok(dataSourceService.update(id, dto));
    }

    /**
     * 删除数据源。
     *
     * <p>同时清除适配器缓存并释放连接资源。
     *
     * @param id 数据源 ID
     * @return 空响应体
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除数据源", description = "删除指定数据源及其连接缓存")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        dataSourceService.delete(id);
        return ApiResponse.ok();
    }

    /**
     * 测试数据源连接是否可用。
     *
     * <p>创建临时连接实例进行验证，验证完毕后立即释放，不影响缓存中的持久连接。
     * 失败语义与旧项目一致：HTTP 200 + 业务码 500 + "连接失败"（不是抛异常）。
     *
     * @param id 数据源 ID
     * @return true 表示连接成功，false 表示连接失败
     */
    @PostMapping("/{id}/test")
    @Operation(summary = "测试连接", description = "验证数据源连接参数是否可用")
    public ApiResponse<Boolean> testConnection(@PathVariable Long id) {
        boolean success = dataSourceService.testConnection(id);
        return success ? ApiResponse.ok(true) : ApiResponse.error("连接失败");
    }

    /**
     * 查询后端已注册的所有数据源类型。
     *
     * <p>返回类型编码、展示名与默认端口，供前端动态渲染类型选择器。
     * 新增适配器后该接口自动包含新类型，前端无需改动。
     *
     * @return 数据源类型元数据列表
     */
    @GetMapping("/supported-types")
    @Operation(summary = "支持的数据源类型", description = "返回适配器注册表中的全部类型元数据")
    public ApiResponse<List<DataSourceTypeInfo>> supportedTypes() {
        return ApiResponse.ok(dataSourceService.supportedTypes());
    }
}
