package com.nl2sql.agent;

import com.nl2sql.config.AgentConfig;
import com.nl2sql.config.ModelProperties;
import com.nl2sql.mapper.DataSourceMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 云端 DashScope 通道冒烟：装配云端 ReActAgent（与默认 Bean 同一 sysPrompt / Toolkit /
 * maxIters，仅换模型，经 {@link AgentConfig#buildAgent}）跑 1 个简单问题。
 *
 * <p>key 只从环境变量（经 {@code nl2sql.model.dashscope-api-key} 属性）读取，不硬编码；
 * {@code DASHSCOPE_API_KEY} 缺失时 {@link Assumptions#assumeTrue} 跳过并打印
 * "待 DASHSCOPE_API_KEY"，不阻塞构建。云端模型 Bean 为 {@code @Lazy}，
 * 注入 {@link ObjectProvider} 本身不触发创建——key 存在时用例内再取实例。
 */
@SpringBootTest
class AgentCloudSmokeTest {

    @Autowired
    private ModelProperties modelProperties;

    @Autowired
    private AgentConfig agentConfig;

    @Autowired
    private Toolkit nl2sqlToolkit;

    @Autowired
    private ObjectProvider<DashScopeChatModel> cloudModelProvider;

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void cloudAgentAnswersSimpleCountQuestion() {
        boolean keyPresent = modelProperties.getDashscopeApiKey() != null
                && !modelProperties.getDashscopeApiKey().isBlank();
        if (!keyPresent) {
            System.out.println("[T5-cloud] 待 DASHSCOPE_API_KEY：云端冒烟用例已就位，本机缺 key，跳过执行");
        }
        Assumptions.assumeTrue(keyPresent, "DASHSCOPE_API_KEY 缺失，跳过云端冒烟（用例已就位，待 key）");
        AgentE2ESupport.assumeTargetDatabaseUp();

        ReActAgent cloudAgent = agentConfig.buildAgent(cloudModelProvider.getObject(), nl2sqlToolkit);
        Long datasourceId = AgentE2ESupport.nlp2sqlTestDatasourceId(dataSourceMapper);

        AgentE2ESupport.RunTrace trace = AgentE2ESupport.ask(cloudAgent,
                "数据源ID为 " + datasourceId + "。users 表中共有多少位用户？");

        assertFalse(trace.answer().isBlank(), "云端最终回答不应为空");
        assertTrue(trace.executedSql(), "应至少调用一次 execute_sql，实际调用序列: " + trace.toolCalls());
        assertTrue(trace.answer().contains("200"),
                "回答应包含真实数据痕迹（用户总数 200），实际回答: " + AgentE2ESupport.truncate(trace.answer(), 200));

        System.out.println("[T5-cloud] 云端冒烟通过: model=" + modelProperties.getCloudModel()
                + " 工具序列=" + trace.toolCalls() + " 耗时=" + trace.costMs() + "ms");
    }
}
