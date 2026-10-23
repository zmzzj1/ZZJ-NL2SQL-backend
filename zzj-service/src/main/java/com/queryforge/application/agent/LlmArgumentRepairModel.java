package com.queryforge.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * MCP 工具参数的 LLM 修复模型：构造系统/用户提示词，并把模型输出解析为单一 JSON object。
 *
 * <p>修复前提是不可变的、调用前可确定的错误（Schema 校验失败），而非运行期副作用错误。
 * 因此模型只需产出合法参数，解析阶段强制“单一 JSON object、无代码围栏”，并通过
 * {@link McpToolArgumentValidator} 二次校验后才被采用。</p>
 */
public final class LlmArgumentRepairModel {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LlmArgumentRepairModel() {
    }

    public static String systemPrompt() {
        return """
                你只负责修正给定 MCP 工具的 JSON 参数。你会收到工具名称、用途、参数 Schema、
                原始（错误的）参数以及具体的校验错误。请在不改变调用意图的前提下，仅修正参数使其符合 Schema。
                仅输出单个 JSON object，不要包含任何解释、前后缀或 Markdown 代码围栏。""";
    }

    public static String userPrompt(String task, String toolName, String toolDescription,
                                    Map<String, Object> schema, Map<String, Object> originalArgs,
                                    String validationError) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：").append(task == null ? "" : task).append("\n");
        sb.append("工具名称：").append(toolName).append("\n");
        if (toolDescription != null && !toolDescription.isBlank()) {
            sb.append("工具用途：").append(toolDescription).append("\n");
        }
        sb.append("参数 Schema：\n").append(toJson(schema)).append("\n");
        sb.append("原始参数：\n").append(toJson(originalArgs)).append("\n");
        sb.append("校验错误：\n").append(validationError == null ? "" : validationError).append("\n");
        sb.append("请输出修正后的单个 JSON object 参数。");
        return sb.toString();
    }

    /** 剥离常见代码围栏后解析为单一 JSON object；任何不合法都抛 MCP_ARGUMENT_REPAIR_FAILED。 */
    public static Map<String, Object> parseStrict(String text, ObjectMapper objectMapper) {
        if (text == null || text.isBlank()) {
            throw new ApiException(ErrorCode.MCP_ARGUMENT_REPAIR_FAILED, HttpStatus.BAD_REQUEST,
                    "模型未返回可解析的参数");
        }
        String cleaned = stripFences(text);
        JsonNode node;
        try {
            node = objectMapper.readTree(cleaned);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.MCP_ARGUMENT_REPAIR_FAILED, HttpStatus.BAD_REQUEST,
                    "模型返回的不是合法 JSON");
        }
        if (node == null || !node.isObject()) {
            throw new ApiException(ErrorCode.MCP_ARGUMENT_REPAIR_FAILED, HttpStatus.BAD_REQUEST,
                    "模型返回的不是 JSON object");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.convertValue(node, Map.class);
            return map;
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.MCP_ARGUMENT_REPAIR_FAILED, HttpStatus.BAD_REQUEST,
                    "参数结构无法转换为对象");
        }
    }

    /** 去除 ```json ... ``` 或 ``` ... ``` 围栏；模型被要求不输出围栏，此处仅作防御。 */
    private static String stripFences(String text) {
        String t = text.strip();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            int fenceEnd = firstNl < 0 ? t.length() : firstNl;
            t = t.substring(fenceEnd + 1).strip();
            int lastFence = t.lastIndexOf("```");
            if (lastFence >= 0) {
                t = t.substring(0, lastFence).strip();
            }
        }
        return t;
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
