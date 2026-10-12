package com.nl2sql.model.vo;

import com.nl2sql.model.ChatSession;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话视图对象。
 *
 * <p>同时充当两种角色：单条返回（新建/重命名后，由 {@link #fromEntity} 转换）和
 * 列表投影（{@code ChatSessionMapper.selectSessionList} 直接映射，此时
 * {@code turnCount} 与 {@code preview} 由 SQL 的相关子查询一次带出，不做 N+1）。
 * Mapper 直接映射到 VO 而不是实体，是因为这两列不属于任何一张表。
 */
@Data
@Schema(description = "会话摘要")
public class SessionVO {

    @Schema(description = "会话 ID")
    private Long id;

    @Schema(description = "会话标题")
    private String title;

    @Schema(description = "会话默认数据源 ID，可为空")
    private Long datasourceId;

    @Schema(description = "轮次数")
    private Integer turnCount;

    @Schema(description = "最后一轮问题的前 40 字，用于列表辨识")
    private String preview;

    @Schema(description = "最后活跃时间")
    private LocalDateTime updatedAt;

    /**
     * 从实体转换为视图对象，用于单条返回。
     *
     * <p>{@code turnCount} 置 0 而不是留 null：前端要直接渲染这个数字，
     * 而 Jackson 配了 non_null，null 字段会从响应里整个消失。
     *
     * @param session 会话实体
     * @return 会话视图
     */
    public static SessionVO fromEntity(ChatSession session) {
        SessionVO vo = new SessionVO();
        vo.setId(session.getId());
        vo.setTitle(session.getTitle());
        vo.setDatasourceId(session.getDatasourceId());
        vo.setTurnCount(0);
        vo.setUpdatedAt(session.getUpdatedAt());
        return vo;
    }
}
