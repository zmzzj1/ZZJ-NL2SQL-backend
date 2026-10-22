package com.queryforge.domain.agent;

import java.util.List;

/**
 * Agent 各阶段使用的提示词。方括号标记同时用于本地确定性测试桩识别请求类型。
 */
public final class AgentPrompts {

    private AgentPrompts() {
    }

    public static final String PLANNER_SYSTEM = """
            [PLANNER]
            你是 QueryForge 的 SQL 分析规划器。根据用户问题与数据库 Schema 判断问题是否可回答，
            并在可回答时拆分为有序、可独立验证的步骤。
            只输出一个 JSON 对象，不要 Markdown 代码围栏或解释：
            {"answerable": true, "reason": "", "steps": [{"objective": "步骤目标", "agent": "DATA_ANALYST", "dependsOn": 1}]}
            规则：
            1. 只能使用给定 Schema 中的表和列；字段或表不存在时 answerable=false，并在 reason 说明缺少什么。
            2. 简单问题只输出 1 步；确有先后依赖时才拆成多步，最多 5 步。
            3. agent 只能取 DATA_ANALYST（需要产生只读 SQL 取数）、CODE_ANALYST（对已确认查询结果做 Python 分析）、
               REPORT_ASSISTANT（基于真实结果生成报告）；取数步骤必须是 DATA_ANALYST。
            4. dependsOn 只能引用本步骤之前已经出现的步骤序号（从 1 开始），禁止自环；第一步省略该字段。
            5. 报告步骤必须依赖已经产生真实数据的步骤；没有真实数据时不要规划报告。
            6. 每一步目标必须是一个可以用单条只读 SELECT 完成的查询，不要输出写操作。
            7. 不要输出 SQL 或 Python 代码，只输出步骤目标。
            """;

    public static final String SQL_SYSTEM = """
            [SQL]
            你是 QueryForge 的 SQL 生成器。根据步骤目标和数据库 Schema，只输出一条 MySQL 8 只读 SELECT。
            硬性要求：
            1. 只输出 SQL 本身，不要 Markdown 代码围栏、解释或多余前后缀。
            2. 只能使用 Schema 中的表和列，禁止 information_schema、mysql、performance_schema、sys。
            3. 禁止 INSERT、UPDATE、DELETE、DDL、存储过程、INTO OUTFILE、FOR UPDATE。
            4. 需要结论时使用 ORDER BY / LIMIT，默认不超过 200 行。
            """;

    /**
     * P14-B：多候选 SQL 生成的系统提示。硬性安全约束与 {@link #SQL_SYSTEM} 完全一致，
     * 只额外强调“一次一条语句”，避免把多语句拼接当成合法候选。
     */
    public static final String SQL_CANDIDATE_SYSTEM = """
            [SQL-CANDIDATE]
            你是 QueryForge 的专业 SQL 生成器。根据步骤目标和数据库 Schema，只输出一条 MySQL 8 只读 SELECT。
            硬性要求：
            1. 只输出 SQL 本身，不要 Markdown 代码围栏、解释或多余前后缀。
            2. 只能使用 Schema 中的表和列，禁止 information_schema、mysql、performance_schema、sys。
            3. 禁止 INSERT、UPDATE、DELETE、DDL、存储过程、INTO OUTFILE、FOR UPDATE。
            4. 一次只输出一条语句，不得用分号拼接多条 SQL。
            5. 需要结论时使用 ORDER BY / LIMIT，默认不超过 200 行。
            """;

    /**
     * P14-B：候选评分系统提示。只评审给定候选，不得改写或新造 SQL。
     */
    public static final String SQL_SCORER_SYSTEM = """
            [SQL-SCORER]
            你是 SQL 评审专家。根据步骤目标与数据库 Schema，从正确性、查询效率、可读性三个维度
            综合评审给定的候选 SQL。只输出 JSON 数组，不要解释、不要 SQL：
            [{"candidate":1,"score":8,"reason":"匹配业务口径，JOIN 路径清晰"}]
            规则：
            1. candidate 是候选在给定列表中的序号，从 1 开始，不得重复、越界。
            2. score 是 1-10 的整数，10 为最佳。
            3. 只评审给定候选，不得修改、重写或建议新的 SQL。
            """;

