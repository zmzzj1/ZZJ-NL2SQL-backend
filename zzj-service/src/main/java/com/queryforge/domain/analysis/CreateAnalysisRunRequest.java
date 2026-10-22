package com.queryforge.domain.analysis;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建分析运行的请求。
 * 输入必须是「本次真实查询产生的受控结果工件」，而不是前端伪造的数组：
 * 服务端仅凭 queryHistoryId 重新校验权限与数据源，并重新执行 SQL 获取真实行，
 * 不接受客户端直接上传结果数据。code 仅在 engine=BuiltinAnalyzer 时忽略；
 * engine=SandboxPython 时才真正送入隔离运行时。
 */
public record CreateAnalysisRunRequest(
        @NotNull Long queryHistoryId,
        @Size(max = 20000) String code,
        String chartType
) {
}
