package com.queryforge.application.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.queryforge.application.tool.AgentRunToolPolicy.ToolPolicyDecision;
import com.queryforge.application.tool.AgentRunToolPolicy.ToolPolicyState;
import com.queryforge.domain.agent.AgentRun;
import com.queryforge.infrastructure.persistence.AgentRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P14-A T4：Agent 工具白名单三态裁决。区分“默认不限制”与“已发布配置解析损坏”，
 * 后者在显式工具分支必须拒绝而非降级放行。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Agent Run 工具策略三态裁决")
class AgentRunToolPolicyTest {

    private static final long RUN_ID = 5L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 12, 0);

    @Mock
    private AgentRunRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentRunToolPolicy policy;

    private void stubRun(Long agentId, String toolPolicyHash) {
        when(repository.findRun(RUN_ID)).thenReturn(Optional.of(new AgentRun(
                RUN_ID, 1L, 7L, 10L, "A 商店库", "问题", com.queryforge.domain.agent.RunStatus.RUNNING,
                null, null, null, 0, null, null, null, NOW, NOW,
                agentId, 1L, null, toolPolicyHash,
                Boolean.TRUE, 0, null, false, null, 0, null)));
    }

    private AgentRunToolPolicy policy() {
        policy = new AgentRunToolPolicy(repository, objectMapper);
        return policy;
    }

    @Test
    @DisplayName("未关联 Agent 时是真正的默认不限制")
    void decide_shouldBeUnrestrictedWithoutAgent() {
        stubRun(null, null);

        ToolPolicyDecision decision = policy().decide(RUN_ID);

        assertThat(decision.state()).isEqualTo(ToolPolicyState.UNRESTRICTED);
        assertThat(decision.allows("any_tool")).isTrue();
    }

    @Test
    @DisplayName("已发布白名单内的工具放行，名单外拒绝")
    void decide_shouldAllowOnlyWhitelistedTools() {
        stubRun(3L, "[\"describe_schema\"]");

        ToolPolicyDecision decision = policy().decide(RUN_ID);

        assertThat(decision.state()).isEqualTo(ToolPolicyState.ALLOWED);
        assertThat(decision.allows("describe_schema")).isTrue();
        assertThat(decision.allows("drop_table")).isFalse();
    }

    @Test
    @DisplayName("白名单 JSON 解析损坏时拒绝，不降级放行")
    void decide_shouldRejectWhenPolicyCorrupted() {
        stubRun(3L, "{不是合法 JSON");

        ToolPolicyDecision decision = policy().decide(RUN_ID);

        assertThat(decision.state()).isEqualTo(ToolPolicyState.CORRUPT);
        assertThat(decision.allows("describe_schema")).isFalse();
        assertThat(policy().allowsStrictly(RUN_ID, "describe_schema")).isFalse();
    }

    @Test
    @DisplayName("合法但空的白名单表示不允许任何工具")
    void decide_shouldDenyAllWhenWhitelistEmpty() {
        stubRun(3L, "[]");

        ToolPolicyDecision decision = policy().decide(RUN_ID);

        assertThat(decision.state()).isEqualTo(ToolPolicyState.ALLOWED);
        assertThat(decision.allows("describe_schema")).isFalse();
    }

    @Test
    @DisplayName("运行不存在时不猜测放行（交由 ToolRegistry 归属校验）")
    void decide_shouldBeUnrestrictedWhenRunMissing() {
        when(repository.findRun(RUN_ID)).thenReturn(Optional.empty());

        assertThat(policy().decide(RUN_ID).state()).isEqualTo(ToolPolicyState.UNRESTRICTED);
    }

    @Test
    @DisplayName("历史语义保持：allowedToolNames 对空/损坏返回空列表")
    void allowedToolNames_shouldKeepLegacySemantics() {
        stubRun(3L, "{损坏");

        assertThat(policy().allowedToolNames(RUN_ID)).isEmpty();
        assertThat(List.of()).isEmpty();
    }
}
