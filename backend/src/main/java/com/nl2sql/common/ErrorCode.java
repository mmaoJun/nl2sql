package com.nl2sql.common;

/**
 * 业务错误码常量定义。
 *
 * <p>按域分段编码：
 * <ul>
 *   <li>4xx — 通用 HTTP 语义错误</li>
 *   <li>1xxx — 数据源相关</li>
 *   <li>1005-1007 — 模型路由相关</li>
 *   <li>2xxx — NL2SQL 引擎相关</li>
 *   <li>3xxx — 会话相关</li>
 *   <li>9xxx — 系统级错误</li>
 * </ul>
 *
 * @see BusinessException
 */
public final class ErrorCode {

    private ErrorCode() {}

    // ── 通用错误 ──

    /** 请求参数错误 */
    public static final int BAD_REQUEST = 400;
    /** 未认证或 Token 无效 */
    public static final int UNAUTHORIZED = 401;
    /** 无权限 */
    public static final int FORBIDDEN = 403;
    /** 资源不存在 */
    public static final int NOT_FOUND = 404;

    // ── 数据源 1xxx ──

    /** 数据源不存在 */
    public static final int DATASOURCE_NOT_FOUND = 1001;
    /** 数据源连接失败 */
    public static final int DATASOURCE_CONNECTION_FAILED = 1002;
    /** 数据源名称重复 */
    public static final int DATASOURCE_DUPLICATE_NAME = 1003;
    /** 不支持的数据源类型 */
    public static final int DATASOURCE_TYPE_UNSUPPORTED = 1004;

    // ── 模型 1005-1007（跨域配置类错误，延续 1xxx 段）──

    /** 请求的模型不在候选白名单内 */
    public static final int MODEL_UNSUPPORTED = 1005;
    /** 模型所属 provider 缺少凭据或凭据无效 */
    public static final int MODEL_NOT_CONFIGURED = 1006;
    /** 模型服务不可达（本地服务未启动、连接超时等） */
    public static final int MODEL_UNAVAILABLE = 1007;

    // ── NL2SQL 引擎 2xxx ──

    /** SQL 生成失败 */
    public static final int SQL_GENERATION_FAILED = 2001;
    /** SQL 安全校验失败 */
    public static final int SQL_VALIDATION_FAILED = 2002;
    /** SQL 执行失败 */
    public static final int SQL_EXECUTION_FAILED = 2003;
    /** 未找到 Schema */
    public static final int NO_SCHEMA_FOUND = 2004;

    // ── 会话 3xxx ──

    /**
     * 会话不存在，或不属于当前用户。
     *
     * <p>两种情况刻意合并为同一个错误码与同一句消息：分开报等于告诉调用方
     * "这个 id 确实存在，只是不是你的"，属于可枚举的信息泄露。
     */
    public static final int SESSION_NOT_FOUND = 3001;

    // ── 系统 9xxx ──

    /** 系统内部错误 */
    public static final int SYSTEM_ERROR = 9999;
}
