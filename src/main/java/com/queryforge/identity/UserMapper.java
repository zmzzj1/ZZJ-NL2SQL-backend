package com.queryforge.identity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * P12 S1：users 管理实体的 MyBatis-Plus Mapper。
 *
 * <p>只承载平台管理库的用户元数据 CRUD 与条件查询；不接触用户业务库，
 * 也不替代任何 SQL Guard/审批链路。授权主体仍由服务端会话决定。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
