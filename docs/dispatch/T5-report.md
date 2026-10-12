# T5 实施报告 — ReActAgent 装配与端到端验证（W2 检查点）

- 状态：**完成**。原派发子代理触达 150 回合上限被中断（在本地 7B 工具协议调试中发散）；由主线接管完成：审计草稿 → 复现并定位偶发失败根因 → 修复 → 验证 → 提交。
- 提交：`b3d3a66`（文本化适配器）→ `3ce67be`（装配与双轨模型）→ `0182229`（端到端 + 云端冒烟用例）
- 环境：本机 MySQL（root/050407）✓；Ollama `qwen2.5-coder:7b` / RTX 3060 6G ✓；Redis 未运行；`DASHSCOPE_API_KEY` 未配置

## 1. 交付物

| 文件 | 说明 |
|---|---|
| `config/AgentConfig.java` | Toolkit Bean（注册 5 只读工具）+ ReActAgent Bean（`NL2SQLAgent`，`maxIters=5`）+ 双轨模型 Bean + 共用装配入口 `buildAgent` |
| `config/ModelProperties.java` | `nl2sql.model.*` 绑定（apiKey / cloud-model / ollama-base-url / local-model / local-temperature / provider） |
| `config/TextualToolCallFormatter.java` | 本地通道协议适配器（§3） |
| `resources/prompts/agent-local-v1.md` · `agent-cloud-v1.md` | 通道化 sysPrompt：本地=文本化工具协议版 / 云端=原生 function calling 版（见 §8） |
| `test/.../agent/AgentEndToEndTest.java` · `AgentE2ESupport.java` · `AgentCloudSmokeTest.java` | 端到端轨迹用例 + 云端冒烟（缺 key 自动跳过） |
| `application.yml`（增补） | `nl2sql.model` 段：缺 key 不阻塞启动 |

## 2. 验证（真实输出）

全量：`mvn test` → `Tests run: 37, Failures: 0, Errors: 0, Skipped: 1`（云端冒烟缺 key 按设计跳过），BUILD SUCCESS。

用例 1（简单查询，本地 7B，**5036ms**）实测轨迹：

```
工具调用序列: list_tables -> get_schema -> execute_sql
execute_sql: SELECT name, age FROM users ORDER BY age DESC LIMIT 5
返回: 朱谢/熊田/姜汤/潘宋/陈钟（均 39 岁）
最终回答: "结论：年龄最大的 5 位用户分别是朱谢、熊田、姜汤、潘宋和陈钟，他们的年龄都是 39 岁。"
```

用例 2（分组聚合）：`list_tables -> get_schema(orders) -> execute_sql(SELECT status, COUNT(*) AS count FROM orders GROUP BY status ORDER BY count DESC LIMIT 10)` → 正确作答。

## 3. 根因与修复（本任务核心技术内容）

**现象**：聚合用例偶发失败——最终回答为空、循环提前结束。复现统计：**修复前 9 轮仅 2 轮成功**。

**根因（逐层证据）**：失败轮次中，第三个模型请求由 Ollama 以 HTTP 200 + `finish_reason=stop` + **空 content** 返回（rawContent 为空串、无 tool_calls，见 formatter 的"解析为空"告警与原始报文日志）。机制：qwen2.5-coder 在被要求"以文本 JSON 输出工具调用"的同时，历史里却是"结构化 tool_calls + tool 角色回执"的混合协议，模型偶发直接输出 EOS，ReAct 循环把空回复当最终答案而结束。

**修复（`TextualToolCallFormatter` 出站侧）**：把发给模型的历史统一为纯文本协议——assistant 的结构化 `tool_calls` 回写为模型自己会输出的 JSON 文本；`tool` 角色回执转 `user` 角色（并保留中文续链指令包装）。

**结果**：修复后**连续 8 轮全部成功**（4 轮单测 + 全量套件 2 次 + 复跑 2 次），无回归。此前子代理完成的响应侧（整段/尾部工具调用 JSON 还原为 `ToolUseBlock`，工具名白名单）与本地 `stream=false` 设置经复核保留。

## 4. 装配决策

- **本地默认装配**：无 key 环境也能跑通检查点；云端 `@Lazy` 创建，`nl2sql.model.provider=cloud` 且 key 缺失时 WARN 回退本地——宁可降级不启动失败。
- **采样温度 0.1**（本地）：默认 0.8 下工具调用格式不稳定（子代理实测结论）。
- **唯一装配入口 `buildAgent`**：默认 Bean 与云端冒烟共用同一 sysPrompt / Toolkit / maxIters，保证双通道装配一致。
- 工具结果回灌采用"中文叙述 + 续链指令"包装（仅改发给模型的副本，不污染 AgentScope 记忆）。

## 5. sysPrompt v1 要点

只读硬约束（仅 SELECT、拒绝写操作、单语句）；"数据源ID为 N"参数约定；工具调用 JSON 格式（`{"name": ..., "arguments": {...}}`）；三步主路径 list_tables → get_schema → execute_sql（辅助工具按需）；拿到查询结果后直接中文作答、不编造数据。

## 6. 偏差

1. 本地通道 `stream=false`：文本化还原需要完整响应体，本地不走模型流式（云端保持 `stream=true`）。**影响**：W4 前端轨迹可视化对本地通道需在应用层做事件转发（`streamEvents` 不受影响）。
2. 复现/修复过程中为 formatter 增补诊断日志（响应摘要/空响应原始报文，debug 级；"解析为空"保留 warn 级）——对 W4 调试同样有用，保留。

## 7. 遗留（供 W3+）

1. **云端冒烟**：首跑发现"提示词跨通道不适配"问题并已修复（§8）；复跑验证由用户执行，结果待归档。
2. 技术方案书待同步：AgentScope 2.0.1→**2.0.2**、`maxIterations`→**`maxIters`**、本地通道 `stream=false` 与文本化适配器设计（§5.3/§5.4 代码示例）。
3. 真实 7B 行为数据可用于《7B 模型能力评估报告》："协议适配前后"成功率对比（2/9 → 8/8）、单问延迟（本机 5-15s 级）、空回复现象的机制与规避方法。
4. Redis 未运行：会话记忆（W3）启动前需就绪。

## 8. 补充（2026-10-12 云端冒烟首跑：发现并修复）

- **首跑结果**：`Tests run: 1, Failures: 1`——调用序列为空、耗时 3.1s；轨迹显示"最终回答"是文本化的 `{"name": "list_tables", "arguments": {"datasource_id": 5}}`。
- **根因**：此前只有一条 sysPrompt，且是按本地通道（"需要调用工具时只输出一个 JSON 对象"的文本化协议）写的；云端 qwen-plus 走原生 function calling，在该提示词下把 JSON 调用当普通文本回答输出，ReAct 循环把该文本当最终答案结束，工具从未被调用。
- **修复**（提交 `fix(agent): 按通道拆分系统提示词…`）：提示词按通道拆分——`agent-local-v1.md`（文本化协议，本地，内容不变）与 `agent-cloud-v1.md`（原生工具调用版，去掉 JSON 文本协议段）；`buildAgent` 按模型类型自动选择（`DashScopeChatModel` → 云端版）。本地端到端回归 **2/2 通过**。
- **复验**：待用户重跑 `AgentCloudSmokeTest` 后归档结果。
