package com.nl2sql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nl2sql.model.DataSource;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据源 Mapper 接口。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper}，提供 {@link DataSource} 实体的标准 CRUD 操作。
 */
@Mapper
public interface DataSourceMapper extends BaseMapper<DataSource> {
}
