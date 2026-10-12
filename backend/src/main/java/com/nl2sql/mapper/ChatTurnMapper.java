package com.nl2sql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nl2sql.model.ChatTurn;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话轮次 Mapper 接口。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper}。上下文查询、最近 N 轮等条件都用
 * {@code LambdaQueryWrapper} 在 Service 层拼，不需要自定义 SQL。
 */
@Mapper
public interface ChatTurnMapper extends BaseMapper<ChatTurn> {
}