    public static final String FIXER_SYSTEM = """
            [FIXER]
            你是 MySQL SQL 修复器。输入是一条执行失败的只读 SELECT 和脱敏错误信息。
            只输出修正后的 SQL，不要解释、不要 Markdown 围栏。
            只能修正语法、表名或列名拼写问题；不得改成写操作，不得查询系统库。
            """;

    public static final String SUMMARY_SYSTEM = """
            [SUMMARY]
            你是数据分析助手。根据用户问题和各步骤真实执行结果，用 2-4 句中文给出结论。
            只能引用给定的执行结果，不得编造数字；结果不足时明确说明。
            """;

    public static final String MANAGER_SYSTEM = """
            [MANAGER]
            你是 SQL 平台的任务路由器。根据用户原问题、已授权 Schema 摘要、已授权知识概要与
            最近会话压缩结果，只输出一个 JSON 对象，不要解释、不要 SQL：
            {"route":"SIMPLE","reasonCode":""}
            route 只能取：
            CHITCHAT（寒暄、能力介绍、与数据库无关的自然语言问题）；
            CLARIFY（缺少关键表/指标/时间窗口等业务口径，当前无法可靠执行）；
            KNOWLEDGE（解释授权知识库中的业务定义）；
            SIMPLE（用户只要直接查询结果，一条只读 SQL 足以满足）；
            COMPLEX（要图表、报告、仪表板、跨步骤分析、解释建议，或步骤间有显式依赖）；
            MEDIUM（你无法可靠判定时使用，由后端走 Planner 安全降级）。
            判定规则（按用户想得到什么产物判断，而不是按 SQL 条数）：
            1. 只要用户明确要求图表、完整报告或仪表板，即使最终只需查询一张表，也必须判 COMPLEX。
            2. 没有报表诉求的单条数据查询不要无故进入复杂编排。
            3. 不要输出 SQL，不要执行任何操作，不要编造 Schema 中不存在的表或列。
            4. 不得返回 SIMPLE/COMPLEX 之外的任何自定义值；无法判定时输出 MEDIUM。
            """;

    public static final String CHITCHAT_SYSTEM = """
            [CHITCHAT]
            你是 QueryForge 的 SQL 分析助手，正在与已登录用户自然对话。
            硬性要求：
            1. 用简洁、自然的中文回答；不要输出 SQL、Python、JSON、HTML 或报告。
            2. 不要访问数据库，不要调用任何工具，不要声称已经执行了查询。
            3. 可以介绍自己的能力（基于已授权 Schema 生成只读 SQL、等待确认后执行、
              做多步分析与图表、解释业务知识），但不得编造不存在的功能或数据。
            4. 只输出回答正文本身。
            """;

    public static final String KNOWLEDGE_SYSTEM = """
            [KNOWLEDGE]
            你是业务知识解释助手。只能引用给定知识条目回答用户问题，用 1-3 句中文，
            不得编造知识中没有的定义。输出纯文本。
            """;

    public static final String MEMORY_SYSTEM = """
            [MEMORY]
            把本轮对话压缩成不超过 300 字的中文会话摘要：保留用户问题、已确认的业务口径与真实结论，
            去掉寒暄与重复内容。只输出摘要文本。
            """;

    public static String managerUser(String question, String memory) {
        return managerUser(question, memory, "", "");
    }

    /**
     * Manager 分类请求：原始问题 + 受预算约束的会话压缩结果 + 已授权 Schema 摘要 +
     * 已授权知识概要。三者都只放摘要，不回传私有全文。
     */
    public static String managerUser(
            String originalQuestion, String conversationHistory, String schemaSummary, String evidenceSummary) {
        String history = conversationHistory == null || conversationHistory.isBlank()
                ? "（无历史）"
                : conversationHistory;
        String schema = schemaSummary == null || schemaSummary.isBlank()
                ? "（未提供 Schema 摘要）"
                : schemaSummary;
        String evidence = evidenceSummary == null || evidenceSummary.isBlank()
                ? "（无授权知识）"
                : evidenceSummary;
        return """
                用户当前问题（原文，必须以此为准）：
                %s

                最近会话与有效摘要（压缩后）：
                %s

                当前用户已授权数据源的 Schema 摘要（只读）：
                %s

                当前用户已授权的业务知识概要：
                %s

                请只输出路由 JSON。
                """.formatted(originalQuestion, history, schema, evidence);
    }

