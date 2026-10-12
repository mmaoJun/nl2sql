package com.nl2sql.adapter.jdbc;

import com.nl2sql.adapter.DataSourceAdapter;
import com.nl2sql.adapter.DataSourceAdapterFactory;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 适配器工厂。
 *
 * <p>向 {@link com.nl2sql.adapter.DataSourceFactory} 注册 postgresql 类型元数据。
 */
@Component
public class PostgreSQLAdapterFactory implements DataSourceAdapterFactory {

    /** {@inheritDoc} */
    @Override
    public String type() {
        return "postgresql";
    }

    /** {@inheritDoc} */
    @Override
    public String displayName() {
        return "PostgreSQL";
    }

    /** {@inheritDoc} */
    @Override
    public int defaultPort() {
        return 5432;
    }

    /** {@inheritDoc} */
    @Override
    public DataSourceAdapter create() {
        return new PostgreSQLAdapter();
    }
}
