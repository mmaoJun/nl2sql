package com.nl2sql.adapter;

/**
 * 数据源适配器工厂（SPI 扩展点）。
 *
 * <p>每种数据源类型提供一个实现并标注 {@code @Component}，
 * {@link DataSourceFactory} 启动时通过 Spring 集合注入自动收集注册，
 * 新增类型无需改动工厂的既有代码（开闭原则）。
 *
 * @see DataSourceAdapter
 * @see DataSourceTypeInfo
 */
public interface DataSourceAdapterFactory {

    /**
     * 类型编码，全局唯一，与 {@code datasources.type} 字段对应（如 mysql、postgresql）。
     *
     * @return 类型编码
     */
    String type();

    /**
     * SQL 方言标识，默认与类型编码一致；同一驱动多种方言时可覆写。
     *
     * @return 方言名称
     */
    default String dialect() {
        return type();
    }

    /**
     * 展示名称，供前端类型选择器渲染。
     *
     * @return 展示名称（如 MySQL、PostgreSQL）
     */
    String displayName();

    /**
     * 默认端口，供前端新建数据源时预填。
     *
     * @return 默认端口号
     */
    int defaultPort();

    /**
     * 创建一个新的适配器实例（不负责建立连接）。
     *
     * @return 适配器实例
     */
    DataSourceAdapter create();
}
