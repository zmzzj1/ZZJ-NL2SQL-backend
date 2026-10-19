package com.queryforge.model;

import com.queryforge.identity.AppUserPrincipal;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 平台管理员判权。管理员身份仅由服务端持久化 role 字段驱动，前端传入 role 无效。
 * 平台管理员与 Workspace OWNER 正交，互不包含。
 */
@Component
public class AdminGuard {

    public void requireAdmin(AppUserPrincipal principal) {
        if (principal == null || !principal.isPlatformAdmin()) {
            throw new ApiException(ErrorCode.ADMIN_FORBIDDEN, HttpStatus.FORBIDDEN, "仅平台管理员可执行此操作");
        }
    }
}
