package com.nl2sql.model;

import lombok.Data;
import java.util.List;

/**
 * 表结构信息。
 *
 * <p>包含表名、注释和列信息列表，由数据源适配器从数据库元数据中获取。
 *
 * @see ColumnInfo
 * @see com.nl2sql.service.MetadataService
 */
@Data
public class TableInfo {

    /** 表名 */
    private String tableName;

    /** 表注释 */
    private String comment;

    /**
     * 列数量。
     *
     * <p>仅由 {@link com.nl2sql.service.MetadataService#getTables(Long)} 在返回精简表列表时
     * 按 {@link #columns} 的 size 填充（供前端表列表直接展示"列数"，避免逐表再查一次）；
     * 完整 Schema（{@code getSchema}）中该字段为 null，列详情以 {@link #columns} 为准。
     */
    private Integer columnCount;

    /** 列信息列表 */
    private List<ColumnInfo> columns;
}
