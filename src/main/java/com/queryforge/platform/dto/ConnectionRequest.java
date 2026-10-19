package com.queryforge.platform.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建/修改连接共用请求。密码允许为空（无密码账号，如本地 root）；
 * 修改时留空表示沿用原密文，不会清空密码。
 */
public record ConnectionRequest(
        @NotBlank(message = "连接名称不能为空")
        @Size(max = 100, message = "连接名称最长 100 字符")
        String name,

        @NotBlank(message = "数据库类型不能为空")
        String engine,

        @NotBlank(message = "主机不能为空")
        @Size(max = 255, message = "主机最长 255 字符")
        String host,

        @Min(value = 1, message = "端口需在 1-65535 之间")
        @Max(value = 65535, message = "端口需在 1-65535 之间")
        int port,

        @NotBlank(message = "数据库名不能为空")
        @Size(max = 64, message = "数据库名最长 64 字符")
        String database,

        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名最长 64 字符")
        String username,

        @Size(max = 128, message = "密码最长 128 字符")
        String password
) {
}
