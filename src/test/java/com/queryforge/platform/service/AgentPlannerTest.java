package com.queryforge.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.platform.config.AgentProperties;
import com.queryforge.platform.exception.ApiException;
import com.queryforge.platform.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 计划解析")
class AgentPlannerTest {

    @Mock
    private LlmClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentProperties properties = new AgentProperties(3, 2, 6000, 12);
    private final AgentPlanner planner = new AgentPlanner(llmClient, objectMapper, properties);

    @Test
    @DisplayName("解析带 Markdown 围栏的两步计划")
    void parse_shouldAcceptFencedJson() {
        AgentPlan plan = planner.parse("""
                ```json
                {"answerable": true, "reason": "", "steps": [
                  {"objective": "统计每月订单数"},
                  {"objective": "找出订单量最高的月份"}
                ]}
                ```
                """);

        assertThat(plan.answerable()).isTrue();
        assertThat(plan.steps()).extracting(AgentPlan.Step::objective)
                .containsExactly("统计每月订单数", "找出订单量最高的月份");
    }

    @Test
    @DisplayName("不可回答时返回原因且没有步骤")
    void parse_shouldKeepUnanswerableReason() {
        AgentPlan plan = planner.parse("""
                {"answerable": false, "reason": "当前 Schema 不包含退款表", "steps": []}
                """);

        assertThat(plan.answerable()).isFalse();
        assertThat(plan.reason()).contains("退款表");
        assertThat(plan.steps()).isEmpty();
    }

    @Test
    @DisplayName("步骤数超过配置上限时拒绝，不静默截断")
    void parse_shouldRejectTooManySteps() {
        assertThatThrownBy(() -> planner.parse("""
                {"answerable": true, "steps": [
                  {"objective": "a"}, {"objective": "b"}, {"objective": "c"}, {"objective": "d"}
                ]}
                """))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_PLAN_INVALID));
    }

    @Test
    @DisplayName("非法 JSON 或空步骤返回 AGENT_PLAN_INVALID")
    void parse_shouldRejectInvalidJson() {
        assertThatThrownBy(() -> planner.parse("抱歉，我无法回答"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_PLAN_INVALID));

        assertThatThrownBy(() -> planner.parse("{\"answerable\": true, \"steps\": []}"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_PLAN_INVALID));
    }
}
