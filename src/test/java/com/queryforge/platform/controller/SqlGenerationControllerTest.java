package com.queryforge.platform.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.queryforge.platform.exception.ApiException;
import com.queryforge.platform.exception.ErrorCode;
import com.queryforge.platform.security.AppUserPrincipal;
import com.queryforge.platform.service.SqlGenerationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SqlGenerationController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("SQL 流式生成接口的错误响应")
class SqlGenerationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SqlGenerationService service;

    @BeforeEach
    void authenticate() {
        AppUserPrincipal principal = new AppUserPrincipal(1L, "alice@example.com", "Alice", "hash");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("客户端接受 SSE 时，未配置模型仍返回 JSON 503 而不是空响应")
    void generate_shouldReturnJsonError_whenModelNotConfigured() throws Exception {
        when(service.prepare(any(), anyLong(), anyString())).thenThrow(
                new ApiException(ErrorCode.MODEL_NOT_CONFIGURED, HttpStatus.SERVICE_UNAVAILABLE, "模型服务未配置"));

        mockMvc.perform(post("/api/v1/sql/generate/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"userInput":"统计订单数量","connectionId":1,"strategyName":"default"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                                .andExpect(jsonPath("$.code").value("MODEL_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("空白 userInput 返回 400 VALIDATION_ERROR")
    void generate_shouldReturnValidationError_whenUserInputBlank() throws Exception {
        mockMvc.perform(post("/api/v1/sql/generate/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"userInput":"   ","connectionId":1,"strategyName":"default"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("未知 strategyName 被 Bean Validation 拒绝")
    void generate_shouldRejectUnknownStrategy() throws Exception {
        mockMvc.perform(post("/api/v1/sql/generate/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userInput":"统计订单数量","connectionId":1,"strategyName":"multi-agent"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
