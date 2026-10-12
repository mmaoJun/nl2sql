package com.nl2sql.model;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 数据源实体，映射 {@code datasources} 表。
 *
 * <p>存储数据库连接配置信息，包括类型、地址、端口、认证信息等。
 * 通过 {@link #toAdapterConfig()} 转换为适配器所需的 Map 配置。
 *
 * @see com.nl2sql.model.vo.DataSourceVO
 * @see com.nl2sql.adapter.DataSourceAdapter
 */
@Data
@TableName("datasources")
public class DataSource {

    private static final ObjectMapper EXTRA_CONFIG_MAPPER = new ObjectMapper();
    private static final Set<String> STANDARD_CONFIG_KEYS =
            Set.of("host", "port", "databaseName", "username", "password");

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源名称 */
    private String name;

    /** 数据库类型（如 mysql、postgresql） */
    private String type;

    /** 主机地址 */
    private String host;

    /** 端口号 */
    private Integer port;

    /** 数据库名称 */
    private String databaseName;

    /** 用户名 */
    private String username;

    /** 密码（明文存储） */
    private String password;

    /** 扩展配置（JSON 格式） */
    private String extraConfig;

    /** 创建时间，由 MyBatis-Plus 自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间，由 MyBatis-Plus 自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 转换为数据源适配器所需的配置 Map。
     *
     * <p>{@code extraConfig}（JSON 对象）中的键会合并进配置，
     * 供方言专属参数（如 PostgreSQL 的 {@code schema}）透传给适配器；
     * 标准连接字段不允许被覆盖。JSON 非法时静默忽略。
     *
     * @return 包含 host、port、databaseName、username、password 及扩展参数的配置 Map
     */
    public Map<String, Object> toAdapterConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("host", this.host);
        config.put("port", this.port);
        config.put("databaseName", this.databaseName);
        config.put("username", this.username);
        config.put("password", this.password);
        mergeExtraConfig(config);
        return config;
    }

    private void mergeExtraConfig(Map<String, Object> config) {
        if (this.extraConfig == null || this.extraConfig.isBlank()) {
            return;
        }
        try {
            Map<String, Object> extra = EXTRA_CONFIG_MAPPER.readValue(this.extraConfig, new TypeReference<>() {});
            extra.forEach((key, value) -> {
                if (!STANDARD_CONFIG_KEYS.contains(key) && value != null) {
                    config.put(key, value);
                }
            });
        } catch (Exception e) {
            // 非法 JSON 忽略扩展参数，不影响标准连接
        }
    }
}
