package com.nl2sql.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户实体，映射 {@code users} 表。
 *
 * @see com.nl2sql.service.AuthService
 */
@Data
@TableName("users")
public class User {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户名（唯一） */
    private String username;

    /** 密码（BCrypt 加密） */
    private String password;

    /** 角色（如 USER、ADMIN） */
    private String role;

    /** 创建时间，由 MyBatis-Plus 自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
