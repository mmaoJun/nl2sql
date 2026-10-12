package com.nl2sql.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.common.BusinessException;
import com.nl2sql.common.ErrorCode;
import com.nl2sql.mapper.ChatSessionMapper;
import com.nl2sql.mapper.ChatTurnMapper;
import com.nl2sql.model.ChatSession;
import com.nl2sql.model.ChatTurn;
import com.nl2sql.model.dto.SessionCreateRequest;
import com.nl2sql.model.dto.SessionRenameRequest;
import com.nl2sql.model.vo.SessionVO;
import com.nl2sql.model.vo.TurnVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 会话资源服务：会话与轮次的持久化与归属校验。
 *
 * <p>职责边界：只管"这份数据属于谁、能不能读写"，不参与一次提问的编排 ——
 * 后者在 {@link ChatService}。这样拆是因为归属校验必须无条件生效
 * （它是安全边界），而提问编排里塞满了降级分支，混在一起容易让校验被"顺手"跳过。
 *
 * <p>本类的方法<b>允许抛异常</b>；把异常吞成降级的责任在调用方，
 * 因为只有调用方知道"这次失败该不该影响用户拿到查询结果"。
 *
 * @see ChatService
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSessionService {

    /** 会话列表最多返回条数 */
    static final int LIST_LIMIT = 50;
    /** 恢复对话时最多加载的轮次数 */
    static final int TURN_LIMIT = 100;
    /** 自动标题的截断长度 */
    static final int TITLE_LENGTH = 30;
    /** 首轮提问前的占位标题 */
    static final String DEFAULT_TITLE = "新对话";

    private final ChatSessionMapper sessionMapper;
    private final ChatTurnMapper turnMapper;

    /**
     * 新建会话。
     *
     * @param userId  归属用户，由调用方从 JWT 解析，<b>不来自请求体</b>
     * @param request 标题与默认数据源，均可空
     * @return 新建会话的视图（turnCount 为 0，无 preview）
     */
    public SessionVO create(Long userId, SessionCreateRequest request) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(normalizeTitle(request.getTitle()));
        session.setDatasourceId(request.getDatasourceId());
        sessionMapper.insert(session);
        log.info("新建会话: id={}, userId={}", session.getId(), userId);
        return SessionVO.fromEntity(session);
    }

    /**
     * 查当前用户的会话列表，按最后活跃时间倒序。
     *
     * @param userId 归属用户
     * @return 会话摘要列表，最多 {@value #LIST_LIMIT} 条
     */
    public List<SessionVO> list(Long userId) {
        return sessionMapper.selectSessionList(userId, LIST_LIMIT);
    }

    /**
     * 查会话的历史轮次，用于刷新页面后重建对话。
     *
     * <p>只取最近 {@value #TURN_LIMIT} 轮：先在库里倒序 LIMIT，再在内存里翻回正序，
     * 这样长会话不会把响应撑爆，同时前端拿到的仍是时间正序。
     *
     * @param sessionId 会话 ID
     * @param userId    当前用户，用于归属校验
     * @return 轮次视图列表，时间正序
     * @throws BusinessException 会话不存在或不属于该用户（3001）
     */
    public List<TurnVO> listTurns(Long sessionId, Long userId) {
        requireOwned(sessionId, userId);
        List<ChatTurn> turns = turnMapper.selectList(new LambdaQueryWrapper<ChatTurn>()
                .eq(ChatTurn::getSessionId, sessionId)
                .orderByDesc(ChatTurn::getId)
                .last("LIMIT " + TURN_LIMIT));
        Collections.reverse(turns);
        return turns.stream().map(TurnVO::fromEntity).toList();
    }

    /**
     * 取同会话<b>同数据源</b>的最近若干轮，供多轮追问注入历史。
     *
     * <p>按数据源过滤是硬要求：不同库的表结构完全不同，把 PostgreSQL 的历史轮次
     * 喂给一个 MySQL 提问，只会诱导模型生成引用不存在表的 SQL。
     * 副作用是换库后的第一轮等于"该库下的首轮"，取到空上下文，这正是期望行为。
     *
     * <p>先在库里倒序 LIMIT 再翻回正序，命中 {@code idx_session_datasource}。
     * {@code limit} 来自配置项而不是用户输入，字符串拼接不构成注入面。
     *
     * @param sessionId    会话 ID
     * @param datasourceId 数据源 ID
     * @param limit        最多取几轮
     * @return 历史轮次实体，时间正序
     */
    public List<ChatTurn> recentTurns(Long sessionId, Long datasourceId, int limit) {
        List<ChatTurn> turns = turnMapper.selectList(new LambdaQueryWrapper<ChatTurn>()
                .eq(ChatTurn::getSessionId, sessionId)
                .eq(ChatTurn::getDatasourceId, datasourceId)
                .orderByDesc(ChatTurn::getId)
                .last("LIMIT " + limit));
        Collections.reverse(turns);
        return turns;
    }

    /**
     * 取同会话最近若干轮（<b>跨数据源</b>），供「对话式跨源融合」追问注入历史（T23）。
     *
     * <p>与 {@link #recentTurns} 的区别是<b>不按数据源过滤</b>：同一会话先后对不同
     * 数据源提问时（如先查 MySQL 的员工数、再查 REST API 的预算），第二轮需要看到
     * 第一轮的真实数值才能做汇总对比（应用层融合）。历史以原文注入、不带数据源标注
     * （见 {@code ChatService#buildMessages}）：实测本地 7B 会把两组不同的「数据源ID为 N」
     * 句式误读为更换数据源的写指令而拒答（T23 探针，见报告）。同数据源会话行为与
     * {@link #recentTurns} 完全一致。
     *
     * <p>先在库里倒序 LIMIT 再翻回正序；{@code limit} 来自配置项而非用户输入。
     *
     * @param sessionId 会话 ID
     * @param limit     最多取几轮
     * @return 历史轮次实体，时间正序
     */
    public List<ChatTurn> recentSessionTurns(Long sessionId, int limit) {
        List<ChatTurn> turns = turnMapper.selectList(new LambdaQueryWrapper<ChatTurn>()
                .eq(ChatTurn::getSessionId, sessionId)
                .orderByDesc(ChatTurn::getId)
                .last("LIMIT " + limit));
        Collections.reverse(turns);
        return turns;
    }

    /**
     * 重命名会话。
     *
     * @param sessionId 会话 ID
     * @param userId    当前用户
     * @param request   新标题，不能为空白（由 DTO 上的 {@code @NotBlank} 保证）
     * @return 改名后的会话视图
     * @throws BusinessException 会话不存在或不属于该用户（3001）
     */
    public SessionVO rename(Long sessionId, Long userId, SessionRenameRequest request) {
        ChatSession session = requireOwnedEntity(sessionId, userId);
        session.setTitle(request.getTitle().trim());
        sessionMapper.updateById(session);
        log.info("重命名会话: id={}", sessionId);
        return SessionVO.fromEntity(session);
    }

    /**
     * 删除会话并级联物理删除其轮次。
     *
     * <p>审计记录不在删除范围内：{@code audit_logs} 是运维留痕，
     * 用户删掉自己的对话不应该连带抹掉系统的操作记录。
     *
     * @param sessionId 会话 ID
     * @param userId    当前用户
     * @throws BusinessException 会话不存在或不属于该用户（3001）
     */
    public void delete(Long sessionId, Long userId) {
        requireOwned(sessionId, userId);
        turnMapper.delete(new LambdaQueryWrapper<ChatTurn>().eq(ChatTurn::getSessionId, sessionId));
        sessionMapper.deleteById(sessionId);
        log.info("删除会话: id={}, userId={}", sessionId, userId);
    }

    /**
     * 校验会话归属。
     *
     * <p>{@code sessionId} 为 null 表示"无会话模式"，直接放行 —— 单轮提问
     * （如评测脚本）走的就是这条路，它不该被会话逻辑拦住。归属不符与 id 不存在
     * 返回同一个 3001，不区分二者，否则调用方可以靠错误码枚举出别人的会话 id。
     *
     * @param sessionId 会话 ID，可为 null
     * @param userId    当前用户 ID，可为 null（未认证）
     * @throws BusinessException 未认证（401）、会话不存在或不属于该用户（3001）
     */
    public void requireOwned(Long sessionId, Long userId) {
        requireOwnedEntity(sessionId, userId);
    }

    /**
     * 写入一轮对话。
     *
     * @param turn 轮次实体，插入后自增 id 会回填到该对象
     * @return 轮次 id
     */
    public Long insertTurn(ChatTurn turn) {
        turnMapper.insert(turn);
        return turn.getId();
    }

    /**
     * 首轮提问后把占位标题换成问题前 30 字。
     *
     * <p>用一条带条件的 UPDATE 而不是"先查再判断再改"：会话可能已被用户改名，
     * 条件里排除掉已改过的标题，就不会把用户的命名覆盖掉。
     *
     * @param sessionId 会话 ID
     * @param question  用户原话
     */
    public void applyFirstQuestionTitle(Long sessionId, String question) {
        String title = question == null ? "" : question.trim();
        if (title.length() > TITLE_LENGTH) {
            title = title.substring(0, TITLE_LENGTH);
        }
        if (title.isBlank()) {
            title = DEFAULT_TITLE;
        }
        ChatSession session = new ChatSession();
        session.setId(sessionId);
        session.setTitle(title);
        sessionMapper.update(session, new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .and(w -> w.eq(ChatSession::getTitle, DEFAULT_TITLE)
                        .or().isNull(ChatSession::getTitle)));
    }

    /**
     * 把会话的最后活跃时间推到当前时刻。
     *
     * @param sessionId 会话 ID
     */
    public void touch(Long sessionId) {
        sessionMapper.touch(sessionId);
    }

    private ChatSession requireOwnedEntity(Long sessionId, Long userId) {
        if (sessionId == null) {
            return null;
        }
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未认证，无法使用会话功能");
        }
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND, "会话不存在或已删除");
        }
        return session;
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_TITLE;
        }
        return title.trim();
    }
}
