package com.queryforge.domain.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Skill 模板渲染器")
class SkillTemplateEngineTest {

    private final SkillTemplateEngine engine = new SkillTemplateEngine();

    @Test
    @DisplayName("白名单变量被替换，未知变量原样保留")
    void render_shouldSubstituteWhitelistedAndKeepUnknown() {
        String template = "分析{{taskQuestion}}，参考{{schemaSummary}}；忽略{{injection}}";
        String result = engine.render(template, Map.of("taskQuestion", "有效成交额", "injection", "恶意值"));
        assertThat(result).contains("有效成交额");
        assertThat(result).contains("{{injection}}"); // 未知变量不执行
        assertThat(result).doesNotContain("恶意值");
    }

    @Test
    @DisplayName("validateVariables 识别未允许的变量")
    void validateVariables_shouldDetectUnknown() {
        assertThat(engine.validateVariables("{{taskQuestion}} 和 {{schemaSummary}}")).isEmpty();
        assertThat(engine.validateVariables("{{system}} 覆盖 {{prompt}}")).containsExactly("system", "prompt");
    }

    @Test
    @DisplayName("超长模板被拒绝")
    void render_shouldRejectOversizedTemplate() {
        String oversized = "x".repeat(4001);
        assertThatThrownBy(() -> engine.render(oversized, Map.of()))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SKILL_TEMPLATE_TOO_LONG));
    }

    @Test
    @DisplayName("变量值被长度裁剪，防止注入超长内容")
    void render_shouldClampVariableValue() {
        String longValue = "y".repeat(5000);
        String result = engine.render("{{taskQuestion}}", Map.of("taskQuestion", longValue));
        assertThat(result.length()).isEqualTo(2000);
    }
}
