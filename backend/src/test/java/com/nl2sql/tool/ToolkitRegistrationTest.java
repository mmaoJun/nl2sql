package com.nl2sql.tool;

import com.nl2sql.security.SqlSecurityChecker;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Toolkit.registerTool} 注册效果验证：工具名、JSON Schema 参数与读写/并发标志。
 *
 * <p>纯 JVM 单测，不拉起 Spring 上下文：注册走反射扫描注解，不触达数据库，
 * 因此构造 {@link NL2SQLTools} 时只有校验器传真实例，三个需要数据源的 collaborator
 * 传 null —— 若注册/反射阶段误触达它们会直接 NPE，等于顺手验证了"注册不依赖库连接"。
 * 另把 validate_sql 通过 Toolkit 完整调一次（校验是纯函数、无需数据库），
 * 验证按参数名绑定入参这条反射链路真的通。
 */
class ToolkitRegistrationTest {

    private static final Set<String> EXPECTED_TOOLS =
            Set.of("list_tables", "get_schema", "sample_data", "validate_sql", "execute_sql");

    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        toolkit.registerTool(new NL2SQLTools(null, null, null, new SqlSecurityChecker(), 100));
    }

    @Test
    void registerToolExposesExactlyFiveTools() {
        Set<String> names = toolkit.getToolNames();

        assertEquals(EXPECTED_TOOLS, names, "注册后工具名应恰为 5 个");
        assertEquals(5, names.size(), "工具数量应为 5");

        System.out.println("[T4] toolkit.getToolNames()=" + names);
    }

    @Test
    void toolSchemasCarryDescriptionsAndNamedParameters() {
        List<ToolSchema> schemas = toolkit.getToolSchemas();

        assertEquals(5, schemas.size(), "应返回 5 个工具 schema");
        for (ToolSchema schema : schemas) {
            assertNotNull(schema.getDescription(), schema.getName() + " 应有描述");
            assertFalse(schema.getDescription().isBlank(), schema.getName() + " 描述不应为空");
            Map<String, Object> parameters = schema.getParameters();
            assertNotNull(parameters, schema.getName() + " 应有参数 schema");
            assertEquals("object", String.valueOf(parameters.get("type")), "参数 schema 应为 object");
            Object properties = parameters.get("properties");
            assertTrue(properties instanceof Map, "参数 schema 应含 properties，实际: " + parameters);
            assertTrue(((Map<?, ?>) properties).containsKey("datasource_id"),
                    schema.getName() + " 的参数应含 datasource_id，实际: " + properties);
            System.out.println("[T4] schema " + schema.getName() + " required=" + parameters.get("required")
                    + " properties=" + ((Map<?, ?>) properties).keySet()
                    + " description=" + brief(schema.getDescription()));
        }

        ToolSchema sampleSchema = schemaOf(schemas, "sample_data");
        Map<?, ?> sampleProps = (Map<?, ?>) sampleSchema.getParameters().get("properties");
        assertTrue(sampleProps.containsKey("table_name"), "sample_data 应含 table_name");
        assertTrue(sampleProps.containsKey("limit"), "sample_data 应含可选的 limit");

        ToolSchema schemaTool = schemaOf(schemas, "get_schema");
        assertTrue(((Map<?, ?>) schemaTool.getParameters().get("properties")).containsKey("table_name"),
                "get_schema 应含 table_name");
    }

    @Test
    void toolFlagsAreReadOnlyAndConcurrencyAsDesigned() {
        for (String name : EXPECTED_TOOLS) {
            AgentTool tool = toolkit.getTool(name);
            assertNotNull(tool, "工具应可取出: " + name);
            assertTrue(tool.isReadOnly(), name + " 应标注 readOnly = true");
        }

        // 并发标志：元数据类与纯校验并行安全；execute_sql 串行执行模型现编 SQL
        assertTrue(((ToolBase) toolkit.getTool("list_tables")).isConcurrencySafe(), "list_tables 应并发安全");
        assertTrue(((ToolBase) toolkit.getTool("get_schema")).isConcurrencySafe(), "get_schema 应并发安全");
        assertTrue(((ToolBase) toolkit.getTool("sample_data")).isConcurrencySafe(), "sample_data 应并发安全");
        assertTrue(((ToolBase) toolkit.getTool("validate_sql")).isConcurrencySafe(), "validate_sql 应并发安全");
        assertFalse(((ToolBase) toolkit.getTool("execute_sql")).isConcurrencySafe(),
                "execute_sql 应串行，避免多条重查询同时压向目标库");

        System.out.println("[T4] flags readOnly=true for all 5; concurrencySafe: list_tables/get_schema/sample_data/"
                + "validate_sql=true, execute_sql=false");
    }

    @Test
    void validateSqlInvocationThroughToolkitBindParameters() {
        AgentTool tool = toolkit.getTool("validate_sql");

        ToolCallParam okParam = ToolCallParam.builder()
                .input(Map.of("datasource_id", 1L, "sql", "SELECT id, name FROM users"))
                .build();
        ToolResultBlock okResult = tool.callAsync(okParam).block();

        assertNotNull(okResult, "Toolkit 调用应返回结果块");
        String okText = concatOutput(okResult);
        assertTrue(okText.contains("valid"), "结果应携带 valid 字段: " + okText);
        assertTrue(okText.contains("true"), "合法 SELECT 应判定为通过: " + okText);

        ToolCallParam badParam = ToolCallParam.builder()
                .input(Map.of("datasource_id", 1L, "sql", "DELETE FROM users WHERE id = 1"))
                .build();
        ToolResultBlock badResult = tool.callAsync(badParam).block();

        assertNotNull(badResult, "Toolkit 调用应返回结果块");
        String badText = concatOutput(badResult);
        assertTrue(badText.contains("禁"), "拒绝原因应回传给模型: " + badText);

        // 直接调 AgentTool 时状态停在 RUNNING，由上层 Agent 运行时收尾，这里只断言内容
        System.out.println("[T4] toolkit.call validate_sql -> ok=" + okText + "; rejected=" + badText);
    }

    private static ToolSchema schemaOf(List<ToolSchema> schemas, String name) {
        return schemas.stream().filter(s -> name.equals(s.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少工具 schema: " + name));
    }

    /** 拼接结果块的文本内容，用于断言回传给模型的内容。 */
    private static String concatOutput(ToolResultBlock block) {
        return block.getOutput() == null ? "" : block.getOutput().toString();
    }

    private static String brief(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }
}