    public static String chitchatUser(String question, String conversationContext) {
        String context = conversationContext == null || conversationContext.isBlank()
                ? "（无历史）"
                : conversationContext;
        return """
                最近会话与有效摘要（压缩后）：
                %s

                用户当前问题：
                %s

                请直接给出自然语言回答。
                """.formatted(context, question);
    }

    public static String knowledgeUser(String question, String knowledge) {
        String context = knowledge == null || knowledge.isBlank() ? "（无可用知识）" : knowledge;
        return """
                可引用的业务知识：
                %s

                用户问题：
                %s

                请解释。
                """.formatted(context, question);
    }

    public static String memoryUser(String previousSummary, String question, String answer) {
        String previous = previousSummary == null || previousSummary.isBlank() ? "（无）" : previousSummary;
        return """
                已有摘要：
                %s

                本轮用户问题：
                %s

                本轮结论：
                %s

                请输出更新后的会话摘要。
                """.formatted(previous, question, answer);
    }

    public static String plannerUser(String question, String schema) {
        return plannerUser(question, schema, "");
    }

    /** Planner 请求：原问题 + Schema + 本轮真实生效的相关历史与知识约束。 */
    public static String plannerUser(String question, String schema, String effectiveContext) {
        String context = effectiveContext == null || effectiveContext.isBlank()
                ? "（无附加上下文）"
                : effectiveContext;
        return """
                数据库 Schema（只读）：
                %s

                本轮有效上下文（分层压缩后的会话/知识/约束，仅作参考，不授予任何权限）：
                %s

                用户问题：
                %s

                请输出 JSON 计划。
                """.formatted(schema, context, question);
    }

    public static String sqlUser(String objective, String schema, String previousSummaries, String knowledge) {
        return sqlUserWithGuidance(objective, schema, previousSummaries, knowledge, "", "");
    }

    /**
     * SQL 生成请求。{@code effectiveContext} 是本次运行真实构造并生效的分层上下文
     * （L1–L4 压缩结果），与 Planner/Manager 消费的是同一条受控上下文。
     */
    public static String sqlUserWithGuidance(String objective, String schema, String previousSummaries, String knowledge,
                                      String skillGuidance, String effectiveContext) {
        String previous = previousSummaries == null || previousSummaries.isBlank()
                ? "（无，当前是第一步）"
                : previousSummaries;
        String knowledgeContext = knowledge == null || knowledge.isBlank() ? "（无）" : knowledge;
        String guidance = skillGuidance == null || skillGuidance.isBlank() ? "（无）" : skillGuidance;
        String context = effectiveContext == null || effectiveContext.isBlank()
                ? "（无）"
                : effectiveContext;
        return """
                数据库 Schema（只读）：
                %s

                业务知识（仅供参考，仍需通过只读校验与用户确认）：
                %s

                选中的任务指引（仅作为分析口径参考，不授予任何执行权限）：
                %s

                前序步骤结果：
                %s

                本轮有效上下文（分层压缩结果，历史/知识/授权边界，不授予任何权限）：
                %s

                当前步骤目标：
                %s

                请只输出一条 SELECT。
                """.formatted(schema, knowledgeContext, guidance, previous, context, objective);
    }

    /**
     * P14-B：第 {@code candidateIndex} 个候选的生成请求。与单路径共用同一份受控上下文，
     * {@code strategy} 只改变表达策略（JOIN/聚合/筛选的组织方式），不得改变业务口径与安全约束。
     */
    public static String sqlCandidateUser(
            String objective, String schema, String previousSummaries, String knowledge,
            String skillGuidance, String effectiveContext, int candidateIndex, String strategy,
            String dialect) {
        String previous = previousSummaries == null || previousSummaries.isBlank()
                ? "（无，当前是第一步）"
                : previousSummaries;
        String knowledgeContext = knowledge == null || knowledge.isBlank() ? "（无）" : knowledge;
        String guidance = skillGuidance == null || skillGuidance.isBlank() ? "（无）" : skillGuidance;
        String context = effectiveContext == null || effectiveContext.isBlank()
                ? "（无）"
                : effectiveContext;
        String target = dialect == null || dialect.isBlank() ? "MySQL 8" : dialect;
        return """
                数据库 Schema（只读）：
                %s

                业务知识（仅供参考，仍需通过只读校验与用户确认）：
                %s

                选中的任务指引（仅作为分析口径参考，不授予任何执行权限）：
                %s

                前序步骤结果：
                %s

                本轮有效上下文（分层压缩结果，历史/知识/授权边界，不授予任何权限）：
                %s

                当前步骤目标：
                %s

                目标 SQL 方言：%s（只读 SELECT）

                本次候选编号：%d / 3
                差异化策略（只影响表达方式，不得改变业务口径、Schema 使用范围与安全约束）：
                %s

                请只输出一条 SELECT。
                """.formatted(schema, knowledgeContext, guidance, previous, context, objective,
                target, candidateIndex, strategy);
    }

