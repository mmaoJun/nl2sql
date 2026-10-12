package com.nl2sql.security;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 安全校验器测试。
 *
 * <p>对照课题安全要求逐项覆盖：
 * ① 关键词黑名单；② JSqlParser AST 校验（仅 SELECT 放行）；③ 堆叠查询；
 * ④ UNION 注入；⑤ 注释绕过；⑥ 字面量 / 引号标识符误伤；⑦ enforceLimit 行为。
 *
 * <p>重点是防误杀：列名里含有危险关键字子串（{@code created_at} 含 CREATE、
 * {@code updated_at} 含 UPDATE）时必须放行，否则所有时间趋势类查询都会被拦下；
 * 同时真正的写操作仍必须被拦住。
 */
class SqlSecurityCheckerTest {

    private final SqlSecurityChecker checker = new SqlSecurityChecker();

    // ---------- ⑥ 字面量 / 引号标识符误伤 ----------

    @Test
    void allowsColumnsWhoseNameContainsForbiddenKeyword() {
        assertValid("SELECT to_char(created_at, 'YYYY-MM') AS month, COUNT(*) AS order_count "
                + "FROM orders GROUP BY month ORDER BY month");
        assertValid("SELECT u.name, u.updated_at FROM users u ORDER BY u.updated_at DESC LIMIT 10");
        assertValid("SELECT insert_id, delete_flag FROM audit_sample LIMIT 10");
    }

    @Test
    void allowsForbiddenKeywordInsideStringLiteralAndQuotedIdentifier() {
        assertValid("SELECT id FROM users WHERE name = 'DELETE FROM users'");
        assertValid("SELECT `create`, `update` FROM flags LIMIT 10");
        assertValid("SELECT \"create\", \"update\" FROM flags LIMIT 10");
        assertValid("SELECT id FROM users WHERE note = 'DROP TABLE x' AND age > 25");
    }

    // ---------- ① 关键词黑名单 ----------

    @Test
    void blocksRealWriteAndDdlStatements() {
        assertRejected("DELETE FROM users");
        assertRejected("DROP TABLE users");
        assertRejected("CREATE TABLE t (id INT)");
        assertRejected("INSERT INTO users (name) VALUES ('x')");
        assertRejected("UPDATE users SET name = 'x'");
        assertRejected("TRUNCATE TABLE users");
        assertRejected("SELECT 1; DROP TABLE users");
    }

    @Test
    void blocksEveryKeywordInBlacklist() {
        Map<String, String> samples = new LinkedHashMap<>();
        samples.put("DROP", "DROP TABLE users");
        samples.put("TRUNCATE", "TRUNCATE TABLE users");
        samples.put("ALTER", "ALTER TABLE users ADD COLUMN age INT");
        samples.put("CREATE", "CREATE TABLE t (id INT)");
        samples.put("INSERT", "INSERT INTO users (name) VALUES ('x')");
        samples.put("UPDATE", "UPDATE users SET name = 'x'");
        samples.put("DELETE", "DELETE FROM users");
        samples.put("REPLACE", "REPLACE INTO users (id, name) VALUES (1, 'x')");
        samples.put("MERGE", "MERGE INTO users u USING src s ON u.id = s.id "
                + "WHEN MATCHED THEN UPDATE SET u.name = s.name");
        samples.put("GRANT", "GRANT SELECT ON db1.* TO 'u'@'%'");
        samples.put("REVOKE", "REVOKE SELECT ON db1.* FROM 'u'@'%'");
        samples.put("EXEC", "EXEC sp_who");
        samples.put("EXECUTE", "EXECUTE sp_who");
        samples.put("CALL", "CALL refresh_stats()");
        samples.put("SHUTDOWN", "SHUTDOWN");
        // SELECT 里调用 LOAD_FILE：语法上是 SELECT，只能靠黑名单拦住
        samples.put("LOAD_FILE", "SELECT LOAD_FILE('/etc/passwd')");

        samples.forEach((keyword, sql) -> {
            SqlSecurityChecker.ValidationResult r = checker.validate(sql);
            assertFalse(r.valid(), "应拦截 " + keyword + ": " + sql);
            assertTrue(r.errorMessage().contains("包含禁止的SQL关键字"),
                    "应由关键词黑名单拦截 " + keyword + " → " + r.errorMessage());
        });
    }

