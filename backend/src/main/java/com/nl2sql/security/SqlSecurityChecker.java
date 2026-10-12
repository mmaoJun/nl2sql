package com.nl2sql.security;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SQL 安全校验器。
 *
 * <p>三层防御机制：
 * <ul>
 *   <li>L1 — 关键词黑名单：拒绝 INSERT/UPDATE/DELETE/DROP 等危险操作</li>
 *   <li>L2 — AST 分析：使用 JSqlParser 验证语句必须是 SELECT</li>
 *   <li>L3 — 注入模式检测：拦截堆叠查询和 UNION 注入</li>
 * </ul>
 * 另提供 {@link #enforceLimit} 方法强制添加 LIMIT 子句。
 *
 * <p>L1 的匹配是"整词"而非"子串"，且扫描前会先剥掉字符串字面量与带引号的标识符：
 * 列名 {@code created_at} 含子串 CREATE、{@code updated_at} 含 UPDATE，按子串匹配会把
 * 所有涉及时间列的查询全部拦下；{@code WHERE note = 'DELETE FROM users'} 这类正常取值
 * 同样不该被当成写操作。真正的写语句由整词匹配 + AST 双层拦住。
 *
 * <p>L3 的 UNION 注入检测同样在剥离字面量与注释后的代码片段上进行：字面量里的
 * UNION 关键词属于正常取值不该误伤；注释拆开 UNION 与 SELECT 的绕过写法则会在
 * 剥离注释后还原为完整模式被拦下。
 *
 * @see com.nl2sql.service.NL2SQLEngine
 */
@Component
@Slf4j
public class SqlSecurityChecker {

    /** 危险关键词；顺序即报错优先级，保证同一条 SQL 报出的关键词是确定的。 */
    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "DROP", "TRUNCATE", "ALTER", "CREATE", "INSERT", "UPDATE", "DELETE",
            "REPLACE", "MERGE", "GRANT", "REVOKE",
            "EXEC", "EXECUTE", "CALL", "SHUTDOWN", "LOAD_FILE"
    );

    private static final Map<String, Pattern> FORBIDDEN_PATTERNS = buildForbiddenPatterns();

    private static final Pattern STACKED_QUERIES = Pattern.compile(";\\s*(?!\\s*$)");
    private static final Pattern COMMENT_INJECTION = Pattern.compile("(/\\*|--|#)");
    /**
     * UNION 注入：UNION [ALL|DISTINCT] [(] SELECT。
     *
     * <p>比朴素写法多兼容两种变形：第二个 SELECT 带括号（如 UNION (SELECT ...)）与
     * UNION 后紧跟 MySQL 的 DISTINCT 关键字；注释拆开的写法则由调用处先剥离注释。
     */
    private static final Pattern UNION_INJECTION = Pattern.compile(
            "\\bUNION\\b\\s*(?:ALL|DISTINCT)?\\s*\\(*\\s*\\bSELECT\\b", Pattern.CASE_INSENSITIVE);

    /** 单引号字符串字面量（含 SQL 里的 '' 转义） */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:''|[^'])*'", Pattern.DOTALL);
    /** 反引号标识符（MySQL） */
    private static final Pattern BACKTICK_IDENTIFIER = Pattern.compile("`[^`]*`");
    /** 双引号标识符 */
    private static final Pattern QUOTED_IDENTIFIER = Pattern.compile("\"[^\"]*\"");
    /** 块注释 */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*(?:[^*]|\\*(?!/))*\\*/", Pattern.DOTALL);
    /** 行注释 */
    private static final Pattern LINE_COMMENT = Pattern.compile("(--|#)[^\\n]*");
    private static final Pattern LIMIT_CLAUSE = Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE);

    private static Map<String, Pattern> buildForbiddenPatterns() {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        for (String keyword : FORBIDDEN_KEYWORDS) {
            patterns.put(keyword, Pattern.compile("\\b" + keyword + "\\b"));
        }
        return patterns;
    }

    /**
     * 校验 SQL 安全性。
     *
     * <p>依次执行关键词黑名单、AST 类型验证和注入模式检测，任一失败即返回失败结果。
     *
     * @param sql 待校验的 SQL 语句
     * @return 校验结果，包含是否通过和错误消息
     */
    public ValidationResult validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return ValidationResult.fail("SQL 不能为空");
        }

        String trimmed = sql.trim();

        // 剥离字面量/引号标识符/注释后的"可执行代码"片段：L1 黑名单与 L3 UNION 检测
        // 都基于它，既避免字面量误伤，也防止用注释拆开关键词绕过检测。
        String strippedSql = stripLiteralsAndQuotedIdentifiers(trimmed);

        // L1: 关键词黑名单（整词匹配，先剥掉字面量与带引号标识符避免误杀）
        String scannable = strippedSql.toUpperCase();
        for (Map.Entry<String, Pattern> entry : FORBIDDEN_PATTERNS.entrySet()) {
            if (entry.getValue().matcher(scannable).find()) {
                log.warn("SQL 包含禁止关键词: {}", entry.getKey());
                return ValidationResult.fail("包含禁止的SQL关键字: " + entry.getKey());
            }
        }

        // L2: AST 分析 - 验证是 SELECT
        try {
            Statement stmt = CCJSqlParserUtil.parse(trimmed);
            if (!(stmt instanceof Select)) {
                return ValidationResult.fail("只允许 SELECT 查询语句");
            }
        } catch (JSQLParserException e) {
            log.warn("SQL 解析失败: {}", e.getMessage());
            return ValidationResult.fail("SQL 语法错误: " + e.getMessage());
        }

        // L3: 注入模式检测
        if (STACKED_QUERIES.matcher(trimmed).find()) {
            return ValidationResult.fail("禁止堆叠查询");
        }
        if (UNION_INJECTION.matcher(strippedSql).find()) {
            log.warn("检测到 UNION 注入模式");
            return ValidationResult.fail("检测到可疑的 UNION 查询");
        }

        return ValidationResult.ok();
    }

    /**
     * 剥掉不参与语法判断的片段：字符串字面量、带引号标识符、注释。
     *
     * <p>顺序固定为先字面量再注释，否则字符串里的 {@code --} 会吞掉后面的真实代码。
     *
     * @param sql 原始 SQL
     * @return 只保留代码片段的扫描用文本
     */
    private static String stripLiteralsAndQuotedIdentifiers(String sql) {
        String stripped = STRING_LITERAL.matcher(sql).replaceAll(" ");
        stripped = BACKTICK_IDENTIFIER.matcher(stripped).replaceAll(" ");
        stripped = QUOTED_IDENTIFIER.matcher(stripped).replaceAll(" ");
        stripped = BLOCK_COMMENT.matcher(stripped).replaceAll(" ");
        return LINE_COMMENT.matcher(stripped).replaceAll(" ");
    }

    /**
     * 强制为 SQL 添加 LIMIT 子句，防止全表扫描。
     *
     * <p>去除末尾分号后，若 SQL 中无 LIMIT 子句则追加 {@code LIMIT maxLimit}。
     * 判断是否已有限制同样按整词匹配：列名 {@code limit_count} 不算已加过限制。
     *
     * @param sql      待处理的 SQL
     * @param maxLimit 最大返回行数
     * @return 处理后的 SQL
     */
    public String enforceLimit(String sql, int maxLimit) {
        String cleaned = sql.trim();
        if (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        if (!LIMIT_CLAUSE.matcher(stripLiteralsAndQuotedIdentifiers(cleaned)).find()) {
            return cleaned + " LIMIT " + maxLimit;
        }
        return cleaned;
    }

    /**
     * SQL 校验结果。
     *
     * @param valid        是否通过校验
     * @param errorMessage 校验失败时的错误消息，通过时为 null
     */
    public record ValidationResult(boolean valid, String errorMessage) {

        /** 校验通过。 */
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }
        /** 校验失败。 */
        public static ValidationResult fail(String message) {
            return new ValidationResult(false, message);
        }
    }
}
