package com.nl2sql.adapter;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源适配器工厂（注册表）。
 *
 * <p>启动时通过 Spring 集合注入收集所有 {@link DataSourceAdapterFactory} Bean，
 * 按类型编码建立注册表；运行时按类型创建适配器，并用 {@link ConcurrentHashMap}
 * 缓存已连接的适配器实例，避免重复创建连接池。数据源更新或删除时通过
 * {@link #removeAdapter} 清理缓存并断开连接。
 *
 * <p><b>扩展方式</b>：新增数据源类型只需提供 {@code DataSourceAdapter} 实现 +
 * {@code @Component} 标注的 {@link DataSourceAdapterFactory}，本类无需任何改动。
 *
 * @see DataSourceAdapter
 * @see DataSourceAdapterFactory
 */
@Component
public class DataSourceFactory {

    private final Map<String, DataSourceAdapterFactory> registry;
    private final Map<Long, DataSourceAdapter> adapterCache = new ConcurrentHashMap<>();

    public DataSourceFactory(List<DataSourceAdapterFactory> factories) {
        this.registry = new ConcurrentHashMap<>();
        for (DataSourceAdapterFactory factory : factories) {
            String code = factory.type().toLowerCase();
            DataSourceAdapterFactory existing = registry.putIfAbsent(code, factory);
            if (existing != null) {
                throw new IllegalStateException("数据源类型重复注册: " + code
                        + " (" + existing.getClass().getSimpleName() + " / " + factory.getClass().getSimpleName() + ")");
            }
        }
    }

    /**
     * 获取或创建数据源适配器。
     *
     * <p>缓存命中时直接返回；未命中时创建新适配器并建立连接后缓存。
     *
     * @param datasourceId 数据源 ID，用作缓存 Key
     * @param type         数据源类型（如 mysql）
     * @param config       连接配置
     * @return 已连接的数据源适配器
     */
    public DataSourceAdapter getAdapter(Long datasourceId, String type, Map<String, Object> config) {
        return adapterCache.computeIfAbsent(datasourceId, id -> {
            DataSourceAdapter adapter = createAdapter(type);
            adapter.connect(config);
            return adapter;
        });
    }

    /**
     * 根据类型创建适配器实例（不建立连接）。
     *
     * @param type 数据源类型
     * @return 对应的适配器实例
     * @throws IllegalArgumentException 不支持的数据源类型
     */
    public DataSourceAdapter createAdapter(String type) {
        DataSourceAdapterFactory factory = type == null ? null : registry.get(type.toLowerCase());
        if (factory == null) {
            throw new IllegalArgumentException("不支持的数据源类型: " + type);
        }
        return factory.create();
    }

    /**
     * 判断数据源类型是否已注册。
     *
     * @param type 数据源类型
     * @return true 表示支持该类型
     */
    public boolean isSupported(String type) {
        return type != null && registry.containsKey(type.toLowerCase());
    }

    /**
     * 获取指定类型对应的 SQL 方言标识（不创建适配器实例）。
     *
     * @param type 数据源类型
     * @return 方言名称
     * @throws IllegalArgumentException 不支持的数据源类型
     */
    public String getDialect(String type) {
        DataSourceAdapterFactory factory = type == null ? null : registry.get(type.toLowerCase());
        if (factory == null) {
            throw new IllegalArgumentException("不支持的数据源类型: " + type);
        }
        return factory.dialect();
    }

    /**
     * 列出所有已注册的数据源类型元数据，供前端动态渲染类型选择器。
     *
     * @return 类型元数据列表
     */
    public List<DataSourceTypeInfo> supportedTypes() {
        return registry.values().stream()
                .map(f -> new DataSourceTypeInfo(f.type(), f.displayName(), f.defaultPort()))
                .sorted(java.util.Comparator.comparing(DataSourceTypeInfo::code))
                .toList();
    }

    /**
     * 移除并断开指定数据源的适配器缓存。
     *
     * @param datasourceId 数据源 ID
     */
    public void removeAdapter(Long datasourceId) {
        DataSourceAdapter adapter = adapterCache.remove(datasourceId);
        if (adapter != null) {
            adapter.disconnect();
        }
    }

    /**
     * 测试数据源连接是否可用（创建临时适配器，测试后断开）。
     *
     * @param type   数据源类型
     * @param config 连接配置
     * @return true 表示连接可用
     */
    public boolean testConnection(String type, Map<String, Object> config) {
        DataSourceAdapter adapter = createAdapter(type);
        try {
            return adapter.testConnection(config);
        } finally {
            adapter.disconnect();
        }
    }
}
