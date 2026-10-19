package com.queryforge.analysis;

import java.util.List;
import java.util.Map;

/**
 * 分析执行器：输入是服务端已授权的受限结果投影（列 + 行），输出结构化图表或统计结果。
 * 实现必须保证模型生成的代码运行在隔离运行时，绝不回退到宿主机执行。
 */
public interface AnalysisEngine {

    /** 引擎标识：BuiltinAnalyzer（受信任确定性聚合）或 SandboxPython（隔离容器）。 */
    String name();

    /** 是否可用于当前环境（例如 Docker 守护进程不可用时 SandboxPython 返回 false）。 */
    boolean available();

    /**
     * 对受控结果执行分析，返回结构化图表与文字摘要。
     *
     * @param code   模型/用户生成的代码（BuiltinAnalyzer 忽略该参数）
     * @param columns 结果列名（已裁剪）
     * @param rows    结果行（已裁剪、限行限长）
     */
    AnalysisResult run(String code, List<String> columns, List<Map<String, Object>> rows) throws AnalysisExecutionException;

    record AnalysisResult(
            List<ChartSchema.TypedChart> charts,
            String summary,
            String stdoutTail,
            String stderrTail,
            int exitCode
    ) {
    }
}
