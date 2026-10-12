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

    /** 列信息列表 */
    private List<ColumnInfo> columns;
}
