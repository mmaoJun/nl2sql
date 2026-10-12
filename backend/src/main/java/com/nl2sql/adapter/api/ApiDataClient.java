package com.nl2sql.adapter.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API 数据拉取与「响应 → 行集」转换（REST GET / GraphQL POST）。
 *
 * <p><b>响应 → 行集约定</b>（与 T23 派发说明一致，报告与演示文档写死此契约）：
 * <ul>
 *   <li>顶层 JSON <b>数组</b>为标准形态：每个元素（对象）一行；</li>
 *   <li>顶层 <b>单对象</b>视为一行；</li>
 *   <li>GraphQL 先取 {@code data.<查询的顶层字段>}，其值再按上述两规则展开；</li>
 *   <li>行内<b>嵌套对象/数组</b>值序列化为 JSON 字符串（建表时按 VARCHAR 存，见
 *       {@link JsonTableExecutor}）；</li>
 *   <li>数组元素若是标量，落为单列 {@code value}。</li>
 * </ul>
 *
 * <p>鉴权失败（HTTP 401）抛出的消息包含 "401"，便于上层工具把「检查 extra_config.headers
 * 里的鉴权头」作为可读错误回传给模型/用户。
 */
final class ApiDataClient {

    /** 单次 HTTP 请求超时（秒）。 */
    private static final int REQUEST_TIMEOUT_S = 30;

    /** 提取 GraphQL 查询顶层字段名：首个 { 之后的第一个标识符。 */
    private static final Pattern GRAPHQL_TOP_FIELD = Pattern.compile("\\{\\s*([A-Za-z_][A-Za-z0-9_]*)");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiDataClient() {
    }

    /** REST 取数：GET baseUrl + path（携带 extra_config.headers）。 */
    static List<Map<String, JsonNode>> fetchRest(ApiConfig config, ApiConfig.ApiTable table) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + table.path()))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S));
        config.headers().forEach(builder::header);
        HttpRequest request = builder.GET().build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        ensureSuccess(response, "REST GET " + table.path());
        return toRows(readJson(response), "REST " + table.path());
    }

    /** GraphQL 取数：POST {baseUrl}/graphql，body 为 {"query": ...}，取 data.<顶层字段> 展开行集。 */
    static List<Map<String, JsonNode>> fetchGraphql(ApiConfig config, ApiConfig.ApiTable table) throws Exception {
        String field = topLevelField(table.query());
        byte[] body = MAPPER.writeValueAsBytes(Map.of("query", table.query()));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/graphql"))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S));
        config.headers().forEach(builder::header);
        HttpRequest request = builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        ensureSuccess(response, "GraphQL " + field);

        JsonNode node = readJson(response);
        JsonNode data = node.path("data");
        JsonNode errors = node.path("errors");
        if ((data.isMissingNode() || data.isNull()) && errors.isArray() && !errors.isEmpty()) {
            throw new IllegalStateException("GraphQL 返回错误: " + truncate(errors.toString(), 300));
        }
        if (data.isMissingNode() || data.isNull()) {
            throw new IllegalStateException("GraphQL 响应缺少 data 字段: " + truncate(node.toString(), 300));
        }
        JsonNode value = data.path(field);
        if (value.isMissingNode()) {
            throw new IllegalStateException("GraphQL data 中不存在字段 " + field
                    + "，实际字段: " + fieldNames(data));
        }
        return toRows(value, "GraphQL " + field);
    }

    /** 解析 GraphQL 查询的顶层字段名（本适配器按「首个 { 后第一个标识符」约定）。 */
    static String topLevelField(String query) {
        Matcher matcher = GRAPHQL_TOP_FIELD.matcher(query == null ? "" : query);
        if (!matcher.find()) {
            throw new IllegalArgumentException("无法从 GraphQL 查询中解析顶层字段名: " + query);
        }
        return matcher.group(1);
    }

    /** 响应 → 行集（顶层数组 / 单对象 / 标量，约定见类注释）。 */
    static List<Map<String, JsonNode>> toRows(JsonNode node, String source) {
        List<Map<String, JsonNode>> rows = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode element : node) {
                rows.add(asRow(element));
            }
        } else if (!node.isMissingNode() && !node.isNull()) {
            rows.add(asRow(node));
        }
        return rows;
    }

    private static Map<String, JsonNode> asRow(JsonNode element) {
        Map<String, JsonNode> row = new LinkedHashMap<>();
        if (element.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = element.fields();
            fields.forEachRemaining(entry -> row.put(entry.getKey(), entry.getValue()));
        } else {
            // 标量元素（如 [1, 2, 3]）落为单列 value
            row.put("value", element);
        }
        return row;
    }

    private static void ensureSuccess(HttpResponse<byte[]> response, String scene) {
        int status = response.statusCode();
        if (status == 401) {
            throw new IllegalStateException(scene + " 返回 HTTP 401 未授权：请检查 extra_config.headers 中的鉴权头"
                    + "（如 X-API-KEY）是否正确");
        }
        if (status / 100 != 2) {
            throw new IllegalStateException(scene + " 返回 HTTP " + status + "（"
                    + truncate(new String(response.body(), StandardCharsets.UTF_8), 200) + "）");
        }
    }

    private static JsonNode readJson(HttpResponse<byte[]> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private static String fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return String.join(", ", names);
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
