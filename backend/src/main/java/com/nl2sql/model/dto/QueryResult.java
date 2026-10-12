package com.nl2sql.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * SQL 执行结果。
 *
 * <p>由数据源适配器执行 SQL 后返回，包含列名、数据行和执行状态。
 */
@Data
@Schema(description = "SQL 执行结果")
public class QueryResult {

    /** 结果列名列表 */
    @Schema(description = "结果列名")
    private java.util.List<String> columns;

    /** 结果数据行 */
    @Schema(description = "结果数据行")
    private java.util.List<java.util.Map<String, Object>> rows;

    /** 结果行数 */
    @Schema(description = "结果行数")
    private int rowCount;

    /** 执行耗时（毫秒） */
    @Schema(description = "执行耗时（毫秒）")
    private long executionTimeMs;

    /** 执行是否成功 */
    @Schema(description = "是否成功")
    private boolean success;

    /** 错误消息 */
    @Schema(description = "错误消息")
    private String errorMessage;
}