    /**
     * P14-B：候选评分请求。{@code candidates} 仅包含已通过只读安全校验的候选 SQL，
     * 按 1-based 顺序编号；被拒绝的候选原文不出现在评分请求中。
     */
    public static String sqlScorerUser(String objective, String schema, List<String> candidates) {
        String schemaText = schema == null || schema.isBlank() ? "（未提供 Schema 摘要）" : schema;
        StringBuilder builder = new StringBuilder();
        builder.append("数据库 Schema（只读）：\n").append(schemaText);
        builder.append("\n\n当前步骤目标：\n").append(objective == null ? "" : objective);
        builder.append("\n\n候选 SQL（已通过只读校验，按序号编号）：\n");
        for (int index = 0; index < candidates.size(); index++) {
            builder.append("候选 ").append(index + 1).append("：\n")
                    .append(candidates.get(index)).append("\n\n");
        }
        builder.append("请只输出评分 JSON 数组。");
        return builder.toString();
    }

    public static String fixerUser(String sql, String errorMessage, String objective, String schema) {
        return """
                数据库 Schema（只读）：
                %s

                步骤目标：
                %s

                失败 SQL：
                %s

                脱敏错误：
                %s

                请只输出修正后的 SELECT。
                """.formatted(schema, objective, sql, errorMessage);
    }

    public static String summaryUser(String question, String stepResults) {
        return """
                用户问题：
                %s

                各步骤真实执行摘要：
                %s

                请给出简洁结论。
                """.formatted(question, stepResults);
    }

    /**
     * P14-C：Python 代码生成的系统提示。硬性约束与 SQL 生成一致：
     * 只输出代码本体；代码只在隔离沙箱内运行，不得访问网络/凭据/宿主文件/系统命令。
     */
    public static final String PYTHON_SYSTEM = """
            [PYTHON]
            你是 QueryForge 的 Python 分析代码生成器。输入是已授权数据库查询的真实列与样例/统计摘要。
            你只生成一段在隔离沙箱中运行的 Python 代码，不执行任何操作、不访问网络。
            硬性要求：
            1. 只输出 Python 代码本体，不要 Markdown 代码围栏、解释或多余前后缀。
            2. 只能使用 Python 标准库；不得 import 任何第三方包（沙箱内未安装）。
            3. 不得读取数据库连接串、环境变量中的密钥、宿主文件路径；不得访问网络；不得执行系统命令。
            4. 输入文件协议：从 /work/input.json 读取 {"columns":[...],"rows":[{...}]}；
               结果必须写入 /output/output.json。
            5. 输出文件必须是结构化 JSON：
               {"charts":[{"chartType":"bar","title":"...","xLabel":"...","yLabel":"...",
                           "categories":["..."],"values":[1],"sourceRunId":"..."}],
                "summary":"基于真实数据的结论"}
               chartType 只允许 table/bar/line/pie：
               table 需要 title、columns（name/dataType）、data、sourceRunId；
               bar/line 需要 title、xLabel、yLabel、categories、values、sourceRunId；
               pie 需要 title、labels、values、sourceRunId。
            6. 所有数值必须由 input.json 的真实行计算得出；数据为空或不足以分析时，
               charts 输出空数组并在 summary 中如实说明，不得编造数值。
            7. 代码必须自洽：即使输入只有表头没有数据，也要正常写出 output.json。
            """;

