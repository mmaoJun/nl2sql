package com.nl2sql.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nl2sql.common.BusinessException;
import com.nl2sql.common.ErrorCode;
import com.nl2sql.common.TurnState;
import com.nl2sql.mapper.ChatSessionMapper;
import com.nl2sql.mapper.ChatTurnMapper;
import com.nl2sql.model.ChatSession;
import com.nl2sql.model.ChatTurn;
import com.nl2sql.model.dto.SessionCreateRequest;
import com.nl2sql.model.dto.SessionRenameRequest;
import com.nl2sql.model.vo.SessionVO;
import com.nl2sql.model.vo.TurnVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话服务直连本机 MySQL 的集成验证（<b>零 LLM 调用</b>）。
 *
 * <p>验的是 HTTP 用例不方便覆盖的服务层语义：
 * <ol>
 *   <li>{@link ChatSessionService#recentTurns} 的<b>数据源隔离</b>、时间正序与 limit 截断
 *       —— 它是多轮历史注入的唯一取数口径；</li>
 *   <li>首问标题只在占位标题时生效（用户改过名就不再覆盖）；</li>
 *   <li>归属校验：他人会话一律 3001，删除时级联清理轮次。</li>
 * </ol>
 *
 * <p>测试用户用随机 userId 直接构造（{@code chat_sessions} 无外键约束、不读 users 表），
 * 用例结束按实体删除自己造的会话（级联删轮次），不给本机库留垃圾。
 */
@SpringBootTest
class ChatSessionServiceIntegrationTest {

    private static final long USER_A = 900_000_000_001L;
    private static final long USER_B = 900_000_000_002L;
    private static final long DATASOURCE_A = 900_001L;
    private static final long DATASOURCE_B = 900_002L;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private ChatSessionMapper sessionMapper;

    @Autowired
    private ChatTurnMapper turnMapper;

    /** 本用例创建的会话 id，结束后清理 */
    private final List<Long> createdSessionIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long sessionId : createdSessionIds) {
            turnMapper.delete(new LambdaQueryWrapper<ChatTurn>().eq(ChatTurn::getSessionId, sessionId));
            sessionMapper.deleteById(sessionId);
        }
        createdSessionIds.clear();
    }

    /** 历史取数：同会话按数据源隔离、时间正序、limit 截断到最近 N 轮。 */
    @Test
    void recentTurnsFiltersByDatasourceAndKeepsChronologicalOrder() {
        assumeMysqlUp();
        Long sessionId = createSession(USER_A);

        Long a1 = insertTurn(sessionId, DATASOURCE_A, "第一问", "第一答");
        Long b1 = insertTurn(sessionId, DATASOURCE_B, "另一库的提问", "另一库的回答");
        Long a2 = insertTurn(sessionId, DATASOURCE_A, "第二问", "第二答");
        Long a3 = insertTurn(sessionId, DATASOURCE_A, "第三问", "第三答");

        List<ChatTurn> all = sessionService.recentTurns(sessionId, DATASOURCE_A, 3);
        assertEquals(List.of(a1, a2, a3), all.stream().map(ChatTurn::getId).toList(),
                "应只取同数据源的轮次且时间正序（另一库的 " + b1 + " 不得混入）");
        assertEquals("第一答", all.get(0).getAnswer(), "历史轮次要带助手回答文本");

        List<ChatTurn> limited = sessionService.recentTurns(sessionId, DATASOURCE_A, 1);
        assertEquals(List.of(a3), limited.stream().map(ChatTurn::getId).toList(),
                "limit=1 应只取最近一轮");

        assertTrue(sessionService.recentTurns(sessionId, 900_999L, 3).isEmpty(),
                "该会话在该数据源下无轮次，应返回空历史");
        System.out.println("[T11-session] recentTurns 数据源隔离 ok: sessionId=" + sessionId
                + " datasourceA轮次=" + all.stream().map(ChatTurn::getId).toList()
                + " limit1=" + limited.get(0).getId());
    }

    /** 首问标题：只在占位标题时替换；用户改过名后不再覆盖；超长问题按 30 字截断。 */
    @Test
    void firstQuestionTitleOnlyFillsPlaceholder() {
        assumeMysqlUp();
        SessionCreateRequest request = new SessionCreateRequest();
        SessionVO created = sessionService.create(USER_A, request);
        createdSessionIds.add(created.getId());
        assertEquals("新对话", created.getTitle(), "未给标题时应为占位标题");

        sessionService.applyFirstQuestionTitle(created.getId(), "  users 表中共有多少位用户？");
        ChatSession afterFirst = sessionMapper.selectById(created.getId());
        assertEquals("users 表中共有多少位用户？", afterFirst.getTitle(), "首问应把占位标题换成问题（去首尾空白）");

        SessionRenameRequest rename = new SessionRenameRequest();
        rename.setTitle("我的报表");
        sessionService.rename(created.getId(), USER_A, rename);

        sessionService.applyFirstQuestionTitle(created.getId(), "第二问不应覆盖用户改名");
        ChatSession afterSecond = sessionMapper.selectById(created.getId());
        assertEquals("我的报表", afterSecond.getTitle(), "改过名的标题不应被后续提问覆盖");

        SessionVO second = sessionService.create(USER_A, new SessionCreateRequest());
        createdSessionIds.add(second.getId());
        String longQuestion = "这是一句刻意写得很长很长的问题".repeat(3);
        sessionService.applyFirstQuestionTitle(second.getId(), longQuestion);
        String truncated = sessionMapper.selectById(second.getId()).getTitle();
        assertEquals(30, truncated.length(), "首问标题应截断到 30 字，实际: " + truncated);
        assertEquals(longQuestion.substring(0, 30), truncated, "截断应为问题前 30 字");
        System.out.println("[T11-session] 首问标题 ok: 占位→首问→用户改名保留→30 字截断(" + truncated + ")");
    }

    /** 归属校验与级联删除：他人会话一律 3001，删除后轮次不残留。 */
    @Test
    void ownershipEnforcedAndDeleteCascadesTurns() {
        assumeMysqlUp();
        Long sessionId = createSession(USER_A);
        insertTurn(sessionId, DATASOURCE_A, "问", "答");

        BusinessException turnsDenied = assertThrows(BusinessException.class,
                () -> sessionService.listTurns(sessionId, USER_B), "他人读轮次应被拒");
        assertEquals(ErrorCode.SESSION_NOT_FOUND, turnsDenied.getCode(), "越权与不存在共用 3001");

        SessionRenameRequest rename = new SessionRenameRequest();
        rename.setTitle("越权改名");
        assertEquals(ErrorCode.SESSION_NOT_FOUND,
                assertThrows(BusinessException.class,
                        () -> sessionService.rename(sessionId, USER_B, rename)).getCode(),
                "他人改名应被拒");

        assertEquals(ErrorCode.UNAUTHORIZED, assertThrows(BusinessException.class,
                () -> sessionService.requireOwned(sessionId, null)).getCode(),
                "未认证使用会话应为 401");

        List<TurnVO> turns = sessionService.listTurns(sessionId, USER_A);
        assertEquals(1, turns.size(), "本人应能读到 1 轮");
        assertEquals("答", turns.get(0).getAnswer(), "轮次视图应含 assistant 回答");

        sessionService.delete(sessionId, USER_A);
        createdSessionIds.remove(sessionId);
        assertNull(sessionMapper.selectById(sessionId), "删除后会话应不存在");
        assertTrue(turnMapper.selectList(new LambdaQueryWrapper<ChatTurn>()
                .eq(ChatTurn::getSessionId, sessionId)).isEmpty(), "删除应级联清掉轮次");
        System.out.println("[T11-session] 归属校验与级联删除 ok: sessionId=" + sessionId);
    }

    /** 缺少 must-pass 字段的会话不会进入任何查询（回归保护：list 只返回本人会话）。 */
    @Test
    void listOnlyReturnsCurrentUsersSessions() {
        assumeMysqlUp();
        Long sessionId = createSession(USER_A);
        assertTrue(sessionService.list(USER_A).stream().anyMatch(vo -> sessionId.equals(vo.getId())),
                "本人会话应出现在列表里");
        assertFalse(sessionService.list(USER_B).stream().anyMatch(vo -> sessionId.equals(vo.getId())),
                "他人列表不应包含该会话");
        System.out.println("[T11-session] 会话列表隔离 ok: sessionId=" + sessionId);
    }

    private Long createSession(long userId) {
        SessionCreateRequest request = new SessionCreateRequest();
        request.setDatasourceId(DATASOURCE_A);
        Long sessionId = sessionService.create(userId, request).getId();
        assertNotNull(sessionId, "新建会话应回填自增 id");
        createdSessionIds.add(sessionId);
        return sessionId;
    }

    private Long insertTurn(Long sessionId, Long datasourceId, String question, String answer) {
        ChatTurn turn = new ChatTurn();
        turn.setSessionId(sessionId);
        turn.setQuestion(question);
        turn.setDatasourceId(datasourceId);
        turn.setModelName("qwen2.5-coder:7b");
        turn.setModelProvider("ollama");
        turn.setGeneratedSql("SELECT 1 LIMIT 1");
        turn.setRowCount(1);
        turn.setExecutionTimeMs(10L);
        turn.setStatus(TurnState.TURN_OK);
        turn.setAnswer(answer);
        return sessionService.insertTurn(turn);
    }

    /** MySQL 可达才执行（与既有两个集成用例同一守卫模式）。 */
    private void assumeMysqlUp() {
        Assumptions.assumeTrue(portReachable(3306), "本机 MySQL 未启动，跳过会话服务集成验证");
    }

    private static boolean portReachable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
