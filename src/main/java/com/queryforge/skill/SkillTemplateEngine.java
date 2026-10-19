package com.queryforge.skill;

import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Skill 指引模板渲染器。只允许固定白名单变量，禁止任意脚本、表达式求值或递归模板。
 * 未在变量白名单中的占位符原样保留，绝不执行。
 */
@Component
public class SkillTemplateEngine {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9]*)\\s*}}");
    private static final List<String> ALLOWED_VARIABLES = List.of("taskQuestion", "schemaSummary");
    private static final int MAX_TEMPLATE_CHARS = 4000;

    /**
     * 渲染指引模板。variables 中只取白名单变量；值会被长度限制，防止注入超长内容。
     */
    public String render(String template, Map<String, String> variables) {
        if (template == null || template.isBlank()) {
            return "";
        }
        if (template.length() > MAX_TEMPLATE_CHARS) {
            throw new ApiException(ErrorCode.SKILL_TEMPLATE_TOO_LONG, HttpStatus.BAD_REQUEST, "指引模板超长");
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (ALLOWED_VARIABLES.contains(name) && variables != null) {
                String value = variables.getOrDefault(name, "");
                matcher.appendReplacement(result, Matcher.quoteReplacement(clamp(value, 2000)));
            } else {
                // 未知变量原样保留，不执行、不替换。
                matcher.appendReplacement(result, Matcher.quoteReplacement("{{" + name + "}}"));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** 校验模板中引用的变量是否都在白名单内；返回不合法的变量名列表（空 = 合法）。 */
    public List<String> validateVariables(String template) {
        if (template == null || template.isBlank()) {
            return List.of();
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        List<String> unknown = new java.util.ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!ALLOWED_VARIABLES.contains(name)) {
                unknown.add(name);
            }
        }
        return unknown;
    }

    private static String clamp(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
