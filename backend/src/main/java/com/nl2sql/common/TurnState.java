package com.nl2sql.common;

/**
 * 对话轮次状态列的取值常量。
 *
 * <p>列是 VARCHAR 而不是枚举；用常量类而不是 Java enum，是为了不给 MyBatis-Plus 的
 * {@code @EnumValue} 映射多引一层配置。散落的字符串字面量会让"改一个状态值要搜全仓库"，
 * 故集中在此。
 *
 * <p>旧项目的 {@code rewrite_status}（skipped / applied / failed）常量<b>不迁移</b>：
 * 本项目明确不做追问改写，实体不写该列，库里取 DDL 默认值 {@code skipped}。
 *
 * @see com.nl2sql.model.ChatTurn
 */
public final class TurnState {

    private TurnState() {}

    // ── chat_turns.status ──

    /** 本轮问答正常完成且回答非空 */
    public static final String TURN_OK = "ok";
    /** 本轮问答失败（异常终止），原因见 error_message */
    public static final String TURN_FAILED = "failed";
    /**
     * 本轮问答正常完成但回答为空。
     *
     * <p>与 {@link #TURN_FAILED} 分开取值：空回答不是执行失败（SQL 可能已经跑成、
     * rows 可用），单独一档便于后续评测统计"模型没说话"的占比。
     */
    public static final String TURN_EMPTY = "empty";
    /**
     * 本轮问答被客户端取消（SSE 订阅中断 / 连接关闭），原因见 error_message。
     *
     * <p>（T25 新增）与 {@link #TURN_FAILED} 分开取值：取消不是模型或引擎故障，
     * 把它记成 failed 会让"模型/数据源出问题"的统计掺进"用户主动离开"。
     * 列是 VARCHAR(20)、库端无枚举约束，新增取值仅为消费方（统计 / 前端）的区分符。
     */
    public static final String TURN_CANCELLED = "cancelled";
}
