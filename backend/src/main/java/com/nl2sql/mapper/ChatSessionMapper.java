package com.nl2sql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nl2sql.model.ChatSession;
import com.nl2sql.model.vo.SessionVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 对话会话 Mapper 接口。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper}，另加一个 {@link #touch(Long)}：
 * 记录新一轮时要让会话的 {@code updated_at} 前移，否则列表按最后活跃排序会失灵
 * （插入轮次并不会更新会话行）。显式 UPDATE 而不是依赖 DDL 的
 * {@code ON UPDATE CURRENT_TIMESTAMP}，是为了让"排序依据被推进了"这件事在代码里看得见。
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    /**
     * 查某用户的会话列表，一次带出轮次数与最后一轮预览。
     *
     * <p>两个相关子查询而不是"先查会话再逐个查轮次"：后者是 N+1，50 个会话就是 51 次查询。
     * {@code preview} 用 {@code COALESCE(resolved_question, question)}，因为未改写的轮次
     * {@code resolved_question} 是 NULL；再用 {@code LEFT(..., 40)} 在库里截断，
     * 免得把整段长问题拖到应用层（元数据库固定 MySQL，可以直接用 MySQL 函数）。
     *
     * <p>返回类型直接是 VO 而非实体：{@code turnCount} 与 {@code preview} 不属于任何一张表。
     * 列名靠 {@code map-underscore-to-camel-case} 自动映射到 VO 的同名属性。
     *
     * @param userId 归属用户
     * @param limit  最多返回条数
     * @return 会话摘要列表，按最后活跃时间倒序
     */
    @Select("""
            SELECT s.id, s.title, s.datasource_id, s.updated_at,
                   (SELECT COUNT(*) FROM chat_turns t WHERE t.session_id = s.id) AS turn_count,
                   (SELECT LEFT(COALESCE(t.resolved_question, t.question), 40) FROM chat_turns t
                     WHERE t.session_id = s.id ORDER BY t.id DESC LIMIT 1) AS preview
            FROM chat_sessions s
            WHERE s.user_id = #{userId}
            ORDER BY s.updated_at DESC
            LIMIT #{limit}
            """)
    List<SessionVO> selectSessionList(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * 把会话的最后活跃时间推到当前时刻。
     *
     * @param id 会话 ID
     * @return 受影响行数
     */
    @Update("UPDATE chat_sessions SET updated_at = NOW() WHERE id = #{id}")
    int touch(@Param("id") Long id);
}
