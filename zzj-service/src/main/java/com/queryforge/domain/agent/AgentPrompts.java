package com.queryforge.domain.agent;

/**
 * Agent 各阶段使用的提示词。方括号标记同时用于本地确定性测试桩识别请求类型。
 */
public final class AgentPrompts {

    private AgentPrompts() {
    }

    public static final String PLANNER_SYSTEM = """
            [PLANNER]
            你是 QueryForge 的 SQL 分析规划器。根据用户问题与数据库 Schema 判断问题是否可回答，
            并在可回答时拆分为有序、可独立验证的查询步骤。
            只输出一个 JSON 对象，不要 Markdown 代码围栏或解释：
            {"answerable": true, "reason": "", "steps": [{"objective": "步骤目标"}]}
            规则：
            1. 只能使用给定 Schema 中的表和列；字段或表不存在时 answerable=false，并在 reason 说明缺少什么。
            2. 简单问题只输出 1 步；确有先后依赖时才拆成多步，最多 5 步。
            3. 每一步目标必须是一个可以用单条只读 SELECT 完成的查询，不要输出写操作。
            4. 不要输出 SQL，只输出步骤目标。
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
}
