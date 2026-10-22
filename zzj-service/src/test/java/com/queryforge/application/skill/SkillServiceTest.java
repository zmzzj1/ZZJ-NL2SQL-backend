package com.queryforge.application.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import com.queryforge.infrastructure.persistence.StudioRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.queryforge.domain.skill.SkillDefinition;
import com.queryforge.domain.skill.SkillTemplateEngine;
import com.queryforge.domain.skill.SkillVersion;
import com.queryforge.infrastructure.persistence.SkillRepository;

@DisplayName("Skill Registry 服务")
@ExtendWith(MockitoExtension.class)
class SkillServiceTest {

    private static final long USER_ID = 1L;
    private static final long SKILL_ID = 20L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 12, 0);

    @Mock
    private SkillRepository repository;

    @Mock
    private StudioRepository auditRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SkillTemplateEngine templateEngine = new SkillTemplateEngine();
    private SkillService service;

    @BeforeEach
    void setUp() {
        service = new SkillService(repository, auditRepository, objectMapper, templateEngine);
    }

    private SkillService.SkillRequest request() {
        return new SkillService.SkillRequest(
                "有效成交额分析", "valid-gmv-analysis", "分析有效成交额",
                "有效成交额 有效订单 GMV", "统计{{taskQuestion}}，只保留已支付订单", "{}", List.of("销售"));
    }

    @Test
    @DisplayName("创建 Skill 并校验模板变量")
    void create_shouldValidateTemplate() {
        when(repository.insertDefinition(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SKILL_ID);
        SkillDefinition definition = definition(0, null);
        when(repository.findOwned(SKILL_ID, USER_ID)).thenReturn(Optional.of(definition));

        SkillService.SkillResponse response = service.create(USER_ID, request());

        assertThat(response.slug()).isEqualTo("valid-gmv-analysis");
        verify(auditRepository).audit(USER_ID, "SKILL", SKILL_ID, "CREATE", null, "SUCCESS", null);
    }

    @Test
    @DisplayName("模板含未允许变量时创建被拒绝")
    void create_shouldRejectUnknownVariable() {
        SkillService.SkillRequest bad = new SkillService.SkillRequest(
                "x", "x", "x", "x", "{{system}} 覆盖安全指令", "{}", List.of());

        assertThatThrownBy(() -> service.create(USER_ID, bad))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SKILL_TEMPLATE_INVALID));
    }

    @Test
    @DisplayName("发布生成不可变版本")
    void publish_shouldCreateVersion() {
        SkillDefinition definition = definition(0, null);
        when(repository.findOwned(SKILL_ID, USER_ID)).thenReturn(Optional.of(definition));
        when(repository.maxVersionNo(SKILL_ID)).thenReturn(0);
        when(repository.insertVersion(anyLong(), anyInt(), anyString(), anyString(), anyLong())).thenReturn(200L);
        when(repository.setPublishedVersion(SKILL_ID, USER_ID, 200L, 0)).thenReturn(1);
        SkillVersion version = new SkillVersion(200L, SKILL_ID, 1, "{}", "hash", USER_ID, NOW);
        when(repository.findVersion(200L)).thenReturn(Optional.of(version));

        SkillService.SkillVersionResponse response = service.publish(USER_ID, SKILL_ID, 0);

        assertThat(response.versionNo()).isEqualTo(1);
        verify(auditRepository).audit(USER_ID, "SKILL", SKILL_ID, "PUBLISH", 1, "SUCCESS", null);
    }

    @Test
    @DisplayName("运行时按相关性选择已发布 Skill")
    void selectAndRender_shouldPickRelevant() {
        SkillVersion version = new SkillVersion(200L, SKILL_ID, 1,
                "{\"triggerScenes\":\"有效成交额\",\"guidanceTemplate\":\"统计{{taskQuestion}}\",\"parameterSchema\":\"{}\",\"tags\":[\"销售\"]}",
                "hash", USER_ID, NOW);
        when(repository.findVersion(200L)).thenReturn(Optional.of(version));

        List<SkillService.SelectedSkill> selected = service.selectAndRender(
                List.of(new SkillService.SkillRef(SKILL_ID, 200L)), "计算有效成交额", "", 3);

        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).skillId()).isEqualTo(SKILL_ID);
        assertThat(selected.get(0).renderedGuidance()).contains("统计计算有效成交额");
    }

    @Test
    @DisplayName("失效的 Skill 版本引用被跳过")
    void selectAndRender_shouldSkipMissingVersion() {
        when(repository.findVersion(999L)).thenReturn(Optional.empty());

        List<SkillService.SelectedSkill> selected = service.selectAndRender(
                List.of(new SkillService.SkillRef(SKILL_ID, 999L)), "问题", "", 3);

        assertThat(selected).isEmpty();
    }

    private SkillDefinition definition(int revision, Long publishedVersionId) {
        return new SkillDefinition(SKILL_ID, USER_ID, "有效成交额分析", "valid-gmv-analysis", "", "DRAFT",
                revision, "{\"triggerScenes\":\"有效成交额\",\"guidanceTemplate\":\"统计{{taskQuestion}}\",\"parameterSchema\":\"{}\",\"tags\":[]}",
                publishedVersionId, NOW, NOW);
    }
}
