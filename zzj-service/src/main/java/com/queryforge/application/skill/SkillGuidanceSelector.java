package com.queryforge.application.skill;

import com.queryforge.domain.agent.ResolvedAgentConfig;
import com.queryforge.application.skill.SkillService;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Skill 指引选择能力（Agent Run 与 Workflow 节点共用）：
 * 按任务问题从运行配置绑定的已发布 Skill 中选择并渲染指引文本。
 *
 * <p>只记录选择证据（skillId、版本号），不泄露模板全文；无配置或无命中时返回空。
 */
@Service
public class SkillGuidanceSelector {

    private static final int MAX_SELECTED = 3;

    private final SkillService skillService;

    public SkillGuidanceSelector(SkillService skillService) {
        this.skillService = skillService;
    }

    /** 选择命中的已发布 Skill（含渲染后的指引）；配置为空或未命中时返回空列表。 */
    public List<SkillService.SelectedSkill> select(ResolvedAgentConfig config, String question) {
        if (config == null || config.skillVersionRefs().isEmpty()) {
            return List.of();
        }
        List<SkillService.SkillRef> refs = config.skillVersionRefs().entrySet().stream()
                .map(entry -> new SkillService.SkillRef(entry.getKey(), entry.getValue()))
                .toList();
        return skillService.selectAndRender(refs, question == null ? "" : question, null, MAX_SELECTED);
    }

    /** 渲染为注入提示词的指引文本；空列表返回空串。 */
    public static String render(List<SkillService.SelectedSkill> selected) {
        if (selected.isEmpty()) {
            return "";
        }
        StringBuilder guidance = new StringBuilder();
        for (SkillService.SelectedSkill s : selected) {
            guidance.append("【Skill v").append(s.versionNo()).append("】").append(s.renderedGuidance()).append("\n");
        }
        return guidance.toString();
    }
}
