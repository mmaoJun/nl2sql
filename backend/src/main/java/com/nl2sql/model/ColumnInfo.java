package com.nl2sql.model;

import lombok.Data;

/**
 * 列结构信息。
 *
 * <p>描述单张表中一个列的元数据，包括列名、数据类型、注释等。
 *
 * @see TableInfo
 */
@Data
public class ColumnInfo {

    /** 列名 */
    private String columnName;

    /** 数据类型（如 VARCHAR、INT） */
    private String dataType;

    /** 完整列类型（如 varchar(255)） */
    private String columnType;

    /** 列注释 */
    private String comment;

    /** 键类型（如 PRI、UNI） */
    private String columnKey;

    /** 是否可为空（YES/NO） */
    private String isNullable;

    /** 默认值 */
    private String defaultValue;
}
