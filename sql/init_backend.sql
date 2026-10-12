-- NL2SQL 后端元数据库初始化脚本
-- 数据库: nlp2sql_meta

CREATE DATABASE IF NOT EXISTS nlp2sql_meta DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE nlp2sql_meta;

-- 数据源配置表
CREATE TABLE IF NOT EXISTS datasources (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '数据源名称',
    type VARCHAR(50) NOT NULL COMMENT '类型: mysql/postgresql/hive/hdfs/rest_api/graphql',
    host VARCHAR(200) COMMENT '主机地址',
    port INT COMMENT '端口',
    database_name VARCHAR(100) COMMENT '数据库名',
    username VARCHAR(100) COMMENT '用户名',
    password VARCHAR(200) COMMENT '密码(加密)',
    extra_config JSON COMMENT '额外配置(JSON)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '数据源配置';

-- 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) UNIQUE NOT NULL,
    password VARCHAR(200) NOT NULL COMMENT 'BCrypt加密',
    role VARCHAR(20) DEFAULT 'USER',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT '用户信息';

-- 审计日志表
CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT,
    datasource_id BIGINT,
    nl_input TEXT NOT NULL COMMENT '自然语言输入',
    generated_sql TEXT COMMENT '生成的SQL',
    validation_passed BOOLEAN COMMENT '校验是否通过',
    execution_success BOOLEAN COMMENT '执行是否成功',
    row_count INT COMMENT '结果行数',
    execution_time_ms BIGINT COMMENT '执行耗时(毫秒)',
    error_message TEXT COMMENT '错误信息',
    model_name VARCHAR(100) COMMENT '实际调用的模型id',
    model_provider VARCHAR(30) COMMENT '实际调用的模型提供方',
    session_id BIGINT COMMENT '所属会话，无会话模式为空',
    turn_id BIGINT COMMENT '所属轮次，无会话模式为空',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at)
) COMMENT '审计日志';

-- 2026-09-30: audit_logs 增加模型列（已有库执行一次）
-- ALTER TABLE audit_logs
--   ADD COLUMN model_name VARCHAR(100) COMMENT '实际调用的模型id',
--   ADD COLUMN model_provider VARCHAR(30) COMMENT '实际调用的模型提供方';

-- 对话会话表（2026-10-08 对话记忆 M1）
CREATE TABLE IF NOT EXISTS chat_sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '归属用户，来自 JWT，不接受前端传入',
    title VARCHAR(100) COMMENT '标题，首轮提问后取问题前 30 字',
    datasource_id BIGINT COMMENT '会话默认数据源，可为空',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_updated (user_id, updated_at)
) COMMENT '对话会话';

-- 对话轮次表（一轮一行：改写需要"原话+独立问题+SQL"在同一行）
CREATE TABLE IF NOT EXISTS chat_turns (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    question TEXT NOT NULL COMMENT '用户原话',
    resolved_question TEXT COMMENT '改写后的独立问题，NULL 表示本轮未改写（nl2sql 无改写层，恒为 NULL）',
    rewrite_status VARCHAR(20) NOT NULL DEFAULT 'skipped' COMMENT 'skipped / applied / failed（nl2sql 恒为 skipped）',
    datasource_id BIGINT NOT NULL COMMENT '本轮实际查询的库',
    model_name VARCHAR(100) COMMENT '本轮实际调用的模型 id',
    model_provider VARCHAR(30) COMMENT '本轮实际调用的 provider',
    generated_sql TEXT,
    row_count INT,
    execution_time_ms BIGINT,
    status VARCHAR(20) NOT NULL COMMENT 'ok / failed / empty',
    error_message VARCHAR(500),
    answer TEXT COMMENT '本轮助手回答文本，历史注入用；失败/空回答时为 NULL',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_datasource (session_id, datasource_id, id),
    INDEX idx_session_created (session_id, created_at)
) COMMENT '对话轮次';

-- 2026-10-08: audit_logs 增加会话归因列（已有库执行一次）
-- ALTER TABLE audit_logs
--   ADD COLUMN session_id BIGINT COMMENT '所属会话，无会话模式为空',
--   ADD COLUMN turn_id BIGINT COMMENT '所属轮次，无会话模式为空';

-- 2026-10-12: chat_turns 增加助手回答列（T11 历史注入需要；已有库执行一次）
-- ALTER TABLE chat_turns
--   ADD COLUMN answer TEXT COMMENT '本轮助手回答文本，历史注入用；失败/空回答时为 NULL';