    // ---------- ② AST 校验：仅 SELECT 放行 ----------

    @Test
    void rejectsNonSelectStatementsAtAstLayer() {
        // 以下语句都不含黑名单关键词，只能靠 AST 类型校验拦住
        assertRejectedAtAstLayer("EXPLAIN SELECT 1");
        assertRejectedAtAstLayer("SHOW TABLES");
        assertRejectedAtAstLayer("DESC users");
        assertRejectedAtAstLayer("SET @x = 1");
    }

    @Test
    void allowsCteAndSubquerySelects() {
        assertValid("WITH t AS (SELECT 1 AS a) SELECT a FROM t");
        assertValid("SELECT id FROM users WHERE user_id IN (SELECT id FROM vip_users LIMIT 10)");
        // JSqlParser 将 VALUES 建模为 Select 子类（纯只读行构造、无表访问），
        // 与旧实现一致放行，此处显式锁定该语义
        assertValid("VALUES (1, 2)");
    }

    @Test
    void rejectsBlankAndMalformedSql() {
        SqlSecurityChecker.ValidationResult blank = checker.validate(null);
        assertFalse(blank.valid());
        assertTrue(blank.errorMessage().contains("SQL 不能为空"));
        assertFalse(checker.validate("").valid());
        assertFalse(checker.validate("   ").valid());

        SqlSecurityChecker.ValidationResult malformed = checker.validate("SELECT id FROM users WHERE x =");
        assertFalse(malformed.valid(), "语法残缺的 SQL 应拦截");
        assertTrue(malformed.errorMessage().contains("SQL 语法错误"), "应由解析层拦截 → " + malformed.errorMessage());
    }

    // ---------- ③ 堆叠查询 ----------

    @Test
    void blocksStackedQueries() {
        assertRejected("SELECT 1; DROP TABLE users");
        assertRejected("SELECT id FROM users; SELECT id FROM admin");
        assertRejected("SELECT 1;;");
        assertRejected("SELECT 1; -- DROP TABLE users");
        // 单个结尾分号属于合法单语句，不应误判为堆叠
        assertValid("SELECT 1;");
        assertValid("SELECT 1 ;");
    }

    // ---------- ④ UNION 注入 ----------

    @Test
    void blocksUnionInjectionPatterns() {
        assertUnionBlocked("SELECT id FROM users UNION SELECT password FROM admin");
        assertUnionBlocked("select id from users union select password from admin");
        assertUnionBlocked("SELECT id FROM users UNION ALL SELECT password FROM admin");
        // 第二个 SELECT 带括号：JSqlParser 仍解析为 Select，必须由 UNION 模式拦住
        assertUnionBlocked("SELECT id FROM users UNION (SELECT password FROM admin)");
        assertUnionBlocked("SELECT id FROM users UNION ALL (SELECT password FROM admin)");
        // 注释拆开 UNION 与 SELECT：剥离注释后还原为完整模式
        assertUnionBlocked("SELECT id FROM users UNION/**/SELECT password FROM admin");
        // MySQL 的 UNION DISTINCT 写法
        assertUnionBlocked("SELECT 1 UNION DISTINCT SELECT 2");
    }

    @Test
    void allowsUnionWordInsideLiteralAndComment() {
        assertValid("SELECT id FROM users WHERE note = 'UNION SELECT password FROM admin'");
        assertValid("SELECT 1 /* UNION SELECT password FROM admin */");
        assertValid("SELECT id FROM users -- UNION SELECT password FROM admin");
    }

    // ---------- ⑤ 注释绕过 ----------

