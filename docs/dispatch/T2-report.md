# T2 报告 — SQL 安全校验迁移（SqlSecurityChecker + 测试补强）

> 执行依据：`docs/dispatch/T2-brief.md`。旧仓库 `nlp2sql` 仅只读参考，未做任何改动。

## 1. 完成摘要

- 将 `SqlSecurityChecker` 从 `com.nlp2sql.security` 迁移到 `com.nl2sql.security`，公开 API（`validate(String)` → `ValidationResult(boolean valid, String errorMessage)`、`enforceLimit(String, int)`）与旧实现一致，依赖保持 JSqlParser 4.9 + Lombok + Spring `@Component`。
- 对照课题安全要求逐项核查时，经 JSqlParser 4.9 实测确认旧实现存在 2 处真实漏判 + 1 处字面量误伤，按 brief 第 3 条做最小修补（详见 §4），其余逻辑逐行保持原样（仅包名与注释更新）。
- 测试：旧 5 个用例全部迁移保留，新增 9 个，共 **14 个用例**；两个验证命令均退出码 0。
- 提交：`10beac3`（实现迁移+修补）、`b97e2df`（测试迁移+补强），仅 add 本任务两个文件。

## 2. 文件清单

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `backend/src/main/java/com/nl2sql/security/SqlSecurityChecker.java` | 186 | 迁移主实现（旧 171 行），三层防御 L1 黑名单 / L2 AST 仅 SELECT / L3 堆叠与 UNION 检测 + `enforceLimit` |
| `backend/src/test/java/com/nl2sql/security/SqlSecurityCheckerTest.java` | 243 | 迁移旧测试（70 行）并补强，14 个用例 |

## 3. 测试方法数与被测项

被测对象：`SqlSecurityChecker.validate(String)`、`SqlSecurityChecker.enforceLimit(String, int)`、`ValidationResult.ok()/fail()` 及其错误消息文案。共 14 个测试方法：

| # | 用例名 | 被测项 | 来源 |
| --- | --- | --- | --- |
| 1 | `allowsColumnsWhoseNameContainsForbiddenKeyword` | L1 防误杀（created_at/updated_at/insert_id） | 迁移 |
| 2 | `allowsForbiddenKeywordInsideStringLiteralAndQuotedIdentifier` | L1 防误杀（单引号字面量、反引号/双引号标识符） | 迁移+补双引号 |
| 3 | `blocksRealWriteAndDdlStatements` | L1 拦截 DELETE/DROP/CREATE/INSERT/UPDATE/TRUNCATE 及带分号注入 | 迁移 |
| 4 | `blocksEveryKeywordInBlacklist` | L1 黑名单全部 16 个关键词逐一断言 + 错误消息文案 | 新增 |
| 5 | `rejectsNonSelectStatementsAtAstLayer` | L2：EXPLAIN / SHOW TABLES / DESC / SET 无黑名单词，必须由 AST 层拦截 | 新增 |
| 6 | `allowsCteAndSubquerySelects` | L2 正向：CTE、IN 子查询；显式锁定 `VALUES` 为 Select 子类的既有语义 | 新增 |
| 7 | `rejectsBlankAndMalformedSql` | 空/null 输入与语法残缺 SQL 的拒绝及消息 | 新增 |
| 8 | `blocksStackedQueries` | L3 堆叠：多语句、双分号、行注释伪装；单个结尾分号不误判 | 新增 |
| 9 | `blocksUnionInjectionPatterns` | L3 UNION：大小写、UNION ALL、括号包裹、注释拆开、UNION DISTINCT | 新增 |
| 10 | `allowsUnionWordInsideLiteralAndComment` | UNION 字面量/注释误伤修复验证 | 新增 |
| 11 | `blocksCommentDisguisedStatements` | 注释绕过：藏分号、拆 DROP、拆 UNION 两写法、行注释伪装 | 新增 |
| 12 | `allowsInertComments` | 惰性注释不误伤（块注释包裹、行注释尾随） | 新增 |
| 13 | `stillBlocksInjectionPatterns` | 迁移保留：UNION 基础例、空白串、语法残缺 | 迁移 |
| 14 | `enforceLimitAppendsOnlyWhenReallyAbsent` | ⑦：仅无 LIMIT 时追加；分号清理、字面量伪 LIMIT、小写 limit | 迁移+补强 |

## 4. 覆盖对照表（课题安全要求 → 用例 → 结果）

课题原文（`实习生课题(1).txt` §"安全要求"）：仅允许 SELECT/GET 查询（只读）、SQL 注入防护。brief 细化为 ①–⑦，逐项结果如下（结果均由 §5 实跑得出）：

