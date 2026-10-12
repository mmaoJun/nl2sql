package com.nl2sql.adapter;

/**
 * 数据源类型元数据。
 *
 * <p>由 {@link DataSourceFactory#supportedTypes()} 聚合输出，
 * 供前端动态渲染数据源类型选择器和默认端口预填，
 * 后端新增类型时前端零改动即可感知。
 *
 * @param code        类型编码（如 mysql、postgresql）
 * @param displayName 展示名称（如 MySQL、PostgreSQL）
 * @param defaultPort 默认端口（如 3306、5432）
 */
public record DataSourceTypeInfo(String code, String displayName, int defaultPort) {
}
