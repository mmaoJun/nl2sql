package com.nl2sql.service;

import com.nl2sql.adapter.DataSourceFactory;
import com.nl2sql.adapter.DataSourceTypeInfo;
import com.nl2sql.common.BusinessException;
import com.nl2sql.common.ErrorCode;
import com.nl2sql.mapper.DataSourceMapper;
import com.nl2sql.model.DataSource;
import com.nl2sql.model.dto.DataSourceDTO;
import com.nl2sql.model.vo.DataSourceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数据源管理服务。
 *
 * <p>提供数据源的 CRUD、连接测试功能。更新或删除数据源时会同步清理
 * {@link DataSourceFactory} 中的适配器缓存，确保后续请求使用最新配置。
 *
 * @see DataSourceVO
 * @see DataSourceFactory
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataSourceService {

    private final DataSourceMapper dataSourceMapper;
    private final DataSourceFactory dataSourceFactory;

    /**
     * 创建数据源。
     *
     * @param dto 数据源创建参数
     * @return 创建成功的数据源视图（密码脱敏为 "******"）
     */
    public DataSourceVO create(DataSourceDTO dto) {
        validateType(dto.getType());
        DataSource ds = toEntity(dto);
        dataSourceMapper.insert(ds);
        log.info("创建数据源: id={}, name={}, type={}", ds.getId(), ds.getName(), ds.getType());
        return DataSourceVO.fromEntity(ds);
    }

    /**
     * 查询所有数据源。
     *
     * @return 数据源视图列表（密码脱敏）
     */
    public List<DataSourceVO> list() {
        return dataSourceMapper.selectList(null).stream()
                .map(DataSourceVO::fromEntity)
                .toList();
    }

    /**
     * 更新数据源配置。
     *
     * <p>密码字段为 "******" 时视为未修改，保留原值；否则更新为新密码。
     * 更新后清除 {@link DataSourceFactory} 中的适配器缓存。
     *
     * @param id  数据源 ID
     * @param dto 数据源更新参数
     * @return 更新后的数据源视图
     * @throws BusinessException 数据源不存在时抛出
     */
    public DataSourceVO update(Long id, DataSourceDTO dto) {
        DataSource ds = getById(id);
        validateType(dto.getType());
        ds.setName(dto.getName());
        ds.setType(dto.getType());
        ds.setHost(dto.getHost());
        ds.setPort(dto.getPort());
        ds.setDatabaseName(dto.getDatabaseName());
        ds.setUsername(dto.getUsername());
        if (!"******".equals(dto.getPassword())) {
            ds.setPassword(dto.getPassword());
        }
        ds.setExtraConfig(dto.getExtraConfig());
        dataSourceMapper.updateById(ds);
        dataSourceFactory.removeAdapter(id);
        log.info("更新数据源: id={}", id);
        return DataSourceVO.fromEntity(ds);
    }

    /**
     * 删除数据源并清理适配器缓存。
     *
     * @param id 数据源 ID
     */
    public void delete(Long id) {
        dataSourceMapper.deleteById(id);
        dataSourceFactory.removeAdapter(id);
        log.info("删除数据源: id={}", id);
    }

    /**
     * 测试数据源连接是否可用。
     *
     * @param id 数据源 ID
     * @return true 表示连接成功，false 表示连接失败
     * @throws BusinessException 数据源不存在时抛出
     */
    public boolean testConnection(Long id) {
        DataSource ds = getById(id);
        return dataSourceFactory.testConnection(ds.getType(), ds.toAdapterConfig());
    }

    /**
     * 查询所有已注册的数据源类型元数据。
     *
     * @return 类型元数据列表（编码、展示名、默认端口）
     */
    public List<DataSourceTypeInfo> supportedTypes() {
        return dataSourceFactory.supportedTypes();
    }

    /**
     * 按 ID 加载数据源实体，供内部服务调用。
     *
     * @param id 数据源 ID
     * @return 数据源实体（包含完整连接信息，含明文密码）
     * @throws BusinessException 数据源不存在时抛出
     */
    public DataSource getById(Long id) {
        DataSource ds = dataSourceMapper.selectById(id);
        if (ds == null) {
            throw new BusinessException(ErrorCode.DATASOURCE_NOT_FOUND, "数据源不存在");
        }
        return ds;
    }

    private DataSource toEntity(DataSourceDTO dto) {
        DataSource ds = new DataSource();
        ds.setName(dto.getName());
        ds.setType(dto.getType());
        ds.setHost(dto.getHost());
        ds.setPort(dto.getPort());
        ds.setDatabaseName(dto.getDatabaseName());
        ds.setUsername(dto.getUsername());
        ds.setPassword(dto.getPassword());
        ds.setExtraConfig(dto.getExtraConfig());
        return ds;
    }

    private void validateType(String type) {
        if (!dataSourceFactory.isSupported(type)) {
            throw new BusinessException(ErrorCode.DATASOURCE_TYPE_UNSUPPORTED,
                    "不支持的数据源类型: " + type + "，当前支持: " + dataSourceFactory.supportedTypes()
                            .stream().map(DataSourceTypeInfo::code).toList());
        }
    }
}
