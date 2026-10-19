package com.queryforge.conversation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.queryforge.conversation.ConversationSummaryResponse;
import com.queryforge.shared.pagination.PageResponse;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.identity.AppUserPrincipal;
import com.queryforge.conversation.ConversationService;
import java.time.LocalDateTime;
import java.util.List;
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

@WebMvcTest(ConversationController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("会话接口")
class ConversationControllerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 6, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConversationService service;

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
    @DisplayName("创建会话返回 201 与数据源快照")
    void create_shouldReturnCreated() throws Exception {
        when(service.create(eq(1L), any())).thenReturn(summary(7L));

        mockMvc.perform(post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connectionId\":10,\"title\":\"统计订单数量\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.connectionName").value("A 商店库"))
                .andExpect(jsonPath("$.title").value("统计订单数量"));
    }

    @Test
    @DisplayName("列表返回 items 与 nextCursor")
    void list_shouldReturnPage() throws Exception {
        when(service.list(1L, 2, "cursor-1")).thenReturn(new PageResponse<>(List.of(summary(7L)), "cursor-2"));

        mockMvc.perform(get("/api/v1/conversations").param("limit", "2").param("cursor", "cursor-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(7))
                .andExpect(jsonPath("$.nextCursor").value("cursor-2"));
    }

    @Test
    @DisplayName("删除返回 204")
    void delete_shouldReturnNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/conversations/7"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("访问他人的会话返回 404 与 CONVERSATION_NOT_FOUND")
    void detail_shouldReturnNotFound() throws Exception {
        doThrow(new ApiException(
                ErrorCode.CONVERSATION_NOT_FOUND, HttpStatus.NOT_FOUND, "会话不存在或无权访问"))
                .when(service).detail(1L, 7L);

        mockMvc.perform(get("/api/v1/conversations/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
    }

    private static ConversationSummaryResponse summary(long id) {
        return new ConversationSummaryResponse(id, "统计订单数量", 10L, "A 商店库", NOW, NOW);
    }
}
