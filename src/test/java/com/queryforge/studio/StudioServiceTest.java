package com.queryforge.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.skill.SkillService;
import com.queryforge.tool.ToolRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("Agent Studio 服务")
@ExtendWith(MockitoExtension.class)
class StudioServiceTest {

    private static final long USER_ID = 1L;
    private static final long AGENT_ID = 10L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 12, 0);

    @Mock
    private StudioRepository repository;

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private SkillService skillService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StudioService service;

    @BeforeEach
    void setUp() {
        service = new StudioService(repository, objectMapper, toolRegistry, skillService);
    }

    private StudioService.AgentRequest request() {
        return new StudioService.AgentRequest(
                "销售分析师", "分析销售", "以简洁中文回答", null, List.of(), null, null, List.of());
    }

    @Test
    @DisplayName("创建 Agent 持久化草稿")
    void create_shouldPersistDraft() {
        when(repository.insertDefinition(anyLong(), anyString(), anyString(), anyString())).thenReturn(AGENT_ID);
        AgentDefinition definition = definition(0, null);
        when(repository.findOwned(AGENT_ID, USER_ID)).thenReturn(Optional.of(definition));

        StudioService.AgentResponse response = service.create(USER_ID, request());

        assertThat(response.id()).isEqualTo(AGENT_ID);
        assertThat(response.draftRevision()).isZero();
        verify(repository).audit(USER_ID, "AGENT", AGENT_ID, "CREATE", null, "SUCCESS", null);
    }

    @Test
    @DisplayName("过期 revision 更新被拒绝")
    void update_shouldRejectStaleRevision() {
        when(repository.findOwned(AGENT_ID, USER_ID)).thenReturn(Optional.of(definition(1, null)));
        when(repository.updateDraft(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.update(USER_ID, AGENT_ID, 0, request()))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_CONFLICT));
    }

    @Test
    @DisplayName("发布生成不可变版本并更新发布指针")
    void publish_shouldCreateVersion() {
        when(repository.findOwned(AGENT_ID, USER_ID)).thenReturn(Optional.of(definition(0, null)));
        when(repository.maxVersionNo(AGENT_ID)).thenReturn(0);
        when(repository.insertVersion(anyLong(), anyInt(), anyString(), anyString(), anyString(), any(), anyString(), anyLong()))
                .thenReturn(100L);
        when(repository.setPublishedVersion(AGENT_ID, USER_ID, 100L, 0)).thenReturn(1);
        AgentVersion version = new AgentVersion(100L, AGENT_ID, 1, "{}", "{}", "[]", null, "hash", USER_ID, NOW);
        when(repository.findVersion(100L)).thenReturn(Optional.of(version));

        StudioService.AgentVersionResponse response = service.publish(USER_ID, AGENT_ID, 0, "v1");

        assertThat(response.versionNo()).isEqualTo(1);
        verify(repository).audit(USER_ID, "AGENT", AGENT_ID, "PUBLISH", 1, "SUCCESS", "v1");
    }

    @Test
    @DisplayName("回滚基于源版本生成新版本，不修改源版本")
    void rollback_shouldCreateNewVersion() {
        AgentDefinition def = definition(2, 100L);
        when(repository.findOwned(AGENT_ID, USER_ID)).thenReturn(Optional.of(def));
        AgentVersion source = new AgentVersion(100L, AGENT_ID, 1, "{\"name\":\"v1\"}", "{}", "[]",
                null, "hash1", USER_ID, NOW);
        when(repository.findVersion(100L)).thenReturn(Optional.of(source));
        when(repository.maxVersionNo(AGENT_ID)).thenReturn(1);
        when(repository.insertVersion(anyLong(), anyInt(), anyString(), anyString(), anyString(), any(), anyString(), anyLong()))
                .thenReturn(101L);
        when(repository.updatePublishedVersion(AGENT_ID, USER_ID, 101L)).thenReturn(1);
        AgentVersion newVersion = new AgentVersion(101L, AGENT_ID, 2, source.contentSnapshot(), "{}", "[]",
                null, "hash1", USER_ID, NOW);
        when(repository.findVersion(101L)).thenReturn(Optional.of(newVersion));

        StudioService.AgentVersionResponse response = service.rollback(USER_ID, AGENT_ID, 100L, 2);

        assertThat(response.versionNo()).isEqualTo(2);
        assertThat(response.contentHash()).isEqualTo("hash1"); // 内容来自源版本
        verify(repository, never()).updateDraft(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("越权访问他人 Agent 被拒绝")
    void detail_shouldRejectForeignAccess() {
        when(repository.findOwned(AGENT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(USER_ID, AGENT_ID))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_NOT_FOUND));
    }

    private AgentDefinition definition(int revision, Long publishedVersionId) {
        return new AgentDefinition(AGENT_ID, USER_ID, "销售分析师", "", "DRAFT", revision,
                "{\"name\":\"销售分析师\",\"systemPrompt\":\"\",\"tools\":[],\"skillIds\":[]}",
                publishedVersionId, NOW, NOW);
    }
}
