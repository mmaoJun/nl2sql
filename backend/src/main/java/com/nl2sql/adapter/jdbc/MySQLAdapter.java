package com.nl2sql.adapter.jdbc;

import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.TableInfo;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MySQL 数据源适配器。
 *
 * <p>连接池与查询执行由 {@link JdbcDataSourceAdapter} 基类提供；
 * 元数据读取覆写为 {@code INFORMATION_SCHEMA} 查询，以获取基类无法提供的
 * {@code COLUMN_TYPE}（如 varchar(64)）与 {@code COLUMN_KEY}（PRI/UNI）信息。
 *
 * @see JdbcDataSourceAdapter
 */
@Slf4j
public class MySQLAdapter extends JdbcDataSourceAdapter {

    /** {@inheritDoc} */
    @Override
    protected String buildJdbcUrl(Map<String, Object> config) {
        return String.format(
                "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                config.get("host"), port(config), database(config));
    }

    /** {@inheritDoc} */
    @Override
    public List<TableInfo> getTables() {
        List<TableInfo> tables = new ArrayList<>();
        String sql = """
                SELECT TABLE_NAME, TABLE_COMMENT
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
                ORDER BY TABLE_NAME
                """;

        try (Connection conn = connection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                TableInfo table = new TableInfo();
                table.setTableName(rs.getString("TABLE_NAME"));
                table.setComment(rs.getString("TABLE_COMMENT"));
                table.setColumns(getColumns(table.getTableName()));
                tables.add(table);
            }
        } catch (SQLException e) {
            log.error("获取表列表失败: {}", e.getMessage(), e);
        }
        return tables;
    }

    /** {@inheritDoc} */
    @Override
    public List<ColumnInfo> getColumns(String tableName) {
        List<ColumnInfo> columns = new ArrayList<>();
        String sql = """
                SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, COLUMN_COMMENT,
                       COLUMN_KEY, IS_NULLABLE, COLUMN_DEFAULT
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """;

        try (Connection conn = connection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo col = new ColumnInfo();
                    col.setColumnName(rs.getString("COLUMN_NAME"));
                    col.setDataType(rs.getString("DATA_TYPE"));
                    col.setColumnType(rs.getString("COLUMN_TYPE"));
                    col.setComment(rs.getString("COLUMN_COMMENT"));
                    col.setColumnKey(rs.getString("COLUMN_KEY"));
                    col.setIsNullable(rs.getString("IS_NULLABLE"));
                    col.setDefaultValue(rs.getString("COLUMN_DEFAULT"));
                    columns.add(col);
                }
            }
        } catch (SQLException e) {
            log.error("获取表 {} 字段失败: {}", tableName, e.getMessage(), e);
        }
        return columns;
    }

    /** {@inheritDoc} */
    @Override
    public String getDialect() {
        return "mysql";
    }
}
