package com.nl2sql.service;

import com.nl2sql.model.ColumnInfo;
import com.nl2sql.model.TableInfo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Schema 压缩服务。
 *
 * <p>根据用户查询对表结构进行相关性过滤和多级压缩，控制 Prompt 中的 Schema Token 数量。
 * 压缩策略：
 * <ul>
 *   <li>L0：按查询关键词过滤相关表，最多保留 15 张</li>
 *   <li>L1：完整格式（列名 + 类型 + 注释），适用于 Token 预算内</li>
 *   <li>L2：精简格式（仅列名），适用于 Token 超预算时降级</li>
 * </ul>
 * Token 预算默认 800。
 *
 * @see com.nl2sql.service.MetadataService
 */
@Service
public class SchemaCompressor {

    private static final int TOKEN_BUDGET = 800;
    private static final int AVG_CHARS_PER_TOKEN = 2;

    /**
     * 压缩 Schema 信息，使其适配 LLM Token 预算。
     *
     * <p>先按用户查询过滤相关表，再根据估算 Token 数选择 L1 或 L2 格式输出。
     *
     * @param tables    数据源的完整表结构列表
     * @param userQuery 用户自然语言输入，用于相关性过滤
     * @return 压缩后的 Schema 文本
     */
    public String compress(List<TableInfo> tables, String userQuery) {
        if (tables == null || tables.isEmpty()) {
            return "无可用表结构";
        }

        List<TableInfo> relevant = filterRelevantTables(tables, userQuery);
        if (relevant.isEmpty()) {
            relevant = tables.size() > 15 ? tables.subList(0, 15) : tables;
        }

        int estimatedTokens = estimateTokens(relevant, 0);

        if (estimatedTokens <= TOKEN_BUDGET) {
            return formatLevel1(relevant);
        }

        // 尝试 L1 压缩
        String l1 = formatLevel1(relevant);
        if (estimateTokens(l1) <= TOKEN_BUDGET) {
            return l1;
        }

        // L2 压缩
        return formatLevel2(relevant);
    }

    private List<TableInfo> filterRelevantTables(List<TableInfo> tables, String query) {
        if (query == null || query.isBlank()) {
            return tables;
        }

        Set<String> queryTokens = tokenize(query);

        return tables.stream()
                .filter(t -> isRelevant(t, queryTokens))
                .limit(15)
                .collect(Collectors.toList());
    }

    private boolean isRelevant(TableInfo table, Set<String> queryTokens) {
        String tableName = table.getTableName().toLowerCase().replace("_", "");
        for (String token : queryTokens) {
            if (tableName.contains(token)) return true;
        }
        if (table.getComment() != null) {
            for (String token : queryTokens) {
                if (table.getComment().contains(token)) return true;
            }
        }
        if (table.getColumns() != null) {
            for (ColumnInfo col : table.getColumns()) {
                if (col.getComment() != null) {
                    for (String token : queryTokens) {
                        if (col.getComment().contains(token)) return true;
                    }
                }
                String colName = col.getColumnName().toLowerCase().replace("_", "");
                for (String token : queryTokens) {
                    if (colName.contains(token)) return true;
                }
            }
        }
        return false;
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new java.util.HashSet<>();
        // 中文按2字切分 + 英文按词切分
        for (int i = 0; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) {
                tokens.add(text.substring(i, Math.min(i + 2, text.length())));
                tokens.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                int start = i;
                while (i < text.length() && Character.isLetterOrDigit(text.charAt(i))) i++;
                tokens.add(text.substring(start, i).toLowerCase());
                i--;
            }
        }
        return tokens;
    }

    private String formatLevel1(List<TableInfo> tables) {
        StringBuilder sb = new StringBuilder();
        sb.append("表结构:\n");
        for (TableInfo table : tables) {
            sb.append("- ").append(table.getTableName());
            if (table.getComment() != null && !table.getComment().isBlank()) {
                sb.append("(").append(table.getComment()).append(")");
            }
            sb.append("(");
            if (table.getColumns() != null) {
                List<String> colDefs = new ArrayList<>();
                for (ColumnInfo col : table.getColumns()) {
                    String colDef = col.getColumnName() + " " + col.getDataType().toUpperCase();
                    if (col.getComment() != null && !col.getComment().isBlank()) {
                        colDef += " " + col.getComment();
                    }
                    colDefs.add(colDef);
                }
                sb.append(String.join(", ", colDefs));
            }
            sb.append(")\n");
        }

        // 添加外键关系提示
        sb.append("\n注意: 请根据字段名推断表间关联关系，如 user_id 关联 users.id");
        return sb.toString();
    }

    private String formatLevel2(List<TableInfo> tables) {
        StringBuilder sb = new StringBuilder();
        sb.append("表结构:\n");
        for (TableInfo table : tables) {
            sb.append("- ").append(table.getTableName()).append("(");
            if (table.getColumns() != null) {
                List<String> colNames = table.getColumns().stream()
                        .map(ColumnInfo::getColumnName)
                        .collect(Collectors.toList());
                sb.append(String.join(", ", colNames));
            }
            sb.append(")\n");
        }
        return sb.toString();
    }

    private int estimateTokens(List<TableInfo> tables, int extra) {
        StringBuilder sb = new StringBuilder();
        for (TableInfo t : tables) {
            sb.append(t.getTableName());
            if (t.getColumns() != null) {
                for (ColumnInfo c : t.getColumns()) {
                    sb.append(c.getColumnName()).append(c.getDataType());
                    if (c.getComment() != null) sb.append(c.getComment());
                }
            }
        }
        return sb.length() / AVG_CHARS_PER_TOKEN + extra;
    }

    private int estimateTokens(String text) {
        return text.length() / AVG_CHARS_PER_TOKEN;
    }
}
