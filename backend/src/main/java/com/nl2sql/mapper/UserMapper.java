package com.nl2sql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nl2sql.model.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper 接口。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper}，提供 {@link User} 实体的标准 CRUD 操作。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
