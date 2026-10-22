package com.queryforge.application.agent;
import com.queryforge.domain.model.LlmClient;
import com.queryforge.domain.model.LlmStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.domain.agent.AgentRoute;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Manager 路由解析与降级（P14-A T1）")
class ManagerRouterTest {

    @Mock
    private LlmClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ManagerRouter router;

    @BeforeEach
    void setUp() {
        // 必须在 Mockito 注入 @Mock 字段之后构造，否则 router 持有 null 客户端。
        router = new ManagerRouter(llmClient, objectMapper);
    }

    private static ManagerRouter.RoutingContext context() {
        return new ManagerRouter.RoutingContext(
                "帮我看看最近订单", "shop_orders(id, amount)", "订单=已支付单据", "[最近] 上一轮问了统计");
    }

    private void stubModel(String payload) {
        when(llmClient.openChat(anyString(), anyString()))
                .thenReturn(new LlmStream(Stream.of("data: {\"choices\":[{\"delta\":{\"content\":"
                        + ObjectMapperJson.quote(payload) + "}}]}", "data: [DONE]")));
    }

    @Test
    @DisplayName("解析全部合法路由并保留澄清原因")
    void parse_shouldAcceptKnownRoutes() {
        assertThat(router.parse("{\"route\":\"SIMPLE\"}").route()).isEqualTo(AgentRoute.SIMPLE);
        assertThat(router.parse("{\"route\":\"COMPLEX\",\"reasonCode\":\"需要多步\"}").reason()).isEqualTo("需要多步");
        assertThat(router.parse("```json\n{\"route\":\"CHITCHAT\"}\n```").route()).isEqualTo(AgentRoute.CHITCHAT);
        assertThat(router.parse("{\"route\":\"CLARIFY\",\"reasonCode\":\"口径不明\"}").reason()).isEqualTo("口径不明");
        assertThat(router.parse("{\"route\":\"KNOWLEDGE\"}").route()).isEqualTo(AgentRoute.KNOWLEDGE);
        assertThat(router.parse("{\"route\":\"medium\"}").route()).isEqualTo(AgentRoute.MEDIUM);
    }

    @Test
    @DisplayName("未知路由与坏 JSON 都不放行")
    void parse_shouldRejectUnknownRouteOrBadJson() {
        assertThatThrownBy(() -> router.parse("{\"route\":\"EXECUTE_DELETE\"}"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_PLAN_INVALID));
        assertThatThrownBy(() -> router.parse("随便一段文本"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_PLAN_INVALID));
        // TOOL_INVOCATION 只能由用户显式选择触发，模型返回该值同样拒绝。
        assertThatThrownBy(() -> router.parse("{\"route\":\"TOOL_INVOCATION\"}"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("图表/报告意图判为 COMPLEX，纯查询判为 SIMPLE")
    void route_shouldDistinguishExpectedOutput() {
        stubModel("{\"route\":\"COMPLEX\",\"reasonCode\":\"用户要图表\"}");
        assertThat(router.route(context()).route()).isEqualTo(AgentRoute.COMPLEX);

        stubModel("{\"route\":\"SIMPLE\",\"reasonCode\":\"一条 SQL 即可\"}");
        assertThat(router.route(context()).route()).isEqualTo(AgentRoute.SIMPLE);
    }

    @Test
    @DisplayName("非法 JSON 降级 MEDIUM 并继续走 Planner，不把 Run 判失败")
    void route_shouldDegradeToMediumOnInvalidJson() {
        stubModel("这不是 JSON");

        ManagerRouter.RouteDecision decision = router.route(context());

        assertThat(decision.route()).isEqualTo(AgentRoute.MEDIUM);
        assertThat(decision.reason()).contains("降级");
    }

    @Test
    @DisplayName("模型超时/上游错误同样降级 MEDIUM")
    void route_shouldDegradeToMediumOnModelFailure() {
        when(llmClient.openChat(anyString(), anyString()))
                .thenThrow(new ApiException(ErrorCode.LLM_UPSTREAM_ERROR,
                        org.springframework.http.HttpStatus.BAD_GATEWAY, "模型服务暂不可用"));

        ManagerRouter.RouteDecision decision = router.route(context());

        assertThat(decision.route()).isEqualTo(AgentRoute.MEDIUM);
        assertThat(decision.reason()).doesNotContain("模型服务暂不可用");
    }

    @Test
    @DisplayName("空响应降级 MEDIUM")
    void route_shouldDegradeToMediumOnEmptyResponse() {
        stubModel("");

        assertThat(router.route(context()).route()).isEqualTo(AgentRoute.MEDIUM);
    }

    @Test
    @DisplayName("分类请求真正携带原问题、Schema 摘要与知识概要")
    void route_shouldSendRealContextToModel() {
        stubModel("{\"route\":\"CHITCHAT\"}");

        router.route(new ManagerRouter.RoutingContext(
                "你好", "orders(id)", "GMV 定义", "[最近] 上一轮内容"));

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).openChat(anyString(), captor.capture());
        String userPrompt = captor.getValue();
        assertThat(userPrompt).contains("你好");
        assertThat(userPrompt).contains("orders(id)");
        assertThat(userPrompt).contains("GMV 定义");
        assertThat(userPrompt).contains("上一轮内容");
    }

    /** 生成 JSON 字符串字面量（避免测试里手写转义）。 */
    private static final class ObjectMapperJson {
        static String quote(String value) {
            try {
                return new ObjectMapper().writeValueAsString(value);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