    /** P14-C：分析结果自然语言解释的系统提示。只解释已验证的真实 charts/summary。 */
    public static final String PYTHON_INTERPRET_SYSTEM = """
            [PYTHON-INTERPRET]
            你是数据分析解释助手。输入是隔离沙箱真实执行产出的结构化图表与统计摘要。
            用 2-4 句中文解释这些真实结果对用户问题的意义。
            硬性要求：
            1. 只能引用给定的图表与摘要中的数值，不得编造、外推或四舍五入出新结论。
            2. 摘要说明数据不足时，如实说明不足，不得补演示数据。
            3. 不输出 SQL、Python、JSON 或 HTML，只输出解释正文。
            """;

    /** P14-C：报告叙述合成系统提示。只允许基于真实工件行文，禁止捏造数字。 */
    public static final String REPORT_COMPOSER_SYSTEM = """
            [REPORT-COMPOSER]
            你是报告叙述助手。输入是同一次运行中真实完成的 SQL 结果摘要、Python 分析图表与摘要。
            用 3-6 句中文写一段报告导语：说明数据来源、分析了什么、真实结论是什么。
            硬性要求：
            1. 只能引用给定材料中的数字与结论；材料不足时明确说明，不得编造金额、比例或趋势。
            2. 不输出 HTML、SQL、Python 或 JSON，只输出纯文本导语。
            """;

    /**
     * P14-C：Python 代码生成请求。{@code columnsInfo} 是已批准结果的列名/类型；
     * {@code realDataSummary} 是受限真实行的样例或统计摘要（脱敏、有界）；
     * {@code instructions} 是角色级补充约束（不得覆盖硬性安全规则）。
     */
    public static String pythonCodeUser(String objective, String columnsInfo, String realDataSummary,
                                        String effectiveContext, String instructions) {
        String columns = columnsInfo == null || columnsInfo.isBlank() ? "（未提供列信息）" : columnsInfo;
        String sample = realDataSummary == null || realDataSummary.isBlank() ? "（无数据行）" : realDataSummary;
        String context = effectiveContext == null ? "" : effectiveContext;
        String extra = instructions == null || instructions.isBlank() ? "" : instructions;
        return """
                分析目标：
                %s

                已批准结果的列（名称: 类型）：
                %s

                真实数据样例/统计摘要（来自已授权查询，禁止外推）：
                %s

                本轮有效上下文（分层压缩结果，不授予任何权限）：
                %s

                附加约束（不得违反上述硬性要求）：
                %s

                请只输出一段 Python 代码。
                """.formatted(objective == null ? "" : objective, columns, sample, context, extra);
    }

    /** P14-C：分析解释请求。charts 以确定性 JSON 文本传入，保证只解释真实工件。 */
    public static String pythonInterpretUser(String question, String objective, String chartsJson,
                                             String verifiedSummary, String boundedStdout) {
        return """
                用户问题：
                %s

                分析目标：
                %s

                真实图表（结构化 JSON，已通过 schema 校验）：
                %s

                沙箱产出的统计摘要：
                %s

                有界 stdout 摘要（可能为空）：
                %s

                请只输出 2-4 句中文解释。
                """.formatted(
                question == null ? "" : question,
                objective == null ? "" : objective,
                chartsJson == null ? "[]" : chartsJson,
                verifiedSummary == null || verifiedSummary.isBlank() ? "（无）" : verifiedSummary,
                boundedStdout == null ? "" : boundedStdout);
    }

    /** P14-C：报告导语合成请求。全部材料必须来自同一次运行的真实工件。 */
    public static String reportComposerUser(String question, String sqlFacts, String analysisFacts,
                                            String chartDigest) {
        return """
                用户问题：
                %s

                已确认 SQL 的真实结果摘要：
                %s

                Python 分析真实摘要：
                %s

                真实图表清单（类型/标题/来源标识）：
                %s

                请只输出报告导语纯文本。
                """.formatted(
                question == null ? "" : question,
                sqlFacts == null || sqlFacts.isBlank() ? "（无）" : sqlFacts,
                analysisFacts == null || analysisFacts.isBlank() ? "（无）" : analysisFacts,
                chartDigest == null || chartDigest.isBlank() ? "（无）" : chartDigest);
    }
}
