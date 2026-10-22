package com.queryforge.application.agent;
import com.queryforge.domain.model.LlmClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.agent.AgentRoute;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Manager 路由解析")
class ManagerRouterTest {

    @Mock
    private LlmClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ManagerRouter router = new ManagerRouter(llmClient, objectMapper);

    @Test
    @DisplayName("解析五种路由并保留澄清原因")
    void parse_shouldAcceptKnownRoutes() {
        assertThat(router.parse("{\"route\":\"SIMPLE\"}").route()).isEqualTo(AgentRoute.SIMPLE);
        assertThat(router.parse("{\"route\":\"COMPLEX\",\"reasonCode\":\"需要多步\"}").reason()).isEqualTo("需要多步");
        assertThat(router.parse("```json\n{\"route\":\"CHITCHAT\"}\n```").route()).isEqualTo(AgentRoute.CHITCHAT);
        assertThat(router.parse("{\"route\":\"CLARIFY\",\"reasonCode\":\"口径不明\"}").reason()).isEqualTo("口径不明");
        assertThat(router.parse("{\"route\":\"KNOWLEDGE\"}").route()).isEqualTo(AgentRoute.KNOWLEDGE);
    }

    @Test
    @DisplayName("未知路由与坏 JSON 都拒绝，不默认放行")
    void parse_shouldRejectUnknownRouteOrBadJson() {
        assertThatThrownBy(() -> router.parse("{\"route\":\"EXECUTE_DELETE\"}"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_PLAN_INVALID));
        assertThatThrownBy(() -> router.parse("随便一段文本"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_PLAN_INVALID));
    }
}