| 要求 | 对应用例 | 结果 |
| --- | --- | --- |
| ① 关键词黑名单（DROP/DELETE/INSERT/UPDATE/TRUNCATE 等） | `blocksRealWriteAndDdlStatements`、`blocksEveryKeywordInBlacklist`（16 关键词全覆盖）、`blocksCommentDisguisedStatements`（`DROP/**/TABLE`） | 通过 |
| ② JSqlParser AST 校验（仅 SELECT 放行） | `rejectsNonSelectStatementsAtAstLayer`、`allowsCteAndSubquerySelects`、`rejectsBlankAndMalformedSql` | 通过 |
| ③ 堆叠查询（`SELECT 1; DROP TABLE x`） | `blocksStackedQueries`、`blocksCommentDisguisedStatements`（注释藏分号） | 通过 |
| ④ UNION 注入 | `blocksUnionInjectionPatterns`（断言错误消息含 UNION，证明由 UNION 模式拦截而非其它层巧合）、`stillBlocksInjectionPatterns`、`allowsUnionWordInsideLiteralAndComment` | 通过 |
| ⑤ 注释绕过（`--` / `/* */` 包裹违禁语句） | `blocksCommentDisguisedStatements`（4 种形态）、`allowsInertComments`（反向不误伤） | 通过 |
| ⑥ 字面量 / 引号标识符误伤 | `allowsColumnsWhoseNameContainsForbiddenKeyword`、`allowsForbiddenKeywordInsideStringLiteralAndQuotedIdentifier`、`allowsUnionWordInsideLiteralAndComment`、`allowsInertComments` | 通过 |
| ⑦ `enforceLimit` 行为（仅确实没有 LIMIT 时追加） | `enforceLimitAppendsOnlyWhenReallyAbsent` | 通过 |

## 5. 补强项说明（改动理由）

先用 JShell + JSqlParser 4.9 实测了候选语句的真实解析结果（脚本在系统临时目录，未进仓库），据此确认旧实现的缺口后再做最小修补；**每处修补都有对应用例锁定**。

### 5.1 实现修补（2 处，均为漏判；另附带 1 处误伤修复）

1. **UNION 正则未覆盖括号包裹与 DISTINCT 写法（漏判）**
   - 实测：`SELECT id FROM users UNION (SELECT password FROM admin)`、`... UNION ALL (SELECT ...)`、`SELECT 1 UNION DISTINCT SELECT 2` 均被 JSqlParser 解析为 `SetOperationList`（`Select` 子类）→ L2 放行；旧正则 `\bUNION\b\s+(ALL\s+)?\bSELECT\b` 因 `UNION` 与 `SELECT` 之间是 `(`（或 `DISTINCT`）而不命中 → 三层全过、放行。属于真实可执行的 UNION 注入绕过。
   - 修补：正则改为 `\bUNION\b\s*(?:ALL|DISTINCT)?\s*\(*\s*\bSELECT\b`（CASE_INSENSITIVE 不变）。
   - 锁定用例：`blocksUnionInjectionPatterns` 中括号两种写法 + DISTINCT 写法。
2. **注释可拆开 UNION 与 SELECT（漏判，⑤×④ 交叉形态）**
   - 实测：`SELECT id FROM users UNION/**/SELECT password FROM admin` 被解析为 `SetOperationList` → L2 放行；旧实现 UNION 检测跑在原文上，`UNION/**/SELECT` 不命中 → 放行。
   - 修补：UNION 检测改在 `stripLiteralsAndQuotedIdentifiers(trimmed)`（即 L1 同款"剥字面量+注释"文本）上进行，剥掉注释后还原为完整 `UNION SELECT` 被拦。
   - 附带修复（误伤）：同一处理使 `WHERE note = 'UNION SELECT ...'` 这类字面量内的 UNION 关键词不再被误拦——旧实现会拦；这与类注释声明的"剥字面量避免误杀"设计意图一致。
   - 锁定用例：`blocksCommentDisguisedStatements`（`UNION/**/SELECT`、`UN/**/ION`）、`allowsUnionWordInsideLiteralAndComment`。
   - 说明：`UN/**/ION` 拆分关键词形态实测本身会被 JSqlParser 解析失败拦下（语法错误层），作为防御纵深用例保留。
3. **未改动项（刻意保留，避免扩大范围）**：L1 剥离逻辑、L2 `instanceof Select`、STACKED 正则（含结尾分号豁免语义）、`enforceLimit`、全部错误消息文案。

### 5.2 测试补强（新增 9 个方法）

