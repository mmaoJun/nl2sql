package com.nl2sql.model.vo;

import com.nl2sql.model.DataSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源视图对象。
 *
 * <p>用于前端展示，密码字段脱敏为 "******"。
 * 通过 {@link #fromEntity(DataSource)} 从实体转换。
 */
@Data
@Schema(description = "数据源信息")
public class DataSourceVO {

    @Schema(description = "数据源 ID")
    private Long id;
    @Schema(description = "数据源名称")
    private String name;
    @Schema(description = "数据库类型")
    private String type;
    @Schema(description = "主机地址")
    private String host;
    @Schema(description = "端口号")
    private Integer port;
    @Schema(description = "数据库名称")
    private String databaseName;
    @Schema(description = "用户名")
    private String username;
    @Schema(description = "密码（脱敏）")
    private String password;
    @Schema(description = "扩展配置")
    private String extraConfig;
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    /**
     * 从实体转换为视图对象，密码脱敏为 "******"。
     *
     * @param ds 数据源实体
     * @return 脱敏后的视图对象
     */
    public static DataSourceVO fromEntity(DataSource ds) {
        DataSourceVO vo = new DataSourceVO();
        vo.setId(ds.getId());
        vo.setName(ds.getName());
        vo.setType(ds.getType());
        vo.setHost(ds.getHost());
        vo.setPort(ds.getPort());
        vo.setDatabaseName(ds.getDatabaseName());
        vo.setUsername(ds.getUsername());
        vo.setPassword("******");
        vo.setExtraConfig(ds.getExtraConfig());
        vo.setCreatedAt(ds.getCreatedAt());
        vo.setUpdatedAt(ds.getUpdatedAt());
        return vo;
    }
}
