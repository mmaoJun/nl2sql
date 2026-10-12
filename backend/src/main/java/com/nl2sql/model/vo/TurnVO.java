package com.nl2sql.model.vo;

import com.nl2sql.model.ChatTurn;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话轮次视图对象。
 *
 * <p>用于刷新页面后重建对话（本项目的多轮上下文由服务端从 {@code chat_turns} 注入，
 * 前端拿它只做展示）。<b>不含结果行</b>：库里就没存，历史轮次只显示 SQL、
 * 当时的行数与耗时。想要数据请重新提问 —— 重放旧结果既要重新执行 SQL，
 * 又会遇到"数据和当时不一样"的困惑，本期不做。
 */
@Data
@Schema(description = "对话轮次")
public class TurnVO {

    @Schema(description = "轮次 ID")
    private Long id;

    @Schema(description = "用户原话")
    private String question;

    @Schema(description = "改写后的独立问题，本项目不做改写，恒为空")
    private String resolvedQuestion;

    @Schema(description = "改写状态，本项目不做改写，恒为 skipped")
    private String rewriteStatus;

    @Schema(description = "本轮查询的数据源 ID")
    private Long datasourceId;

    @Schema(description = "本轮实际使用的模型 id")
    private String modelName;

    @Schema(description = "本轮实际使用的 provider")
    private String modelProvider;

    @Schema(description = "本轮生成的 SQL")
    private String generatedSql;

    @Schema(description = "当时返回的行数")
    private Integer rowCount;

    @Schema(description = "当时的端到端耗时（毫秒）")
    private Long executionTimeMs;

    @Schema(description = "本轮结果：ok / failed / empty / cancelled")
    private String status;

    @Schema(description = "失败原因")
    private String errorMessage;

    @Schema(description = "本轮助手回答文本")
    private String answer;

    @Schema(description = "提问时间")
    private LocalDateTime createdAt;

    /**
     * 从实体转换为视图对象。
     *
     * @param turn 轮次实体
     * @return 轮次视图
     */
    public static TurnVO fromEntity(ChatTurn turn) {
        TurnVO vo = new TurnVO();
        vo.setId(turn.getId());
        vo.setQuestion(turn.getQuestion());
        vo.setResolvedQuestion(turn.getResolvedQuestion());
        vo.setRewriteStatus(turn.getRewriteStatus());
        vo.setDatasourceId(turn.getDatasourceId());
        vo.setModelName(turn.getModelName());
        vo.setModelProvider(turn.getModelProvider());
        vo.setGeneratedSql(turn.getGeneratedSql());
        vo.setRowCount(turn.getRowCount());
        vo.setExecutionTimeMs(turn.getExecutionTimeMs());
        vo.setStatus(turn.getStatus());
        vo.setErrorMessage(turn.getErrorMessage());
        vo.setAnswer(turn.getAnswer());
        vo.setCreatedAt(turn.getCreatedAt());
        return vo;
    }
}
