package com.nl2sql.adapter.api;

import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * API 类数据源（REST / GraphQL）的连接配置与「表模型」解析。
 *
 * <p><b>extra_config JSON 契约</b>（W4 收尾与演示文档以此为准）：
 * <pre>{@code
 * {
 *   "headers": { "X-API-KEY": "demo-key-2026" },              // 鉴权等请求头（可选）
 *   "tables": [                                                // 表声明（必填数组；单表即一个元素）
 *     { "name": "department_budgets", "path": "/departments" } // REST：相对 basePath 的接口路径
 *     { "name": "department_budgets", "query": "{ departmentBudgets { deptId deptName budget } }" }
 *   ]                                                          // GraphQL：查询语句（顶层字段即行集）
 * }
 * }</pre>
 *
 * <p>标准字段映射：{@code host}/{@code port} 为服务地址（port 缺省 3100）；
 * {@code database_name} 可空或作 <b>默认 basePath</b>（如 {@code /api}，空/"/" 视为无前缀）；
 * {@code username}/{@code password} 不参与 API 调用（鉴权走 {@code headers}）。
 * 表名匹配不区分大小写（与 HDFS 适配器一致：去引号、去库名前缀后再比对）。
 */
final class ApiConfig {

    /** 默认端口（mock-api 服务监听端口）。 */
    static final int DEFAULT_PORT = 3100;

    /** 连接探测超时（毫秒）。 */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /** 连接探测共用的 HTTP 客户端（业务请求由 ApiDataClient 发送）。 */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final String host;

    private final int port;

    /** 服务基地址前缀（来自 database_name；空串表示无前缀）。 */
    private final String basePath;

    /** 业务请求统一携带的请求头（鉴权头在这里）。 */
    private final Map<String, String> headers;

    /** 表声明（config 即表清单，不做服务端表发现）。 */
    private final List<ApiTable> tables;

    private ApiConfig(String host, int port, String basePath,
                      Map<String, String> headers, List<ApiTable> tables) {
        this.host = host;
        this.port = port;
        this.basePath = basePath;
        this.headers = headers;
        this.tables = tables;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    String basePath() {
        return basePath;
    }

    Map<String, String> headers() {
        return headers;
    }

    List<ApiTable> tables() {
        return tables;
    }

    /** 服务基地址：{@code http://host:port} + basePath（basePath 为空则无后缀）。 */
    String baseUrl() {
        return "http://" + host + ":" + port + basePath;
    }

    /** 解析适配器配置（DataSource#toAdapterConfig 的产物：标准字段 + extra_config 合并）。 */
    static ApiConfig parse(Map<String, Object> config) {
        String host = config == null ? null : value(config.get("host"));
        if (!StringUtils.hasText(host) || "null".equals(host)) {
            throw new IllegalArgumentException("API 数据源缺少 host 配置");
        }
        Object portValue = config.get("port");
        int port = portValue instanceof Number number ? number.intValue()
                : (portValue == null || String.valueOf(portValue).isBlank()
                        ? DEFAULT_PORT : Integer.parseInt(String.valueOf(portValue)));
        String basePath = normalizeBasePath(value(config.get("databaseName")));
        Map<String, String> headers = parseHeaders(config.get("headers"));
        List<ApiTable> tables = parseTables(config.get("tables"));
        return new ApiConfig(host, port, basePath, headers, tables);
    }

    /** 校验并返回 REST 表声明（每张表必须有 path）。 */
    List<ApiTable> requireRestTables() {
        if (tables.isEmpty()) {
            throw new IllegalArgumentException(
                    "REST 数据源缺少 tables 声明（extra_config 形如 {\"tables\":[{\"name\":\"...\",\"path\":\"/api/...\"}]}）");
        }
        for (ApiTable table : tables) {
            if (!StringUtils.hasText(table.path())) {
                throw new IllegalArgumentException("REST 表 " + table.name() + " 缺少 path 声明");
            }
        }
        return tables;
    }

    /** 校验并返回 GraphQL 表声明（每张表必须有 query）。 */
    List<ApiTable> requireGraphqlTables() {
        if (tables.isEmpty()) {
            throw new IllegalArgumentException(
                    "GraphQL 数据源缺少 tables 声明（extra_config 形如 {\"tables\":[{\"name\":\"...\",\"query\":\"{ field { a b } }\"}]}）");
        }
        for (ApiTable table : tables) {
            if (!StringUtils.hasText(table.query())) {
                throw new IllegalArgumentException("GraphQL 表 " + table.name() + " 缺少 query 声明");
            }
        }
        return tables;
    }

    /**
     * 按表名查找声明（不区分大小写；容忍 {@code db.table} 前缀与引号，与 HDFS 适配器同口径）。
     *
     * @param tableRef SQL 中的表引用
     * @return 命中的表声明；未声明时返回 null
     */
    ApiTable findTable(String tableRef) {
        String target = normalizeName(tableRef);
        for (ApiTable table : tables) {
            if (normalizeName(table.name()).equals(target)) {
                return table;
            }
        }
        return null;
    }

    /**
     * 存活探测：GET {@code http://host:port/health} 返回 200 即视为可达
     * （/health 不要求鉴权、也不受 basePath 影响——它是服务根路径上的探针端点）。
     */
    boolean health() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/health"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    }

    private static Map<String, String> parseHeaders(Object value) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    headers.put(String.valueOf(k), String.valueOf(v));
                }
            });
        }
        return headers;
    }

    private static List<ApiTable> parseTables(Object value) {
        List<ApiTable> tables = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    String name = value(map.get("name"));
                    if (!StringUtils.hasText(name)) {
                        throw new IllegalArgumentException("tables 元素缺少 name 声明");
                    }
                    tables.add(new ApiTable(name, value(map.get("path")), value(map.get("query"))));
                }
            }
        }
        return tables;
    }

    /** 归一化 basePath：空 / "null" / "/" 视为无前缀；否则确保以 / 开头、去掉末尾 /。 */
    private static String normalizeBasePath(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw) || "/".equals(raw.trim())) {
            return "";
        }
        String path = raw.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** 表名归一化：去引号 → 取最后一段（容忍库名前缀）→ 小写。 */
    static String normalizeName(String ref) {
        String name = ref == null ? "" : ref.trim();
        name = name.replace("`", "").replace("\"", "").replace("'", "");
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static String value(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 一张「表」的声明：名称 + 取数方式（REST 用 {@code path}，GraphQL 用 {@code query}）。
     *
     * @param name  表名（SQL 中以该名引用）
     * @param path  REST 接口路径（相对 basePath）
     * @param query GraphQL 查询语句
     */
    record ApiTable(String name, String path, String query) {

        /** 人类可读的取数描述（表注释用）。 */
        String describe() {
            return path != null ? "GET " + path : "GraphQL " + truncate(query);
        }

        private static String truncate(String text) {
            if (text == null) {
                return "";
            }
            return text.length() <= 80 ? text : text.substring(0, 80) + "...";
        }
    }
}