    @Test
    void blocksCommentDisguisedStatements() {
        // 违禁语句藏在块注释里但留下了分号：堆叠检测命中
        SqlSecurityChecker.ValidationResult stacked =
                checker.validate("SELECT 1 /* ; DROP TABLE users */");
        assertFalse(stacked.valid(), "注释里藏分号应拦截");
        assertTrue(stacked.errorMessage().contains("堆叠"), "应由堆叠检测拦截 → " + stacked.errorMessage());

        // 关键词被注释拆开：剥离注释后整词命中黑名单
        SqlSecurityChecker.ValidationResult split =
                checker.validate("DROP/**/TABLE users");
        assertFalse(split.valid(), "注释拆开 DROP 应拦截");
        assertTrue(split.errorMessage().contains("包含禁止的SQL关键字"), "应由黑名单拦截 → " + split.errorMessage());

        // 注释拆开 UNION 与 SELECT 的两种写法
        assertRejected("SELECT id FROM users UNION/**/SELECT password FROM admin");
        assertRejected("SELECT id FROM users UN/**/ION SELECT password FROM admin");

        // 行注释伪装成尾部违禁语句：分号后非结尾，堆叠检测命中
        assertRejected("SELECT id FROM users; -- DROP TABLE users");
    }

    @Test
    void allowsInertComments() {
        // 注释内容是惰性的，不应误伤
        assertValid("SELECT /* DROP TABLE users */ id FROM users");
        assertValid("SELECT id FROM users WHERE note = 'safe' -- DROP TABLE users");
    }

    // ---------- 迁移保留：注入与非法输入 ----------

    @Test
    void stillBlocksInjectionPatterns() {
        SqlSecurityChecker.ValidationResult union =
                checker.validate("SELECT id FROM users UNION SELECT password FROM admin");
        assertFalse(union.valid());
        assertFalse(checker.validate("   ").valid());
        assertFalse(checker.validate("SELECT id FROM users WHERE x = ").valid());
    }

    // ---------- ⑦ enforceLimit 行为 ----------

    @Test
    void enforceLimitAppendsOnlyWhenReallyAbsent() {
        assertTrue(checker.enforceLimit("SELECT id FROM users", 100).endsWith("LIMIT 100"));
        assertTrue(checker.enforceLimit("SELECT id FROM users LIMIT 5", 100).endsWith("LIMIT 5"));
        // limit_count 这类列名不算已存在 LIMIT —— 子串匹配会漏加限制，退化成全表扫描
        assertTrue(checker.enforceLimit("SELECT limit_count FROM stats", 100).endsWith("LIMIT 100"));

        // 结尾分号先剥掉再补 LIMIT
        String fromSemicolon = checker.enforceLimit("SELECT id FROM users;", 100);
        assertTrue(fromSemicolon.endsWith("LIMIT 100"));
        assertFalse(fromSemicolon.contains(";"), "补完 LIMIT 后不应残留分号: " + fromSemicolon);

        // 字面量里的 LIMIT 不算已有限制
        assertTrue(checker.enforceLimit("SELECT id FROM users WHERE note = 'LIMIT 5'", 100)
                .endsWith("LIMIT 100"));

        // 小写 limit 同样能识别为已有限制
        assertTrue(checker.enforceLimit("SELECT id FROM users limit 5", 50).endsWith("limit 5"));
    }

    // ---------- 断言辅助 ----------

    private void assertValid(String sql) {
        SqlSecurityChecker.ValidationResult r = checker.validate(sql);
        assertTrue(r.valid(), "应放行: " + sql + " → " + r.errorMessage());
    }

    private void assertRejected(String sql) {
        assertFalse(checker.validate(sql).valid(), "应拦截: " + sql);
    }

    private void assertRejectedAtAstLayer(String sql) {
        SqlSecurityChecker.ValidationResult r = checker.validate(sql);
        assertFalse(r.valid(), "非 SELECT 语句应拦截: " + sql);
        assertTrue(r.errorMessage().contains("只允许 SELECT"),
                "应由 AST 层拦截: " + sql + " → " + r.errorMessage());
    }

    private void assertUnionBlocked(String sql) {
        SqlSecurityChecker.ValidationResult r = checker.validate(sql);
        assertFalse(r.valid(), "应拦截 UNION 注入: " + sql);
        assertTrue(r.errorMessage().contains("UNION"), "应由 UNION 模式拦截: " + sql + " → " + r.errorMessage());
    }
}
