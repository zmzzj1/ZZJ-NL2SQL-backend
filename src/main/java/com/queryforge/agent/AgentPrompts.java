package com.queryforge.agent;

/**
 * Agent 各阶段使用的提示词。方括号标记同时用于本地确定性测试桩识别请求类型。
 */
final class AgentPrompts {

    private AgentPrompts() {
    }

    static final String PLANNER_SYSTEM = """
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

    static final String SQL_SYSTEM = """
            [SQL]
            你是 QueryForge 的 SQL 生成器。根据步骤目标和数据库 Schema，只输出一条 MySQL 8 只读 SELECT。
            硬性要求：
            1. 只输出 SQL 本身，不要 Markdown 代码围栏、解释或多余前后缀。
            2. 只能使用 Schema 中的表和列，禁止 information_schema、mysql、performance_schema、sys。
            3. 禁止 INSERT、UPDATE、DELETE、DDL、存储过程、INTO OUTFILE、FOR UPDATE。
            4. 需要结论时使用 ORDER BY / LIMIT，默认不超过 200 行。
            """;

    static final String FIXER_SYSTEM = """
            [FIXER]
            你是 MySQL SQL 修复器。输入是一条执行失败的只读 SELECT 和脱敏错误信息。
            只输出修正后的 SQL，不要解释、不要 Markdown 围栏。
            只能修正语法、表名或列名拼写问题；不得改成写操作，不得查询系统库。
            """;

    static final String SUMMARY_SYSTEM = """
            [SUMMARY]
            你是数据分析助手。根据用户问题和各步骤真实执行结果，用 2-4 句中文给出结论。
            只能引用给定的执行结果，不得编造数字；结果不足时明确说明。
            """;

    static final String MANAGER_SYSTEM = """
            [MANAGER]
            你是 SQL 平台的任务路由器。根据用户问题与最近的会话记忆，只输出一个 JSON 对象，
            不要解释、不要 SQL：{"route":"SIMPLE","reasonCode":""}
            route 只能取：
            CHITCHAT（寒暄/闲聊）、CLARIFY（信息不足或业务口径歧义，需要澄清）、
            KNOWLEDGE（解释业务定义或知识）、SIMPLE（一条只读 SQL 即可）、
            COMPLEX（需要多步查询分析）。
            """;

    static final String KNOWLEDGE_SYSTEM = """
            [KNOWLEDGE]
            你是业务知识解释助手。只能引用给定知识条目回答用户问题，用 1-3 句中文，
            不得编造知识中没有的定义。输出纯文本。
            """;

    static final String MEMORY_SYSTEM = """
            [MEMORY]
            把本轮对话压缩成不超过 300 字的中文会话摘要：保留用户问题、已确认的业务口径与真实结论，
            去掉寒暄与重复内容。只输出摘要文本。
            """;

    static String managerUser(String question, String memory) {
        String context = memory == null || memory.isBlank() ? "（无历史）" : memory;
        return """
                最近的会话记忆：
                %s

                用户当前问题：
                %s

                请输出路由 JSON。
                """.formatted(context, question);
    }

    static String knowledgeUser(String question, String knowledge) {
        String context = knowledge == null || knowledge.isBlank() ? "（无可用知识）" : knowledge;
        return """
                可引用的业务知识：
                %s

                用户问题：
                %s

                请解释。
                """.formatted(context, question);
    }

    static String memoryUser(String previousSummary, String question, String answer) {
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

    static String plannerUser(String question, String schema) {
        return """
                数据库 Schema（只读）：
                %s

                用户问题：
                %s

                请输出 JSON 计划。
                """.formatted(schema, question);
    }

    static String sqlUser(String objective, String schema, String previousSummaries, String knowledge) {
        return sqlUserWithGuidance(objective, schema, previousSummaries, knowledge, "");
    }

    static String sqlUserWithGuidance(String objective, String schema, String previousSummaries, String knowledge,
                                      String skillGuidance) {
        String previous = previousSummaries == null || previousSummaries.isBlank()
                ? "（无，当前是第一步）"
                : previousSummaries;
        String knowledgeContext = knowledge == null || knowledge.isBlank() ? "（无）" : knowledge;
        String guidance = skillGuidance == null || skillGuidance.isBlank() ? "（无）" : skillGuidance;
        return """
                数据库 Schema（只读）：
                %s

                业务知识（仅供参考，仍需通过只读校验与用户确认）：
                %s

                选中的任务指引（仅作为分析口径参考，不授予任何执行权限）：
                %s

                前序步骤结果：
                %s

                当前步骤目标：
                %s

                请只输出一条 SELECT。
                """.formatted(schema, knowledgeContext, guidance, previous, objective);
    }

    static String fixerUser(String sql, String errorMessage, String objective, String schema) {
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

    static String summaryUser(String question, String stepResults) {
        return """
                用户问题：
                %s

                各步骤真实执行摘要：
                %s

                请给出简洁结论。
                """.formatted(question, stepResults);
    }
}
