package com.nl2sql.adapter.api;

import com.nl2sql.adapter.DataSourceAdapter;
import com.nl2sql.adapter.DataSourceAdapterFactory;
import org.springframework.stereotype.Component;

/**
 * REST API 适配器工厂。
 *
 * <p>向 {@link com.nl2sql.adapter.DataSourceFactory} 注册 rest 类型元数据。
 * 方言上报 h2（SQL 实际由 H2 内存表执行），而非类型编码 rest。
 */
@Component
public class RestApiAdapterFactory implements DataSourceAdapterFactory {

    /** {@inheritDoc} */
    @Override
    public String type() {
        return "rest";
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
        return "REST API";
    }

    /** {@inheritDoc} */
    @Override
    public int defaultPort() {
        // 与本地 mock-api 服务端口一致（docker/mock-api）
        return 3100;
    }

    /** {@inheritDoc} */
    @Override
    public DataSourceAdapter create() {
        return new RestApiAdapter();
    }
}
