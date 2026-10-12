package com.nl2sql.adapter.jdbc;

import com.nl2sql.adapter.DataSourceAdapter;
import com.nl2sql.adapter.DataSourceAdapterFactory;
import org.springframework.stereotype.Component;

/**
 * MySQL 适配器工厂。
 *
 * <p>向 {@link com.nl2sql.adapter.DataSourceFactory} 注册 mysql 类型元数据。
 */
@Component
public class MySQLAdapterFactory implements DataSourceAdapterFactory {

    /** {@inheritDoc} */
    @Override
    public String type() {
        return "mysql";
    }

    /** {@inheritDoc} */
    @Override
    public String displayName() {
        return "MySQL";
    }

    /** {@inheritDoc} */
    @Override
    public int defaultPort() {
        return 3306;
    }

    /** {@inheritDoc} */
    @Override
    public DataSourceAdapter create() {
        return new MySQLAdapter();
    }
}
