package com.nl2sql.adapter.hdfs;

import com.nl2sql.adapter.DataSourceAdapter;
import com.nl2sql.adapter.DataSourceAdapterFactory;
import org.springframework.stereotype.Component;

/**
 * HDFS 文件适配器工厂。
 *
 * <p>向 {@link com.nl2sql.adapter.DataSourceFactory} 注册 hdfs 类型元数据。
 * 方言上报 h2（SQL 实际由 H2 内存库执行），而非类型编码 hdfs。
 */
@Component
public class HdfsFileAdapterFactory implements DataSourceAdapterFactory {

    /** {@inheritDoc} */
    @Override
    public String type() {
        return "hdfs";
    }

    /** {@inheritDoc} */
    @Override
    public String dialect() {
        // SQL 由 H2 内存表执行，语法按 H2（如 LIMIT）；覆盖默认的 type() 返回值
        return "h2";
    }

    /** {@inheritDoc} */
    @Override
    public String displayName() {
        return "HDFS 文件";
    }

    /** {@inheritDoc} */
    @Override
    public int defaultPort() {
        return 9870;
    }

    /** {@inheritDoc} */
    @Override
    public DataSourceAdapter create() {
        return new HdfsFileAdapter();
    }
}
