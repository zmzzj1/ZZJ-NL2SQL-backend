package com.queryforge.infrastructure.persistence;

import com.queryforge.domain.identity.AppUserPrincipal;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import com.queryforge.domain.identity.UserEntity;

/**
 * 用户表只存在于可写管理库，业务查询库没有用户数据。
 *
 * <p>P12 S1：持久化从显式 JdbcTemplate 迁移到 MyBatis-Plus {@link UserMapper}
 * （BaseMapper + LambdaQueryWrapper），消除重复的列映射与手写 SQL；
 * 唯一索引冲突仍转换为统一的 {@link DuplicateEmailException}。
 */
@Repository
public class UserRepository {

    private final UserMapper userMapper;

    public UserRepository(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public Optional<AppUserPrincipal> findByEmail(String email) {
        UserEntity entity = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getEmail, email)
                .last("LIMIT 1"));
        return Optional.ofNullable(entity).map(UserRepository::toPrincipal);
    }

    /** P13-M3A：按主键重建认证主体（RPC 判权只认数据库里的持久化 role）。 */
    public Optional<AppUserPrincipal> findPrincipalById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(userMapper.selectById(id)).map(UserRepository::toPrincipal);
    }

    /** P13-M3A：管理端用户检索总行数（keyword 为空即全量）。 */
    public long countByKeyword(String keyword) {
        return userMapper.selectCount(keywordWrapper(keyword));
    }

    /**
     * P13-M3A：管理端用户分页（倒序返回）。
     *
     * <p>LIMIT/OFFSET 只由已被契约层收敛成 int 的分页参数拼出，不接受任何外部字符串，
     * 因此不存在拼接注入面；关键词仍走参数绑定。
     */
    public List<UserEntity> pageByKeyword(String keyword, int page, int size) {
        int offset = (page - 1) * size;
        return userMapper.selectList(keywordWrapper(keyword)
                .orderByDesc(UserEntity::getId)
                .last(String.format("LIMIT %d OFFSET %d", size, offset)));
    }

    private static LambdaQueryWrapper<UserEntity> keywordWrapper(String keyword) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword + "%";
            wrapper.and(nested -> nested.like(UserEntity::getEmail, pattern)
                    .or().like(UserEntity::getDisplayName, pattern));
        }
        return wrapper;
    }
    public boolean existsByEmail(String email) {
        return userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getEmail, email)) > 0;
    }

    public Long insert(String email, String displayName, String passwordHash) {
        UserEntity entity = new UserEntity();
        entity.setEmail(email);
        entity.setDisplayName(displayName);
        entity.setPasswordHash(passwordHash);
        try {
            userMapper.insert(entity);
        } catch (RuntimeException ex) {
            // MyBatis 会包装底层 SQL 异常；沿因果链识别唯一索引冲突，保持既有错误码语义。
            if (isDuplicateEmail(ex)) {
                throw new DuplicateEmailException(email, ex);
            }
            throw ex;
        }
        if (entity.getId() == null) {
            throw new IllegalStateException("用户创建后未返回主键");
        }
        return entity.getId();
    }

    private static boolean isDuplicateEmail(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof DuplicateKeyException
                    || current instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static AppUserPrincipal toPrincipal(UserEntity entity) {
        return new AppUserPrincipal(
                entity.getId(),
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getPasswordHash(),
                entity.getRole());
    }

    /** 并发注册时唯一索引兜底，由 Service 转换为统一错误码。 */
    public static final class DuplicateEmailException extends RuntimeException {
        public DuplicateEmailException(String email, Throwable cause) {
            super("邮箱已注册：" + email, cause);
        }
    }
}
