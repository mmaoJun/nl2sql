package com.nl2sql.agent;

import com.nl2sql.mapper.DataSourceMapper;
import io.agentscope.core.ReActAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W2 检查点：ReActAgent 默认装配（本地 Ollama qwen2.5-coder:7b）的真实端到端验证。
 *
 * <p>对 {@code nlp2sql_test} 提两个问题（简单查询 + 分组聚合），走
 * {@link AgentE2ESupport#ask} 的 streamEvents 轨迹采集，断言：
 * 最终回答非空、至少一次 {@code execute_sql} 调用、回答包含真实数据痕迹
 * （数据固定：年龄最大 5 位用户均为 39 岁；各订单状态恰好 100 条）。
 *
 * <p>每条用例挂 {@link Timeout}（300s）防挂；MySQL / Ollama 任一不可用时
 * assume 跳过，保证无服务环境的 {@code mvn package} 依旧通过。
 */
@SpringBootTest
class AgentEndToEndTest {

    @Autowired
    private ReActAgent nl2sqlAgent;

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void simpleQueryReturnsRealTopAgeUsers() {
        AgentE2ESupport.assumeLocalStackUp();
        Long datasourceId = AgentE2ESupport.nlp2sqlTestDatasourceId(dataSourceMapper);

        AgentE2ESupport.RunTrace trace = AgentE2ESupport.ask(nl2sqlAgent,
                "数据源ID为 " + datasourceId + "。请查询 users 表中年龄最大的 5 位用户，告诉我他们的姓名和年龄。");

        assertFalse(trace.answer().isBlank(), "最终回答不应为空");
        assertTrue(trace.executedSql(), "应至少调用一次 execute_sql，实际调用序列: " + trace.toolCalls());
        assertTrue(trace.answer().contains("39"),
                "回答应包含真实数据痕迹（最大年龄 39），实际回答: " + AgentE2ESupport.truncate(trace.answer(), 200));
        assertTrue(anyNameInAnswer(trace.answer()),
                "回答应包含真实用户姓名（朱谢/熊田/姜汤/潘宋/陈钟 至少其一），实际回答: "
                        + AgentE2ESupport.truncate(trace.answer(), 200));

        System.out.println("[T5-e2e] 用例1(简单查询) 通过: 工具序列=" + trace.toolCalls() + " 耗时=" + trace.costMs() + "ms");
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void aggregationQueryReturnsPerStatusCounts() {
        AgentE2ESupport.assumeLocalStackUp();
        Long datasourceId = AgentE2ESupport.nlp2sqlTestDatasourceId(dataSourceMapper);

        AgentE2ESupport.RunTrace trace = AgentE2ESupport.ask(nl2sqlAgent,
                "数据源ID为 " + datasourceId + "。请按 status 分组统计 orders 表中每种订单状态的数量，按数量降序排列。");

        assertFalse(trace.answer().isBlank(), "最终回答不应为空");
        assertTrue(trace.executedSql(), "应至少调用一次 execute_sql，实际调用序列: " + trace.toolCalls());
        assertTrue(trace.answer().contains("100"),
                "回答应包含真实数据痕迹（每种状态 100 条），实际回答: " + AgentE2ESupport.truncate(trace.answer(), 200));

        System.out.println("[T5-e2e] 用例2(分组聚合) 通过: 工具序列=" + trace.toolCalls() + " 耗时=" + trace.costMs() + "ms");
    }

    /** 数据固定：nlp2sql_test.users 年龄最大的 5 位用户姓名。 */
    private static boolean anyNameInAnswer(String answer) {
        return List.of("朱谢", "熊田", "姜汤", "潘宋", "陈钟").stream().anyMatch(answer::contains);
    }
}
