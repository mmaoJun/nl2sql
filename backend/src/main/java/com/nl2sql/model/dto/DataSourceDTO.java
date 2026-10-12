package com.nl2sql.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 数据源创建/更新请求体。
 *
 * <p>更新时密码字段传 "******" 表示不修改。
 */
@Data
@Schema(description = "数据源配置")
public class DataSourceDTO {

    /** 数据源 ID（更新时必填） */
    @Schema(description = "数据源 ID（更新时传入）")
    private Long id;

    /** 数据源名称 */
    @NotBlank(message = "数据源名称不能为空")
    @Schema(description = "数据源名称", example = "生产库", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    /** 数据库类型 */
    @NotBlank(message = "数据源类型不能为空")
    @Schema(description = "数据库类型", example = "mysql", requiredMode = Schema.RequiredMode.REQUIRED)
    private String type;

    /** 主机地址 */
    @NotBlank(message = "主机地址不能为空")
    @Schema(description = "主机地址", example = "192.168.1.100", requiredMode = Schema.RequiredMode.REQUIRED)
    private String host;

    /** 端口号 */
    @NotNull(message = "端口不能为空")
    @Schema(description = "端口号", example = "3306", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer port;

    /** 数据库名称 */
    @NotBlank(message = "数据库名不能为空")
    @Schema(description = "数据库名称", example = "nlp2sql", requiredMode = Schema.RequiredMode.REQUIRED)
    private String databaseName;

    /** 用户名 */
    @NotBlank(message = "用户名不能为空")
    @Schema(description = "数据库用户名", example = "root", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    /** 密码（更新时传 "******" 表示不修改） */
    @NotBlank(message = "密码不能为空")
    @Schema(description = "数据库密码", example = "******", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    /** 扩展配置（JSON 格式） */
    @Schema(description = "扩展配置（JSON）")
    private String extraConfig;
}