- **①** 黑名单 16 个关键词逐一构造语句断言（`blockEveryKeywordInBlacklist`），并断言消息前缀 `包含禁止的SQL关键字`，把"报错优先级/文案"也纳入契约；含 `SELECT LOAD_FILE(...)` 这类语法上是 SELECT、只能靠黑名单拦的场景。
- **②** 选实测可解析的非 Select 语句（`EXPLAIN SELECT 1` / `SHOW TABLES` / `DESC users` / `SET @x = 1`）且都不含黑名单关键词，确保考察的是 AST 层本身；另补 CTE / IN 子查询正向例。
- **③** 堆叠查询补多语句、双分号、行注释伪装三类变体；同时显式断言单个结尾分号（`SELECT 1;`）放行，锁定 `(?!\s*$)` 的既有语义防回退。
- **④** UNION 变体 7 条（见 §5.1），每条断言错误消息含 `UNION`，防止"被其它层顺带拦住"的假阳性覆盖。
- **⑤** 注释绕过正反两向共 6 条：正向拦（藏分号 / 拆 DROP / 拆 UNION×2 / 行注释伪装），反向放（块注释包裹、行注释尾随的惰性内容）。
- **⑦** `enforceLimit` 补 3 例：结尾分号先剥后追加且不残留分号、字面量内 LIMIT 不算已限制、小写 `limit` 识别。
- 旧 5 个用例逐条迁移保留（含断言语句与注释），加 1 条双引号标识符防误杀。

## 6. 验证（实际执行的真实输出）

环境：Windows 11 + JDK 21.0.4 + Maven 3.9.11；`-q` 模式成功时不打印 summary，故贴命令退出码与 surefire 报告原文（`backend/target/surefire-reports/`）。

```text
$ cd F:/FANG_ZHENG/nl2sql/backend && mvn -q test -Dtest=SqlSecurityCheckerTest
MVN_EXIT=0

（surefire 报告 com.nl2sql.security.SqlSecurityCheckerTest.txt）
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.266 s -- in com.nl2sql.security.SqlSecurityCheckerTest
```

```text
$ cd F:/FANG_ZHENG/nl2sql/backend && mvn -q clean package
MVN_EXIT=0
-rw-r--r-- 1 lsz25 197609 76276980 Oct 12 09:48 target/nl2sql-backend-1.0.0-SNAPSHOT.jar

（package 阶段复跑整套测试后的 surefire 报告）
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.295 s -- in com.nl2sql.security.SqlSecurityCheckerTest
```

（过程性记录：首跑 14 用例中 1 失败——我把 `VALUES (1,2)` 误设为"应拦截"；实测发现 JSqlParser 将 `Values` 建模为 `Select` 子类，属旧实现既有语义，遂改为在用例中显式锁定放行语义，详见 §7。）

## 7. 提交

| hash | 提交 | 内容 |
| --- | --- | --- |
| `10beac3` | `feat(security): 迁移 SqlSecurityChecker 并修补 UNION 检测的两类漏判` | 主实现 186 行（git add 单文件） |
| `b97e2df` | `test(security): 迁移并补强 SqlSecurityCheckerTest 覆盖七类安全要求` | 测试 243 行（git add 单文件） |

未使用 `git add -A` / `git add .`；提交后 `git status` 仅剩任务前已存在的未跟踪项（`docs/`、`实习生课题(1).txt`），未触碰。

## 8. 偏差与遗留问题

1. **`VALUES (1,2)` 放行（刻意保留）**：JSqlParser 中 `Values extends Select`，`instanceof Select` 成立即放行。它是纯只读行构造（无表访问、无注入面），且为旧实现既有行为，按"行为一致"保留并在用例 6 中显式锁定，防止未来误"修复"。
2. **`COMMENT_INJECTION` 常量未使用**：旧实现即定义了该 Pattern 但全类无引用；按"仅迁移不重构"原样保留（javac 无告警）。建议后续清理或落实用途。
3. **`-q` 命令无 summary 行**：brief 指定的命令带 `-q`，成功时 Maven 不输出 INFO 级 summary；报告以退出码 + surefire 报告文件原文为证（均为真实输出）。
4. **启发式拦截的固有边界（未扩大范围，仅记录）**：
   - 合法 `SELECT ... UNION SELECT ...` 查询同样被拦（旧实现保守策略，未改变）；
   - `SELECT ... FOR UPDATE`、`SELECT SLEEP(10)`、`SELECT BENCHMARK(...)` 实测可过三层（只读不写库，但有锁/耗时风险），课题要求未覆盖，未处理；
   - `SELECT ... INTO OUTFILE`、`LOAD DATA INFILE` 实测被 JSqlParser 解析失败拦下（语法错误层），未额外加黑名单。
5. **`enforceLimit(null)` 会 NPE**（旧行为），调用方（T4 `validate_sql`）需保证非空入参。
6. **git 提示 LF→CRLF**：本机 `core.autocrlf` 环境所致，仅换行符提示，无内容影响。
7. **本报告文件未纳入 git**：按 brief 约束仅 `git add backend/` 下本任务路径。
