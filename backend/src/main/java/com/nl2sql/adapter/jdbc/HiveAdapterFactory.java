package com.nl2sql.adapter.jdbc;

import com.nl2sql.adapter.DataSourceAdapter;
import com.nl2sql.adapter.DataSourceAdapterFactory;
import org.springframework.stereotype.Component;

/**
 * Hive 适配器工厂。
 *
 * <p>向 {@link com.nl2sql.adapter.DataSourceFactory} 注册 hive 类型元数据。
 */
@Component
public class HiveAdapterFactory implements DataSourceAdapterFactory {

    /** {@inheritDoc} */
    @Override
    public String type() {
        return "hive";
    }

    /** {@inheritDoc} */
    @Override
    public String displayName() {
        return "Hive";
    }

    /** {@inheritDoc} */
    @Override
    public int defaultPort() {
        return 10000;
    }

    /** {@inheritDoc} */
    @Override
    public DataSourceAdapter create() {
        return new HiveAdapter();
    }
}
